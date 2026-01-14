package ru.rea.report.birt;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class BirtExpressionMapper {

    // Ищем строго [A-Za-z0-9_] чтобы не ловить мусор
    private static final Pattern BRACKET_PARAM = Pattern.compile("\\[([A-Za-z_][A-Za-z0-9_]{0,120})]");

    /**
     * Для TextItem с contentType=HTML:
     *  - превращаем [param] в &{params["param"].value}
     *  - экранируем HTML (чтобы ODS/ODT спецсимволы не ломали rptdesign)
     *  - выражения BIRT оставляем НЕэкранированными
     */
    public String mapTextToBirtHtml(String text) {
        if (text == null || text.isBlank()) {
            return "<div></div>";
        }

        // 1) заменяем [param] на временные токены, чтобы потом не заэкранировать &{...}
        List<String> exprs = new ArrayList<>();
        String withTokens = replaceBracketParamsToTokens(text, exprs);

        // 2) экранируем HTML
        String escaped = escapeHtml(withTokens);

        // 3) возвращаем выражения на место (токены уже экранированы, а нам нужно сырьё)
        String restored = restoreTokens(escaped, exprs);

        // 4) переносы строк в HTML
        restored = restored.replace("\r\n", "\n").replace("\r", "\n").replace("\n", "<br/>");

        return "<div>" + restored + "</div>";
    }

    /**
     * Для TextItem с contentType=PLAIN:
     *  - оставляем обычный текст как есть
     *  - [param] -> &{params["param"].value}
     * Важно: PLAIN обычно быстрее/проще, но если BIRT у тебя не вычисляет &{...} в PLAIN,
     * тогда используй HTML-вариант в BirtDesignBuilder.
     */
    public String mapTextToBirtPlain(String text) {
        if (text == null || text.isBlank()) return "";
        // В PLAIN ничего не экранируем, просто подстановка
        return replaceBracketParamsToExpr(text);
    }

    private static String replaceBracketParamsToExpr(String text) {
        Matcher m = BRACKET_PARAM.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String name = m.group(1);
            String expr = "&{params[\"" + name + "\"].value}";
            m.appendReplacement(sb, Matcher.quoteReplacement(expr));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String replaceBracketParamsToTokens(String text, List<String> outExprs) {
        Matcher m = BRACKET_PARAM.matcher(text);
        StringBuffer sb = new StringBuffer();
        int idx = 0;
        while (m.find()) {
            String name = m.group(1);
            String expr = "&{params[\"" + name + "\"].value}";
            outExprs.add(expr);

            String token = "___TPLGEN_EXPR_" + (idx++) + "___";
            m.appendReplacement(sb, Matcher.quoteReplacement(token));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String restoreTokens(String escaped, List<String> exprs) {
        String result = escaped;
        for (int i = 0; i < exprs.size(); i++) {
            String token = "___TPLGEN_EXPR_" + i + "___";
            result = result.replace(token, exprs.get(i));
        }
        return result;
    }

    private static String escapeHtml(String s) {
        // минимально необходимое
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
