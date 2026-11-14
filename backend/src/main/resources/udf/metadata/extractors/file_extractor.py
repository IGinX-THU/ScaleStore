"""Route file semantics by format; all branches share FileSemanticKeywordExtract."""
from .base_extractor import BaseMetadataExtractor
from .image_file_extractor import ImageFileMetadataExtractor
from .office_file_extractor import OFFICE_EXTENSIONS, OfficeFileMetadataExtractor


class FileMetadataExtractor(BaseMetadataExtractor):
    IMAGE_EXTENSIONS = ImageFileMetadataExtractor.IMAGE_EXTENSIONS

    def extract(self):
        extension = self._file_format()
        if extension in OFFICE_EXTENSIONS:
            return OfficeFileMetadataExtractor(self.data, self.args, self.params).extract()
        if extension in self.IMAGE_EXTENSIONS:
            return ImageFileMetadataExtractor(self.data, self.args, self.params).extract()
        result = self.build_result(
            "field",
            message="unsupported file format; semantic extraction skipped",
            skip_keyword_normalization=True,
        )
        result["status"] = "SKIPPED"
        return result

    def _file_format(self):
        text = self._safe(self.params.get("fileFormat", "")).lower().lstrip(".")
        if text:
            return text
        file_name = self._safe(self.params.get("fileName", "")).lower()
        if "." in file_name:
            return file_name.rsplit(".", 1)[-1]
        return ""
