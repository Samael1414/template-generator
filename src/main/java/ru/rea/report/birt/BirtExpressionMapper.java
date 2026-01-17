package ru.rea.report.birt;

import org.springframework.stereotype.Component;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class BirtExpressionMapper {

    private static final Pattern BRACKET_PARAM =
            Pattern.compile("\\[([A-Za-z_][A-Za-z0-9_]{0,120})]");

    public boolean containsTagsOrParams(String s) {
        if (s == null || s.isBlank()) return false;
        if (BRACKET_PARAM.matcher(s).find()) return true;
        return s.contains("${");
    }

    public String toHtmlValueExpr(String text) {
        if (text == null) return "\"\"";

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
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\u2028", "")
                .replace("\u2029", "");
    }
}
