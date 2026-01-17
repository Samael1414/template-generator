package ru.rea.report.tags;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public final class TagRegistry {

    public enum ParamType {
        STRING,
        INTEGER,
        DECIMAL,
        BOOLEAN,
        DATE,
        DATE_TIME
    }

    private final Set<String> paramNames = new LinkedHashSet<>();

    private final Map<String, ParamType> paramTypes = new LinkedHashMap<>();

    public void registerParam(String paramName) {
        registerParam(paramName, null);
    }

    public void registerParam(String paramName, ParamType type) {
        if (paramName == null) return;

        String n = paramName.trim();
        if (n.isEmpty()) return;

        paramNames.add(n);

        if (type != null) {
            paramTypes.put(n, type);
        }
    }

    public Set<String> paramNames() {
        return Collections.unmodifiableSet(paramNames);
    }

    public ParamType paramTypeOf(String paramName) {
        if (paramName == null) return null;
        return paramTypes.get(paramName.trim());
    }

    public boolean isEmpty() {
        return paramNames.isEmpty();
    }
}
