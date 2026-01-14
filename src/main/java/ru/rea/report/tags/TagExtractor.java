package ru.rea.report.tags;

import org.springframework.stereotype.Component;
import ru.rea.report.ir.*;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class TagExtractor {

    // Основной формат для ODS у тебя: [tag_name]
    private static final Pattern TAG_SQUARE = Pattern.compile("\\[([^\\[\\]]{1,120})\\]");
    // Дополнительно поддержим старый формат: <tag_name>
    private static final Pattern TAG_ANGLE  = Pattern.compile("<([^<>]{1,120})>");

    private final TagNormalizer normalizer;

    public TagExtractor(TagNormalizer normalizer) {
        this.normalizer = normalizer;
    }

    public TagRegistry extract(TemplateDocumentIR ir) {
        TagRegistry reg = new TagRegistry();
        if (ir == null || ir.getBlocks() == null) return reg;

        for (BlockIR block : ir.getBlocks()) {
            if (block instanceof ParagraphIR p) {
                scanText(p.getText(), reg);
            } else if (block instanceof TableIR t) {
                for (RowIR row : t.getRows()) {
                    for (CellIR cell : row.getCells()) {
                        scanText(cell.getText(), reg);
                    }
                }
            }
        }
        return reg;
    }

    private void scanText(String text, TagRegistry reg) {
        if (text == null || text.isBlank()) return;

        scanBy(text, reg, TAG_SQUARE);
        scanBy(text, reg, TAG_ANGLE);
    }

    private void scanBy(String text, TagRegistry reg, Pattern pattern) {
        Matcher m = pattern.matcher(text);
        while (m.find()) {
            String rawInside = m.group(1).trim();      // то, что внутри скобок
            if (rawInside.isBlank()) continue;

            String key = normalizer.normalize(rawInside);
            reg.register(rawInside, key);
        }
    }
}
