package ru.rea.report.birt;

import org.springframework.stereotype.Component;
import ru.rea.report.tags.TagRegistry;

@Component
public class BirtExpressionMapper {

    public String mapTextToBirtHtml(String text, TagRegistry tags) {
        if (text == null) return "";

        String result = text;

        if (tags != null && !tags.isEmpty()) {
            for (var e : tags.entries()) {
                String raw = e.getKey();
                String key = e.getValue();

                result = result.replace("[" + raw + "]", "&{params[\"" + key + "\"].value}");
                result = result.replace("<" + raw + ">", "&{params[\"" + key + "\"].value}");
            }
        }

        result = result.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
        result = result.replace("&amp;{", "&{");

        return "<div>" + result + "</div>";
    }

    public String mapTextToBirtPlain(String text, TagRegistry tags) {
        return mapTextToBirtHtml(text, tags);
    }
}
