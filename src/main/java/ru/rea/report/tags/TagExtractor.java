package ru.rea.report.tags;

import org.springframework.stereotype.Component;
import ru.rea.report.ir.*;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class TagExtractor {

    private static final Pattern BRACKET_TAG = Pattern.compile("\\[([A-Za-z_][A-Za-z0-9_]{0,120})]");

    public TagRegistry extract(TemplateDocumentIR ir) {
        TagRegistry reg = new TagRegistry();
        if (ir == null || ir.getBlocks() == null) return reg;

        for (BlockIR block : ir.getBlocks()) {
            if (block instanceof ParagraphIR p) {
                scanText(p.getText(), reg);
            } else if (block instanceof TableIR t) {
                if (t.getRows() == null) continue;
                for (RowIR row : t.getRows()) {
                    if (row.getCells() == null) continue;
                    for (CellIR cell : row.getCells()) {
                        scanText(cell.getText(), reg);
                    }
                }
            }
        }
        return reg;
    }

    private static void scanText(String text, TagRegistry reg) {
        if (text == null || text.isBlank()) return;

        Matcher m = BRACKET_TAG.matcher(text);
        while (m.find()) {
            reg.registerParam(m.group(1));
        }
    }
}
