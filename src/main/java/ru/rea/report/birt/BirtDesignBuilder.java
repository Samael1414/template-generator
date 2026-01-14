package ru.rea.report.birt;

import com.ibm.icu.util.ULocale;
import lombok.RequiredArgsConstructor;
import org.eclipse.birt.report.model.api.*;
import org.eclipse.birt.report.model.api.activity.SemanticException;
import org.eclipse.birt.report.model.api.elements.DesignChoiceConstants;
import org.springframework.stereotype.Component;
import ru.rea.report.exception.TemplateProcessingException;
import ru.rea.report.ir.*;
import ru.rea.report.tags.TagRegistry;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

@Component
@RequiredArgsConstructor
public class BirtDesignBuilder {

    private static final String LOG = "[TPLGEN][BIRT] ";

    private final IDesignEngine designEngine;
    private final BirtExpressionMapper exprMapper;

    public void build(TemplateDocumentIR ir, TagRegistry tags, OutputStream out) {
        Path tmp = null;
        try {
            log("build:start locale=" + Locale.getDefault());

            SessionHandle session = designEngine.newSessionHandle(ULocale.getDefault());
            ReportDesignHandle report = session.createDesign();

            ensureMasterPage(report);
            declareParams(report, tags);

            if (ir != null && ir.getBlocks() != null) {
                int i = 0;
                for (BlockIR block : ir.getBlocks()) {
                    i++;
                    if (block instanceof ParagraphIR p) {
                        log("block#" + i + ": paragraph len=" + safeLen(p.getText()));
                        DesignElementHandle el = buildTextData(report, p.getText());
                        report.getBody().add(el);

                    } else if (block instanceof TableIR t) {
                        log("block#" + i + ": table rows=" + safeRows(t));
                        GridHandle grid = buildGridFromTable(report, t);
                        if (grid != null) report.getBody().add(grid);

                    } else {
                        log("block#" + i + ": skip type=" + (block == null ? "null" : block.getClass().getSimpleName()));
                    }
                }
            } else {
                log("build: ir has no blocks");
            }

            tmp = Files.createTempFile("tplgen-", ".rptdesign");
            report.saveAs(tmp.toAbsolutePath().toString());
            log("build:saved tmp=" + tmp);

            // КЛЮЧЕВО: пробуем открыть только что сохранённый rptdesign этим же BIRT Model API.
            // Если тут упадёт — это реальная причина "corrupted item", и будет stacktrace в твоих логах.
            selfParseValidate(session, tmp);

            try (InputStream in = Files.newInputStream(tmp)) {
                in.transferTo(out);
                out.flush();
            }

            log("build:done");
        } catch (Exception e) {
            log("build:ERROR " + e.getClass().getName() + ": " + e.getMessage());
            throw new TemplateProcessingException("Failed to build rptdesign via BIRT model API", e);
        } finally {
            if (tmp != null) {
                try { Files.deleteIfExists(tmp); } catch (Exception ignore) {}
            }
        }
    }

    private void selfParseValidate(SessionHandle session, Path tmp) {
        try {
            log("selfParse:openDesign start");
            ReportDesignHandle reopened = session.openDesign(tmp.toAbsolutePath().toString());
            // форсим доступ к дереву, чтобы спровоцировать возможные ошибки ленивой загрузки
            int bodyCount = reopened.getBody().getCount();
            log("selfParse:openDesign ok bodyCount=" + bodyCount);

            // Дополнительно пройдёмся по body и залогируем типы/имена
            for (int i = 0; i < bodyCount; i++) {
                Object obj = reopened.getBody().get(i);
                if (obj instanceof DesignElementHandle h) {
                    log("selfParse:body[" + i + "] type=" + h.getElement() + " name=" + h.getName() + " id=" + h.getID());
                } else {
                    log("selfParse:body[" + i + "] obj=" + (obj == null ? "null" : obj.getClass().getName()));
                }
            }

            log("selfParse:done");
        } catch (Exception ex) {
            log("selfParse:FAILED " + ex.getClass().getName() + ": " + ex.getMessage());
            // важно: не глотаем — это должен увидеть ты и сервис
            throw new TemplateProcessingException("Generated rptdesign cannot be reopened by BIRT model API (corrupted design)", ex);
        }
    }

