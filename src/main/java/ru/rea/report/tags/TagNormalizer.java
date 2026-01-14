package ru.rea.report.tags;

import org.springframework.stereotype.Component;

@Component
public class TagNormalizer {

    public String normalize(String raw) {
        String s = raw.trim();
        s = s.replaceAll("[\\s\\-]+", "_");
        s = s.replaceAll("[^a-zA-Z0-9_а-яА-Я]", "");
        s = s.toLowerCase();

        if (s.isBlank()) {
            return "tag";
        }
        return s;
    }
}
