import json

from metadata.SemanticKeywordExtract import (
    _RuntimeConfigMixin,
    _llm_available,
    _llm_chat,
    _parse_json_object,
    dedup_strings,
)
from metadata.neo4j_writer import Neo4jGraphWriter


def _trace(event, **fields):
    details = []
    for key in sorted(fields.keys()):
        details.append(str(key) + "=" + str(fields.get(key, "")))
    suffix = " " + " ".join(details) if details else ""
    print("[MetadataSemanticEntityRelationExpand] " + event + suffix, flush=True)


class MetadataSemanticEntityRelationExpand(_RuntimeConfigMixin):
    MAX_ASSET_GROUPS = 8
    MAX_CANDIDATES_PER_GROUP = 30

    def transform(self, data, args, kvargs):
        try:
            focus = self._focus_entity(args)
            _trace(
                "enter",
                focus=focus,
                focusEscaped=self._debug_text(focus),
                rawRows=len(data) if isinstance(data, list) else 0,
            )
            if not focus:
                _trace("exit", status="FAILED", reason="focus_entity_is_required")
                return self._result("FAILED", [], [], "entity", "focus entity is required")

            candidate_groups = self._candidate_groups(data, focus)
            if not candidate_groups:
                _trace("exit", focus=focus, status="SUCCESS", reason="no_remaining_candidates")
                return self._result("SUCCESS", [], [], "entity", "entity relation results: none (no remaining candidates)")

            params = self._params(kvargs)
            writer = Neo4jGraphWriter(params)
            _trace(
                "candidates_ready",
                focus=focus,
                groupCount=len(candidate_groups),
                candidateCount=sum(len(group["candidates"]) for group in candidate_groups),
            )

            relations = []
            pending_groups = []
            skipped_related = 0
            skipped_unrelated = 0
            for group in candidate_groups:
                candidates = group["candidates"]
                statuses = writer.get_entity_relation_statuses(
                    focus,
                    candidates,
                    group["assetKey"],
                )
                unknown = []
                for candidate in candidates:
                    status = statuses.get(self._normalize(candidate))
                    if status is True:
                        skipped_related += 1
                    elif status is False:
                        skipped_unrelated += 1
                    else:
                        unknown.append(candidate)
                if unknown:
                    pending_groups.append({
                        "assetKey": group["assetKey"],
                        "candidates": unknown,
                    })

            evaluated = sum(len(group["candidates"]) for group in pending_groups)
            _trace(
                "cache_checked",
                focus=focus,
                pending=evaluated,
                skippedRelated=skipped_related,
                skippedUnrelated=skipped_unrelated,
            )
            if evaluated and not _llm_available(params):
                message = "LLM is unavailable; pending entity relations were not cached"
                _trace("exit", focus=focus, status="FAILED", reason=message)
                return self._result("FAILED", [], [], "entity", message)

            persisted_related = 0
            persisted_unrelated = 0
            for group in pending_groups:
                candidates = group["candidates"]
                _trace("llm_request", focus=focus, candidates="|".join(candidates))
                inferred = self._infer_relations(params, focus, candidates)
                inferred_by_norm = dict((self._normalize(item["entity"]), item) for item in inferred)
                for candidate in candidates:
                    item = inferred_by_norm.get(self._normalize(candidate))
                    if item:
                        writer.persist_entity_relation(
                            focus,
                            item["relation"],
                            candidate,
                            group["assetKey"],
                            related=True,
                            source="query-udf",
                        )
                        relations.append({
                            "entity": candidate,
                            "relation": item["relation"],
                            "assetKey": group["assetKey"],
                        })
                        persisted_related += 1
                    else:
                        writer.persist_entity_relation(
                            focus,
                            "无直接语义关系",
                            candidate,
                            group["assetKey"],
                            related=False,
                            source="query-udf",
                        )
                        persisted_unrelated += 1

            _trace(
                "llm_result",
                focus=focus,
                relationCount=len(relations),
                relations="|".join([
                    item["entity"] + ":" + item["relation"] for item in relations
                ]),
            )

            relation_summary = "; ".join([
                focus + " -" + item["relation"] + "-> " + item["entity"]
                for item in relations[:12]
            ])
            if not relation_summary:
                relation_summary = "none"

            _trace(
                "exit",
                focus=focus,
                status="SUCCESS",
                evaluated=evaluated,
                related=persisted_related,
                unrelated=persisted_unrelated,
                skippedRelated=skipped_related,
                skippedUnrelated=skipped_unrelated,
                result=relation_summary,
            )

            return self._result(
                "SUCCESS",
                [item["entity"] for item in relations],
                [],
                "entity",
                "entity relation results: " + relation_summary
                + "; evaluated=" + str(evaluated)
                + ", related=" + str(persisted_related)
                + ", unrelated=" + str(persisted_unrelated)
                + ", skippedRelated=" + str(skipped_related)
                + ", skippedUnrelated=" + str(skipped_unrelated),
            )
        except Exception as exc:
            _trace("exit", focus=locals().get("focus", ""), status="FAILED", error=str(exc))
            return self._result("FAILED", [], [], "entity", str(exc))

    def _focus_entity(self, args):
        if not isinstance(args, (list, tuple)) or not args:
            return ""
        return self._decode(args[0]).strip()

    def _candidate_groups(self, data, focus):
        focus_norm = self._normalize(focus)
        groups = []
        seen_asset_keys = set()
        for row in self._rows(data):
            raw_keywords = row.get("semanticKeywords", "")
            keywords = self._parse_keywords(raw_keywords)
            contains_focus = any(self._normalize(item) == focus_norm for item in keywords)
            _trace(
                "candidate_row",
                metaKey=self._safe(row.get("key", "")),
                focusNorm=focus_norm,
                semanticKeywordsRaw=self._debug_text(raw_keywords),
                parsedKeywords=self._debug_json(keywords),
                containsFocus=contains_focus,
            )
            if not contains_focus:
                continue
            candidates = [item for item in keywords if self._normalize(item) != focus_norm]
            candidates = dedup_strings(candidates, self.MAX_CANDIDATES_PER_GROUP)
            meta_key = self._safe(row.get("key", ""))
            asset_key = "DataAsset::" + meta_key
            if candidates and meta_key and asset_key not in seen_asset_keys:
                groups.append({"assetKey": asset_key, "candidates": candidates})
                seen_asset_keys.add(asset_key)
                _trace(
                    "candidate_group_added",
                    assetKey=asset_key,
                    candidates=self._debug_json(candidates),
                )
            elif contains_focus:
                _trace(
                    "candidate_group_skipped",
                    assetKey=asset_key,
                    reason="no_candidates_or_missing_meta_key_or_duplicate_asset",
                    candidates=self._debug_json(candidates),
                )
            if len(groups) >= self.MAX_ASSET_GROUPS:
                break
        return groups

    def _infer_relations(self, params, focus, candidates):
        if not _llm_available(params):
            return []

        prompt = (
            "You decide whether metadata semantic entities are directly related. Return strict JSON only: "
            "{\"relations\":[{\"entity\":\"candidate from input\",\"relation\":\"concise Chinese relation\"}]}. "
            "Only return candidates with a clear direct semantic relation to the focus entity."
        )
        user_content = json.dumps({
            "focusEntity": focus,
            "candidateEntities": candidates,
            "rules": [
                "Use only candidates supplied in candidateEntities.",
                "Do not return weak topical similarity.",
                "Return an empty relations array when none is directly related.",
            ],
        }, ensure_ascii=False)
        messages = [
            {"role": "system", "content": prompt},
            {"role": "user", "content": user_content},
        ]
        _trace(
            "llm_request_detail",
            focus=focus,
            model=self._safe(params.get("llmModel", "")),
            systemPrompt=prompt,
            userContent=user_content,
        )
        raw_response = _llm_chat(params, self._safe(params.get("llmModel", "")), messages)
        _trace("llm_response_raw", focus=focus, response=raw_response)
        node = _parse_json_object(raw_response)
        _trace(
            "llm_response_json",
            focus=focus,
            parsed=json.dumps(node, ensure_ascii=False, sort_keys=True),
        )

        allowed = set(candidates)
        out = []
        for item in node.get("relations", []) if isinstance(node, dict) else []:
            if not isinstance(item, dict):
                continue
            entity = self._safe(item.get("entity", item.get("object", "")))
            relation = self._safe(item.get("relation", item.get("predicate", "")))
            if entity not in allowed or not relation:
                continue
            out.append({"entity": entity, "relation": relation})

        seen = set()
        return [item for item in out if not (item["entity"] in seen or seen.add(item["entity"]))]

    def _rows(self, data):
        if not isinstance(data, list) or not data or not isinstance(data[0], list):
            return []
        headers = [self._column_name(value) for value in data[0]]
        start = 1
        if len(data) > 1 and self._is_type_row(data[1]):
            start = 2

        rows = []
        for raw in data[start:]:
            if not isinstance(raw, list):
                continue
            row = {}
            for index, header in enumerate(headers):
                if index < len(raw):
                    row[header] = self._decode(raw[index])
            rows.append(row)
        return rows

    def _column_name(self, value):
        text = self._decode(value).strip().strip("`")
        if "." in text:
            text = text.split(".")[-1]
        return text.strip("()")

    def _is_type_row(self, row):
        known_types = set([
            "BINARY", "BOOLEAN", "INTEGER", "LONG", "FLOAT", "DOUBLE", "STRING", "DATE", "TIME", "TIMESTAMP",
        ])
        return bool(row) and all(self._decode(value).strip().upper() in known_types for value in row)

    def _parse_keywords(self, value):
        try:
            parsed = json.loads(self._safe(value))
            if isinstance(parsed, list):
                return dedup_strings(parsed, 80)
        except Exception:
            pass
        return []

    def _normalize(self, value):
        return "".join(char.lower() for char in self._safe(value) if char.isalnum())

    def _debug_text(self, value):
        return self._safe(value).encode("unicode_escape").decode("ascii")

    def _debug_json(self, value):
        return json.dumps(value, ensure_ascii=True, sort_keys=True)