    private void ensureMasterPage(ReportDesignHandle report) throws SemanticException {
        if (report.getMasterPages().getCount() > 0) {
            log("masterPage: exists count=" + report.getMasterPages().getCount());
            return;
        }

        log("masterPage: create");
        SimpleMasterPageHandle mp = report.getElementFactory().newSimpleMasterPage(BirtResources.MASTER_PAGE);
        mp.setPageType("a4");
        mp.setProperty(SimpleMasterPageHandle.TOP_MARGIN_PROP, "2cm");
        mp.setProperty(SimpleMasterPageHandle.LEFT_MARGIN_PROP, "2cm");
        mp.setProperty(SimpleMasterPageHandle.BOTTOM_MARGIN_PROP, "2cm");
        mp.setProperty(SimpleMasterPageHandle.RIGHT_MARGIN_PROP, "2cm");
        mp.setProperty(SimpleMasterPageHandle.HEADER_HEIGHT_PROP, "0cm");
        mp.setProperty(SimpleMasterPageHandle.FOOTER_HEIGHT_PROP, "0cm");
        mp.setProperty(SimpleMasterPageHandle.SHOW_HEADER_ON_FIRST_PROP, "false");
        mp.setProperty(SimpleMasterPageHandle.SHOW_FOOTER_ON_LAST_PROP, "false");
        report.getMasterPages().add(mp);
    }

    private void declareParams(ReportDesignHandle report, TagRegistry tags) throws SemanticException {
        if (tags == null || tags.isEmpty()) {
            log("params: none");
            return;
        }

        int added = 0, skipped = 0;
        for (String paramName : tags.paramNames()) {
            if (hasParameter(report, paramName)) {
                skipped++;
                continue;
            }
            ScalarParameterHandle p = report.getElementFactory().newScalarParameter(paramName);
            p.setDataType(DesignChoiceConstants.PARAM_TYPE_STRING);
            p.setProperty(ScalarParameterHandle.PROMPT_TEXT_PROP, paramName);
            report.getParameters().add(p);
            added++;
        }
        log("params: added=" + added + " skipped=" + skipped + " totalNow=" + report.getParameters().getCount());
    }

    private DesignElementHandle buildTextData(ReportDesignHandle report, String srcText) throws SemanticException {
        String clean = sanitizeText(srcText);
        String jsExpr = exprMapper.toHtmlValueExpr(clean);

        TextDataHandle td = report.getElementFactory().newTextData(null);
        td.setProperty(TextDataHandle.CONTENT_TYPE_PROP, DesignChoiceConstants.TEXT_DATA_CONTENT_TYPE_HTML);
        td.setProperty(TextDataHandle.VALUE_EXPR_PROP, new Expression(jsExpr, "javascript"));

        log("paragraph:textData srcLen=" + safeLen(srcText) + " cleanLen=" + safeLen(clean) + " exprLen=" + safeLen(jsExpr));
        return td;
    }

    /**
     * Делает span + ЖЁСТКО drop=all на зоне покрытия.
     * Логи + validateDrops.
     */
    private GridHandle buildGridFromTable(ReportDesignHandle report, TableIR table) throws SemanticException {
        if (table == null || table.getRows() == null || table.getRows().isEmpty()) {
            log("grid:skip empty table");
            return null;
        }

        var nt = TableIR.TableNormalizer.normalize(table);
        int rows = nt.rows();
        int cols = nt.cols();
        CellIR[][] m = nt.cells();

        log("grid:normalize rows=" + rows + " cols=" + cols);

        GridHandle grid = report.getElementFactory().newGridItem(null, cols, rows);

        int droppedByCoveredFlag = 0;
        int droppedBySpan = 0;
        int mastersWithSpan = 0;

        // небольшая “шапка” по первым строкам, чтобы видеть что реально прилетело
        dumpTopLeftSnapshot(m, Math.min(rows, 5), Math.min(cols, 12));

        for (int r = 0; r < rows; r++) {
            RowHandle gridRow = (RowHandle) grid.getRows().get(r);

            for (int c = 0; c < cols; c++) {
                CellHandle ghCell = (CellHandle) gridRow.getCells().get(c);
                CellIR cell = m[r][c];

                if (cell.isCovered()) {
                    markDropped(ghCell);
                    droppedByCoveredFlag++;
                    continue;
                }

                int cs = Math.min(Math.max(cell.getColSpan(), 1), cols - c);
                int rs = Math.min(Math.max(cell.getRowSpan(), 1), rows - r);

                if (cs > 1 || rs > 1) {
                    mastersWithSpan++;
                    if (cs > 1) ghCell.setColumnSpan(cs);
                    if (rs > 1) ghCell.setRowSpan(rs);

                    log("grid:masterSpan r=" + r + " c=" + c + " cs=" + cs + " rs=" + rs
                            + " text='" + preview(cell.getText(), 60) + "'");

                    for (int rr = r; rr < r + rs; rr++) {
                        RowHandle spanRow = (RowHandle) grid.getRows().get(rr);
                        for (int cc = c; cc < c + cs; cc++) {
                            if (rr == r && cc == c) continue;

                            CellHandle coveredCell = (CellHandle) spanRow.getCells().get(cc);
                            markDropped(coveredCell);
                            droppedBySpan++;

                            // чтобы не заспамить — логируем только первую строку покрытия
                            if (rr == r) {
                                log("grid:drop bySpan rr=" + rr + " cc=" + cc + " (master r=" + r + " c=" + c + ")");
                            }
                        }
                    }
                }

                applyCellStyle(ghCell, cell.getStyle());

                String txt = sanitizeText(cell.getText());
                if (txt == null || txt.isBlank()) continue;

                ghCell.getContent().add(buildText(report, txt, cell.getStyle()));
            }
        }

        log("grid:stats mastersWithSpan=" + mastersWithSpan
                + " droppedByCoveredFlag=" + droppedByCoveredFlag
                + " droppedBySpan=" + droppedBySpan);

        validateGridDrops(grid);

        return grid;
    }

