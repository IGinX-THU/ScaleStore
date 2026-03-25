# 智能元数据服务设计说明（重构版：System Cypher + LLM Cypher）

本文说明当前重构后的查询体系。目标是把“查找”拆成两条明确通道：

- System Cypher：由系统根据结构化参数稳定生成 Cypher，不依赖关键词规则推断
- LLM Cypher：由 LLM 根据自然语言生成 Cypher，处理复杂语义问题

同时，前端按屏幕模式分层：

- 非全屏：默认实体语义查找（直接走 System Cypher）
- 全屏：显示高级查询控件，可切换 System/LLM 模式

## 1. 核心代码位置

- 查询编排：backend/src/main/java/com/storage/engine/service/MetadataKnowledgeService.java
- 查询接口：backend/src/main/java/com/storage/engine/controller/MetadataController.java
- LLM 转 Cypher：backend/src/main/java/com/storage/engine/service/LlmService.java
- 图数据库执行：backend/src/main/java/com/storage/engine/dao/Neo4jDao.java
- 前端查询 UI：frontend/index.html, frontend/app.js, frontend/styles.css

## 2. 本体模型（LLM 与 System 共用）

### 2.1 节点

- LogicalPath: path, name, depth
- DataAsset: logicalPath, dataType, fileName, fileFormat, fileSize, createTime
- Field: ukey, norm, kind, name
- Entity: norm, name

### 2.2 关系

- CONTAINS: LogicalPath -> LogicalPath
- HAS_DATA: LogicalPath -> DataAsset
- HAS_FILED: DataAsset -> Field
- MENTIONS: DataAsset -> Entity
- SEMANTIC_RELATION: Entity -> Entity（relation, sourcePath, updatedAt）

## 3. 后端查询接口设计

统一入口：GET /metadata/query

参数：

- mode: system 或 llm，默认 system
- q: LLM 自然语言问题（llm 模式必需）
- logicalPath: 逻辑路径前缀（system 可选）
- dataType: 数据类型（system 可选）
- keyword: 实体或字段关键词（system 可选）

## 4. System Cypher 设计（稳定查询通道）

### 4.1 设计目标

- 不再做“关键词猜测式模板分支”
- 用户填什么参数，就拼什么过滤条件
- 可空参数不会报错，只是不参与 WHERE

### 4.2 执行步骤

步骤 S1：参数归一化

- logicalPath 规范化为前缀路径（确保以 / 开头）
- dataType 转小写
- keyword 去首尾空格

步骤 S2：构建 WHERE 条件列表

- 填了 logicalPath -> `coalesce(d.logicalPath,'') STARTS WITH logicalPath`
- 填了 dataType -> `toLower(coalesce(d.dataType,'')) = dataType`
- 填了 keyword -> 同时匹配 `Entity(name/norm)` 与 `Field(name/norm)`

步骤 S3：构建统一子图骨架（固定 MATCH 结构）

```cypher
MATCH (d:DataAsset)
OPTIONAL MATCH (p:LogicalPath)-[hd:HAS_DATA]->(d)
OPTIONAL MATCH (d)-[m:MENTIONS]->(e:Entity)
OPTIONAL MATCH (d)-[hf:HAS_FILED]->(f:Field)
OPTIONAL MATCH (e)-[sr:SEMANTIC_RELATION]->(t:Entity)
...WHERE(按已填写参数拼接)
RETURN p,hd,d,m,e,hf,f,sr,t
LIMIT 150
```

步骤 S4：执行并返回

- strategy=system
- strategyReason=structured_filters
- strategyConfidence=1.0
- 返回 cypher 原文用于调试

### 4.3 不同参数组合会得到什么

仅 keyword

- 返回命中实体及其来源 DataAsset（通过 MENTIONS）
- 同时返回路径（HAS_DATA）与实体外扩语义关系（SEMANTIC_RELATION）

dataType + keyword

- 在实体命中基础上再过滤类型
- 例如“文档 + 足球”只保留 document 数据中的足球相关资产

logicalPath + dataType

- 返回指定路径下、指定类型的数据资产图

keyword

- 返回语义实体或字段相关资产与关系

全部为空

- 返回全局子图（受 LIMIT 控制）

## 5. LLM Cypher 设计（复杂语义通道）

### 5.1 进入条件

- mode=llm

### 5.2 执行步骤

步骤 L1：构造增强 Prompt

- 明确本体节点、关系、属性
- 强约束输出：只允许一条只读 Cypher
- 要求优先返回节点和关系，不只返回 count
- 强调实体问题要把来源文件一起返回（MENTIONS + HAS_DATA）

步骤 L2：调用 LLM 生成 Cypher

步骤 L3：安全校验

- 禁止 CREATE/MERGE/DELETE/SET/REMOVE/DROP/APOC/CALL DBMS
- 仅接受 MATCH/OPTIONAL MATCH/WITH/UNWIND 起始

步骤 L4：自动补 LIMIT 80 并执行

步骤 L5：异常回退

- LLM 失败/不安全/执行错误/空结果 -> 自动回退系统参数化查询
- 回退参数推断规则：
  - dataType: 从领域词识别（文档/document、图像/image、时序/timeseries、关系/relational、键值/keyvalue）
  - keyword: 从自然语言模式提取（有关X的、关于X的、与X相关、查找X数据）
  - 若无法推断，退化为关键词回退查询

### 5.3 返回标记

- 正常 LLM：strategy=llm, strategyReason=nl_to_cypher
- 回退：strategy=system_fallback 或 keyword_fallback
- 回退结果包含：fallbackDataType、fallbackKeyword、fallbackInference（规则命中明细）

## 6. 前端 UI 与交互设计

### 6.1 非全屏（默认）

- 只展示一个主输入框：实体关键词
- 点击“查找”时固定走 mode=system
- 主输入内容映射为 keyword
- 目的：在窄区域下保证高可用与稳定命中

### 6.2 全屏（高级）

- 显示高级控制区
- 新增查询模式选择：
  - 系统参数化（system）
  - LLM自然语言（llm）
- system 模式可填：logicalPath、dataType、keyword
- llm 模式可填：LLM 问题（q）
- 模式切换时自动禁用不相关输入框，避免误填

### 6.3 前端调用映射

非全屏查找：

- GET /metadata/query?mode=system&keyword=...

全屏 + system：

- GET /metadata/query?mode=system&logicalPath=...&dataType=...&keyword=...

全屏 + llm：

- GET /metadata/query?mode=llm&q=...

## 7. 你提到的“稳定性”为什么会改善

旧逻辑问题：

- 需要“猜”用户意图（关键词规则、置信度门控）
- 同一句话可能因分词差异走不同分支

新逻辑改进：

- system 模式不猜意图，参数即条件
- 非全屏默认 system，避免窄 UI 下误走复杂路径
- llm 仅在用户明确选择时启用

因此可预测性显著提高，查询行为与输入参数一一对应。

## 8. 安全与性能边界

- 仅允许只读 Cypher
- System 模式 LIMIT 默认 150
- LLM 模式 LIMIT 默认 80
- 返回节点关系子图，便于前端直接绘制

## 9. 后续建议

1. 在前端展示 strategy/strategyReason，提升可解释性。
2. 为 system 模式增加“保存查询模板”能力。
3. 为 llm 模式增加“生成后人工确认再执行”开关，进一步提升可控性。
