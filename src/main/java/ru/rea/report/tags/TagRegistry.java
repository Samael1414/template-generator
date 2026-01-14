package ru.rea.report.tags;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

public final class TagRegistry {

    private final Set<String> paramNames = new LinkedHashSet<>();

    public void registerParam(String paramName) {
        if (paramName == null) return;
        String n = paramName.trim();
        if (!n.isEmpty()) paramNames.add(n);
    }

    public Set<String> paramNames() {
        return Collections.unmodifiableSet(paramNames);
    }

    public boolean isEmpty() {
        return paramNames.isEmpty();
    }
}
