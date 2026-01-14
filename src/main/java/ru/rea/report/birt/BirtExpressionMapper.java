package ru.rea.report.birt;

import org.springframework.stereotype.Component;
import ru.rea.report.tags.TagRegistry;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class BirtExpressionMapper {

    private static final Pattern BRACKET_PARAM =
            Pattern.compile("\\[([A-Za-z_][A-Za-z0-9_]{0,120})]");

    /** true если текст содержит хотя бы один [param] */
    public boolean hasParams(String text) {
        if (text == null || text.isBlank()) return false;
        return BRACKET_PARAM.matcher(text).find();
    }

    /**
     * Строит JS expression для TextData.valueExpr, возвращающий HTML-строку:
     * "abc<br/>" + params["x"].value + " def"
     */
    public String toHtmlValueExpr(String text) {
        if (text == null) return "\"\"";

        // нормализуем переносы сразу
        String src = text.replace("\r\n", "\n").replace("\r", "\n");

        Matcher m = BRACKET_PARAM.matcher(src);
        StringBuilder expr = new StringBuilder();
        int pos = 0;
        boolean first = true;

        while (m.find()) {
            String before = src.substring(pos, m.start());
            String name = m.group(1);

            if (!before.isEmpty()) {
                String lit = escapeJsString(before).replace("\n", "<br/>");
                if (!first) expr.append(" + ");
                expr.append("\"").append(lit).append("\"");
                first = false;
            }

            if (!first) expr.append(" + ");
            expr.append("params[\"").append(name).append("\"].value");
            first = false;

            pos = m.end();
        }

        String tail = src.substring(pos);
        if (!tail.isEmpty() || first) {
            String lit = escapeJsString(tail).replace("\n", "<br/>");
            if (!first) expr.append(" + ");
            expr.append("\"").append(lit).append("\"");
        }

        return expr.toString();
    }

    private static String escapeJsString(String s) {
        // для JavaScript-строки в двойных кавычках
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\u2028", "")   // на всякий случай
                .replace("\u2029", "");
    }
}