    private static void dumpTopLeftSnapshot(CellIR[][] m, int rMax, int cMax) {
        System.out.println(LOG + "grid:snapshot " + rMax + "x" + cMax);
        for (int r = 0; r < rMax; r++) {
            StringBuilder sb = new StringBuilder();
            sb.append(LOG).append("R").append(r).append(": ");
            for (int c = 0; c < cMax; c++) {
                CellIR cell = m[r][c];
                String t = (cell == null) ? "" : preview(cell.getText(), 18);
                int cs = (cell == null) ? 1 : Math.max(cell.getColSpan(), 1);
                int rs = (cell == null) ? 1 : Math.max(cell.getRowSpan(), 1);
                boolean cov = cell != null && cell.isCovered();
                sb.append("[")
                        .append(cov ? "C" : " ")
                        .append(" cs=").append(cs)
                        .append(" rs=").append(rs)
                        .append(" '").append(t).append("']")
                        .append(" ");
            }
            System.out.println(sb);
        }
    }

    private static void markDropped(CellHandle cell) throws SemanticException {
        cell.setProperty(CellHandle.DROP_PROP, DesignChoiceConstants.DROP_TYPE_ALL);
        clearSlot(cell.getContent());
        cell.setColumnSpan(1);
        cell.setRowSpan(1);
    }

    private static boolean hasParameter(ReportDesignHandle report, String paramName) {
        if (report == null || paramName == null || paramName.isBlank()) return false;

        SlotHandle slot = report.getParameters();
        for (int i = 0; i < slot.getCount(); i++) {
            Object obj = slot.get(i);
            if (obj instanceof ParameterHandle p) {
                if (paramName.equals(p.getName())) return true;
            }
        }
        return false;
    }

