package com.storage.engine.utils;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** File semantic support shared by ingestion and callback validation. */
public final class FileSemanticSupport {
    private static final Set<String> OFFICE_EXTENSIONS = new HashSet<String>(
            Arrays.asList("doc", "docx", "pdf", "ppt", "pptx"));
    private static final Set<String> IMAGES = new HashSet<String>(
            Arrays.asList("jpg", "jpeg", "png", "bmp", "gif", "webp"));

    private FileSemanticSupport() { }

    public static String extension(String format, String fileName) {
        String extension = format == null ? "" : format.trim().toLowerCase(Locale.ROOT);
        while (extension.startsWith(".")) extension = extension.substring(1);
        if (extension.isEmpty() && fileName != null) {
            String name = fileName.trim();
            int dot = name.lastIndexOf('.');
            if (dot >= 0) extension = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        }
        return extension;
    }

    public static boolean isOffice(String dataType, String format, String fileName) {
        return "file".equalsIgnoreCase(dataType) && OFFICE_EXTENSIONS.contains(extension(format, fileName));
    }

    public static boolean isSupported(String format, String fileName) {
        String extension = extension(format, fileName);
        return IMAGES.contains(extension) || OFFICE_EXTENSIONS.contains(extension);
    }

}
