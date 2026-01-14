package ru.rea.report.core;

public enum TemplateType {
    ODT, ODS;

    public static TemplateType fromFilename(String filename) {
        String lower = filename.toLowerCase();
        if (lower.endsWith(".odt")) return ODT;
        if (lower.endsWith(".ods")) return ODS;
        throw new IllegalArgumentException("Unsupported template file type: " + filename);
    }

    public static String stripExtension(String filename) {
        int idx = filename.lastIndexOf('.');
        return idx > 0 ? filename.substring(0, idx) : filename;
    }
}