    private DesignElementHandle buildText(ReportDesignHandle report, String srcText, StyleIR style) throws SemanticException {
        srcText = (srcText == null) ? "" : srcText;

        if (!exprMapper.containsTagsOrParams(srcText)) {
            TextItemHandle t = report.getElementFactory().newTextItem(null);
            t.setContentType(DesignChoiceConstants.TEXT_CONTENT_TYPE_HTML);
            t.setContent(escapeHtml(srcText));
            applyTextStyle(t, style);
            return t;
        }

        String jsExpr = exprMapper.toHtmlValueExpr(srcText);

        TextDataHandle td = report.getElementFactory().newTextData(null);
        td.setProperty(TextDataHandle.CONTENT_TYPE_PROP, DesignChoiceConstants.TEXT_DATA_CONTENT_TYPE_HTML);
        td.setProperty(TextDataHandle.VALUE_EXPR_PROP, new Expression(jsExpr, "javascript"));
        applyTextStyle(td, style);
        return td;
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static String sanitizeText(String s) {
        if (s == null) return null;
        // выкидываем управляющие символы кроме \n \t
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '\n' || ch == '\t') {
                out.append(ch);
                continue;
            }
            if (ch < 0x20) {
                // логируем редко: только если реально нашли мусор
                // (чтобы не залить консоль)
                continue;
            }
            out.append(ch);
        }
        return out.toString();
    }

    private static String preview(String s, int max) {
        if (s == null) return "";
        String clean = s.replace("\n", "\\n").replace("\t", "\\t");
        if (clean.length() <= max) return clean;
        return clean.substring(0, max) + "...";
    }

    private static void clearSlot(SlotHandle slot) throws SemanticException {
        while (slot.getCount() > 0) {
            slot.drop(0);
        }
    }

    private static void applyCellStyle(CellHandle cell, StyleIR st) throws SemanticException {
        if (st == null) return;

        if (st.getBackgroundColor() != null && !st.getBackgroundColor().isBlank()) {
            cell.setProperty(StyleHandle.BACKGROUND_COLOR_PROP, st.getBackgroundColor());
        }

        if (st.getAlign() != null) {
            cell.setProperty(StyleHandle.TEXT_ALIGN_PROP, st.getAlign());
        }

        if (st.getBorderColor() != null && !st.getBorderColor().isBlank()) {
            cell.setProperty(StyleHandle.BORDER_TOP_COLOR_PROP, st.getBorderColor());
            cell.setProperty(StyleHandle.BORDER_BOTTOM_COLOR_PROP, st.getBorderColor());
            cell.setProperty(StyleHandle.BORDER_LEFT_COLOR_PROP, st.getBorderColor());
            cell.setProperty(StyleHandle.BORDER_RIGHT_COLOR_PROP, st.getBorderColor());

            cell.setProperty(StyleHandle.BORDER_TOP_STYLE_PROP, DesignChoiceConstants.LINE_STYLE_SOLID);
            cell.setProperty(StyleHandle.BORDER_BOTTOM_STYLE_PROP, DesignChoiceConstants.LINE_STYLE_SOLID);
            cell.setProperty(StyleHandle.BORDER_LEFT_STYLE_PROP, DesignChoiceConstants.LINE_STYLE_SOLID);
            cell.setProperty(StyleHandle.BORDER_RIGHT_STYLE_PROP, DesignChoiceConstants.LINE_STYLE_SOLID);

            cell.setProperty(StyleHandle.BORDER_TOP_WIDTH_PROP, "1px");
            cell.setProperty(StyleHandle.BORDER_BOTTOM_WIDTH_PROP, "1px");
            cell.setProperty(StyleHandle.BORDER_LEFT_WIDTH_PROP, "1px");
            cell.setProperty(StyleHandle.BORDER_RIGHT_WIDTH_PROP, "1px");
        }
    }

    private static void applyTextStyle(DesignElementHandle textEl, StyleIR st) throws SemanticException {
        if (st == null) return;

        if (st.isBold()) {
            textEl.setProperty(StyleHandle.FONT_WEIGHT_PROP, DesignChoiceConstants.FONT_WEIGHT_BOLD);
        }
        if (st.isItalic()) {
            textEl.setProperty(StyleHandle.FONT_STYLE_PROP, DesignChoiceConstants.FONT_STYLE_ITALIC);
        }
        if (st.getFontSizePt() != null && st.getFontSizePt() > 0) {
            textEl.setProperty(StyleHandle.FONT_SIZE_PROP, st.getFontSizePt() + "pt");
        }
    }

    private void validateGridDrops(GridHandle grid) throws SemanticException {
        int rows = grid.getRows().getCount();
        int cols = ((RowHandle) grid.getRows().get(0)).getCells().getCount();

        int problems = 0;

        for (int r = 0; r < rows; r++) {
            RowHandle row = (RowHandle) grid.getRows().get(r);
            for (int c = 0; c < cols; c++) {
                CellHandle cell = (CellHandle) row.getCells().get(c);

                int cs = safeInt(cell.getColumnSpan(), 1);
                int rs = safeInt(cell.getRowSpan(), 1);
                if (cs == 1 && rs == 1) continue;

                for (int rr = r; rr < r + rs; rr++) {
                    RowHandle spanRow = (RowHandle) grid.getRows().get(rr);
                    for (int cc = c; cc < c + cs; cc++) {
                        if (rr == r && cc == c) continue;

                        CellHandle covered = (CellHandle) spanRow.getCells().get(cc);
                        Object drop = covered.getProperty(CellHandle.DROP_PROP);

                        if (drop == null || !"all".equals(String.valueOf(drop))) {
                            problems++;
                            log("grid:VALIDATION FAIL master r=" + r + " c=" + c + " cs=" + cs + " rs=" + rs
                                    + " -> covered rr=" + rr + " cc=" + cc + " drop=" + drop);
                        }
                    }
                }
            }
        }

        log("grid:validation problems=" + problems);
    }

    private static int safeInt(Object v, int def) {
        if (v == null) return def;
        if (v instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(String.valueOf(v));
        } catch (Exception e) {
            return def;
        }
    }

    private static int safeRows(TableIR t) {
        try {
            return t.getRows() == null ? 0 : t.getRows().size();
        } catch (Exception e) {
            return -1;
        }
    }

    private static int safeLen(String s) {
        return s == null ? 0 : s.length();
    }

    private static void log(String s) {
        System.out.println(LOG + s);
    }
}
