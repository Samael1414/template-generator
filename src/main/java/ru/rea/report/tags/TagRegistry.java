package ru.rea.report.tags;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public final class TagRegistry {
    private final Map<String, String> rawToKey = new LinkedHashMap<>();

    public void register(String rawTag, String key) {
        rawToKey.putIfAbsent(rawTag, key);
    }

    public Set<Map.Entry<String, String>> entries() {
        return rawToKey.entrySet();
    }

    public boolean isEmpty() { return rawToKey.isEmpty(); }

    public boolean containsRaw(String rawTag) {
        return rawToKey.containsKey(rawTag);
    }

    public String paramNameByRaw(String rawTag) {
        return rawToKey.getOrDefault(rawTag, rawTag);
    }
}
