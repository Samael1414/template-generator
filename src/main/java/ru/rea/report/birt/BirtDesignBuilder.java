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

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;

@Component
@RequiredArgsConstructor
public class BirtDesignBuilder {

    private final IDesignEngine designEngine;
    private final BirtExpressionMapper exprMapper;

    private static final boolean KEEP_DEBUG_ARTIFACTS = true;

    public void build(TemplateDocumentIR ir, TagRegistry tags, OutputStream out) {
        SessionHandle session = null;

        StringBuilder dbg = new StringBuilder(64_000);
        DebugSink log = new DebugSink(dbg);

        Path debugDir = null;
        Path tmpRpt = null;

        try {
            debugDir = Files.createTempDirectory("tplgen-debug-");
            log.i("debugDir=" + debugDir);

            session = designEngine.newSessionHandle(ULocale.getDefault());
            log.i("build:start locale=" + ULocale.getDefault());

            ReportDesignHandle report = session.createDesign();

            report.setProperty("language", "javascript");
            report.setProperty("locale", ULocale.getDefault().toString());
            report.setProperty("units", "mm");
            report.setProperty("layoutPreference", "auto layout");
            report.setProperty("imageDPI", "96");

            ensureMasterPage(report, log);
            declareParams(report, tags, log);

            // --- MASTER_GRID 1x1 как в рабочем шаблоне ---
            GridHandle masterGrid = report.getElementFactory().newGridItem("MASTER_GRID", 1, 1);
            masterGrid.setProperty("width", "500mm");
            ColumnHandle mgCol = (ColumnHandle) masterGrid.getColumns().get(0);
            mgCol.setProperty("width", "500mm");

            RowHandle mgRow = (RowHandle) masterGrid.getRows().get(0);
            CellHandle mgCell = (CellHandle) mgRow.getCells().get(0);
            // ---------------------------------------------

            int blocks = (ir == null || ir.getBlocks() == null) ? 0 : ir.getBlocks().size();
            log.i("blocks=" + blocks);

            if (ir != null && ir.getBlocks() != null) {
                int idx = 0;
                for (BlockIR block : ir.getBlocks()) {
                    idx++;
                    if (block instanceof ParagraphIR p) {
                        log.i("block#" + idx + ": paragraph textLen=" + safe(p.getText()).length());
                        DesignElementHandle el = buildTextData(report, p.getText());
                        mgCell.getContent().add(el);

                    } else if (block instanceof TableIR t) {
                        int rcount = (t.getRows() == null) ? 0 : t.getRows().size();
                        log.i("block#" + idx + ": table rows=" + rcount);

                        validateIrTableGeometryOrThrow(t, log);

                        GridHandle grid = buildGridFromTable(report, t, log);
                        if (grid != null) mgCell.getContent().add(grid);

                        dumpGrid(grid, debugDir.resolve("grid_dump.txt"));
                    } else {
                        log.i("block#" + idx + ": unknown " + (block == null ? "null" : block.getClass().getName()));
                    }
                }
            }

            report.getBody().add(masterGrid);

            tmpRpt = debugDir.resolve("generated.rptdesign");
            report.saveAs(tmpRpt.toAbsolutePath().toString());
            log.i("build:saved " + tmpRpt);

            String xml = Files.readString(tmpRpt, StandardCharsets.UTF_8);
            int end = xml.lastIndexOf("</report>");
            if (end < 0) throw new IllegalStateException("No </report> in rptdesign");
            String tail = xml.substring(end + "</report>".length()).trim();
            if (!tail.isEmpty()) {
                throw new IllegalStateException("Garbage after </report>: " + tail.substring(0, Math.min(tail.length(), 200)));
            }

            validateXmlRowCellCounts(tmpRpt, log);
            validateXmlGridEffectiveWidth(tmpRpt, log);
            selfParseValidate(session, tmpRpt, log);

            writeDebugLog(debugDir.resolve("debug.log"), dbg);

            try (InputStream in = Files.newInputStream(tmpRpt)) {
                in.transferTo(out);
                out.flush();
            }

            log.i("build:done");
        } catch (Exception e) {
            log.e("build:FAILED " + e.getClass().getName() + ": " + e.getMessage());
            try {
                if (debugDir != null) writeDebugLog(debugDir.resolve("debug.log"), dbg);
            } catch (Exception ignore) {}

            if (KEEP_DEBUG_ARTIFACTS && debugDir != null) {
                System.out.println("[TPLGEN][BIRT] DEBUG ARTIFACTS LEFT IN: " + debugDir.toAbsolutePath());
            }

            throw new TemplateProcessingException("Failed to build rptdesign via BIRT model API", e);
        } finally {
            if (!KEEP_DEBUG_ARTIFACTS && debugDir != null) {
                try { deleteDirRecursive(debugDir); } catch (Exception ignore) {}
            }
        }
    }

    private void ensureMasterPage(ReportDesignHandle report, DebugSink log) throws SemanticException {
        if (report.getMasterPages().getCount() > 0) {
            log.i("masterPage: exists count=" + report.getMasterPages().getCount());
            return;
        }

        log.i("masterPage: create (custom landscape 500mm x 210mm, margins=0)");

        SimpleMasterPageHandle mp = report.getElementFactory().newSimpleMasterPage("Simple MasterPage");

        mp.setProperty("type", "custom");
        mp.setProperty("orientation", "landscape");

        mp.setProperty("topMargin", "0mm");
        mp.setProperty("leftMargin", "0mm");
        mp.setProperty("bottomMargin", "0mm");
        mp.setProperty("rightMargin", "0mm");

        mp.setProperty("height", "210mm");
        mp.setProperty("width", "500mm");

        mp.setProperty("showHeaderOnFirst", "false");
        mp.setProperty("showFooterOnLast", "false");

        mp.setProperty("headerHeight", "0mm");
        mp.setProperty("footerHeight", "0mm");

        report.getMasterPages().add(mp);
    }

    private void declareParams(ReportDesignHandle report, TagRegistry tags, DebugSink log) throws SemanticException {
        if (tags == null || tags.isEmpty()) {
            log.i("params: none");
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

        log.i("params: added=" + added + " skipped=" + skipped + " totalNow=" + report.getParameters().getCount());
    }

    private DesignElementHandle buildTextData(ReportDesignHandle report, String srcText) throws SemanticException {
        String jsExpr = exprMapper.toHtmlValueExpr(srcText);

        TextDataHandle td = report.getElementFactory().newTextData(null);
        td.setProperty(TextDataHandle.CONTENT_TYPE_PROP, DesignChoiceConstants.TEXT_DATA_CONTENT_TYPE_PLAIN);
        td.setProperty(TextDataHandle.VALUE_EXPR_PROP, jsExpr);

        return td;
    }

    private GridHandle buildGridFromTable(ReportDesignHandle report, TableIR table, DebugSink log) throws SemanticException {
        if (table == null || table.getRows() == null || table.getRows().isEmpty()) {
            log.i("grid:skip empty table");
            return null;
        }

        var nt = TableIR.TableNormalizer.normalize(table);
        int rows = nt.rows();
        int cols = nt.cols();
        CellIR[][] m = nt.cells();

        log.i("grid:normalize rows=" + rows + " cols=" + cols);

        validateNormalizedMatrixOrThrow(m, rows, cols, log);

        GridHandle grid = report.getElementFactory().newGridItem(null, cols, rows);
        grid.setProperty(GridHandle.WIDTH_PROP, "100%");

        // колонки фиксируем (как у вас), но можно позже сделать по ODS-ширинам
        final String defaultColW = "16mm";
        for (int c = 0; c < cols; c++) {
            ColumnHandle ch = (ColumnHandle) grid.getColumns().get(c);
            ch.setProperty(ColumnHandle.WIDTH_PROP, defaultColW);
        }
        log.i("grid:columns width fixed: cols=" + cols + " each=" + defaultColW);

        int mastersWithSpan = 0;
        int coveredCleared = 0;

        int[][] masterRow = new int[rows][cols];
        int[][] masterCol = new int[rows][cols];
        for (int r = 0; r < rows; r++) {
            Arrays.fill(masterRow[r], -1);
            Arrays.fill(masterCol[r], -1);
        }

        // строим матрицу master-координат
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                CellIR cell = m[r][c];
                if (cell == null || cell.isCovered()) continue;

                int cs = Math.min(Math.max(cell.getColSpan(), 1), cols - c);
                int rs = Math.min(Math.max(cell.getRowSpan(), 1), rows - r);

                for (int rr = r; rr < r + rs; rr++) {
                    for (int cc = c; cc < c + cs; cc++) {
                        masterRow[rr][cc] = r;
                        masterCol[rr][cc] = c;
                    }
                }
            }
        }

        CellHandle[][] cellHandles = new CellHandle[rows][cols];

        for (int r = 0; r < rows; r++) {
            RowHandle gridRow = (RowHandle) grid.getRows().get(r);
            int birtCells = gridRow.getCells().getCount();
            if (birtCells != cols) {
                log.w("grid:WARN row=" + r + " birtCells=" + birtCells + " expectedCols=" + cols);
            }

            // 1) сначала "зарегистрируем" handles строки
            for (int c = 0; c < cols; c++) {
                cellHandles[r][c] = (CellHandle) gridRow.getCells().get(c);
            }

            // 2) затем заполним
            for (int c = 0; c < cols; c++) {
                CellHandle ghCell = cellHandles[r][c];
                CellIR cell = m[r][c];

                if (cell == null) {
                    log.w("grid:WARN cellIR is null at r=" + r + " c=" + c);
                    continue;
                }

                // --- КЛЮЧЕВО: covered -> markCoveredDrop ---
                if (cell.isCovered()) {
                    int mr = masterRow[r][c];
                    int mc = masterCol[r][c];

                    if (mr >= 0 && mc >= 0) {
                        CellHandle masterCell = cellHandles[mr][mc];
                        if (masterCell != null) {
                            markCoveredDrop(ghCell, masterCell, r, c, log);
                        } else {
                            markCoveredEmpty(ghCell, r, c, log);
                        }
                    } else {
                        markCoveredEmpty(ghCell, r, c, log);
                    }

                    coveredCleared++;
                    continue;
                }

                int rawCs = cell.getColSpan();
                int rawRs = cell.getRowSpan();
                int cs = Math.min(Math.max(rawCs, 1), cols - c);
                int rs = Math.min(Math.max(rawRs, 1), rows - r);

                // Проверка, что span не перекрывается с уже занятыми ячейками
                for (int checkC = c + 1; checkC < c + cs && checkC < cols; checkC++) {
                    if (m[r][checkC] != null && !m[r][checkC].isCovered()) {
                        log.w("grid:spanOverlap detected r=" + r + " c=" + c + " cs=" + cs + " overlapAt=" + checkC);
                        cs = checkC - c; // уменьшаем span до безопасного размера
                        break;
                    }
                }

                // Дополнительная проверка: ограничить span, чтобы не выходить за границы таблицы
                if (c + cs > cols) {
                    log.w("grid:spanExceedsCols r=" + r + " c=" + c + " cs=" + cs + " maxCols=" + cols);
                    cs = cols - c;
                }

                boolean hasSpan = (cs > 1 || rs > 1);

                if (hasSpan) {
                    mastersWithSpan++;
                    log.i("grid:masterSpan r=" + r + " c=" + c
                            + " cs=" + cs + " rs=" + rs
                            + " rawCs=" + rawCs + " rawRs=" + rawRs
                            + " textLen=" + safe(cell.getText()).length());
                }

                if (cs > 1) ghCell.setColumnSpan(cs);
                if (rs > 1) ghCell.setRowSpan(rs);

                applyCellStyle(ghCell, cell.getStyle());

                String txt = cell.getText();
                if (txt != null && !txt.isBlank()) {
                    ghCell.getContent().add(buildText(report, txt, cell.getStyle(), log));
                } else if (hasSpan) {
                    TextItemHandle t = report.getElementFactory().newTextItem(null);
                    t.setContentType(DesignChoiceConstants.TEXT_CONTENT_TYPE_PLAIN);
                    t.setContent("\u00A0");
                    ghCell.getContent().add(t);
                    log.i("grid:spanMasterEmptyContent added NBSP r=" + r + " c=" + c);
                }
            }
        }

        log.i("grid:stats mastersWithSpan=" + mastersWithSpan + " coveredCleared=" + coveredCleared);

        validateGridStructure(grid);
        validateGridEffectiveWidth(grid, rows, cols, log);

        return grid;
    }

    private static void markCoveredEmpty(CellHandle cell, int r, int c, DebugSink log) throws SemanticException {
        clearSlot(cell.getContent());

        cell.clearProperty(CellHandle.COL_SPAN_PROP);
        cell.clearProperty(CellHandle.ROW_SPAN_PROP);
        cell.clearProperty(CellHandle.DROP_PROP);

        // полная "чистка" стилей, чтобы Designer не пытался рисовать конфликтные стили
        cell.clearProperty(StyleHandle.BACKGROUND_COLOR_PROP);
        cell.clearProperty(StyleHandle.TEXT_ALIGN_PROP);
        cell.clearProperty(StyleHandle.COLOR_PROP);
        cell.clearProperty(StyleHandle.FONT_FAMILY_PROP);
        cell.clearProperty(StyleHandle.FONT_SIZE_PROP);
        cell.clearProperty(StyleHandle.FONT_STYLE_PROP);
        cell.clearProperty(StyleHandle.FONT_WEIGHT_PROP);
        cell.clearProperty(StyleHandle.TEXT_INDENT_PROP);

        cell.clearProperty(StyleHandle.BORDER_TOP_COLOR_PROP);
        cell.clearProperty(StyleHandle.BORDER_BOTTOM_COLOR_PROP);
        cell.clearProperty(StyleHandle.BORDER_LEFT_COLOR_PROP);
        cell.clearProperty(StyleHandle.BORDER_RIGHT_COLOR_PROP);

        cell.clearProperty(StyleHandle.BORDER_TOP_STYLE_PROP);
        cell.clearProperty(StyleHandle.BORDER_BOTTOM_STYLE_PROP);
        cell.clearProperty(StyleHandle.BORDER_LEFT_STYLE_PROP);
        cell.clearProperty(StyleHandle.BORDER_RIGHT_STYLE_PROP);

        cell.clearProperty(StyleHandle.BORDER_TOP_WIDTH_PROP);
        cell.clearProperty(StyleHandle.BORDER_BOTTOM_WIDTH_PROP);
        cell.clearProperty(StyleHandle.BORDER_LEFT_WIDTH_PROP);
        cell.clearProperty(StyleHandle.BORDER_RIGHT_WIDTH_PROP);

        cell.clearProperty(StyleHandle.MARGIN_TOP_PROP);
        cell.clearProperty(StyleHandle.MARGIN_BOTTOM_PROP);
        cell.clearProperty(StyleHandle.MARGIN_LEFT_PROP);
        cell.clearProperty(StyleHandle.MARGIN_RIGHT_PROP);

        cell.clearProperty(StyleHandle.PADDING_TOP_PROP);
        cell.clearProperty(StyleHandle.PADDING_BOTTOM_PROP);
        cell.clearProperty(StyleHandle.PADDING_LEFT_PROP);
        cell.clearProperty(StyleHandle.PADDING_RIGHT_PROP);

        log.i("covered cell cleared (no master) r=" + r + " c=" + c);
    }

    private static void markCoveredDrop(CellHandle cell, CellHandle masterCell, int r, int c, DebugSink log) throws SemanticException {
        clearSlot(cell.getContent());

        cell.clearProperty(CellHandle.COL_SPAN_PROP);
        cell.clearProperty(CellHandle.ROW_SPAN_PROP);

        // Не задаем drop для covered-ячейки, чтобы избежать corrupted в BIRT Designer
        cell.clearProperty(CellHandle.DROP_PROP);


        // полная "чистка" стилей у covered (они "живут" на мастере)
        cell.clearProperty(StyleHandle.BACKGROUND_COLOR_PROP);
        cell.clearProperty(StyleHandle.TEXT_ALIGN_PROP);
        cell.clearProperty(StyleHandle.COLOR_PROP);
        cell.clearProperty(StyleHandle.FONT_FAMILY_PROP);
        cell.clearProperty(StyleHandle.FONT_SIZE_PROP);
        cell.clearProperty(StyleHandle.FONT_STYLE_PROP);
        cell.clearProperty(StyleHandle.FONT_WEIGHT_PROP);
        cell.clearProperty(StyleHandle.TEXT_INDENT_PROP);

        cell.clearProperty(StyleHandle.BORDER_TOP_COLOR_PROP);
        cell.clearProperty(StyleHandle.BORDER_BOTTOM_COLOR_PROP);
        cell.clearProperty(StyleHandle.BORDER_LEFT_COLOR_PROP);
        cell.clearProperty(StyleHandle.BORDER_RIGHT_COLOR_PROP);

        cell.clearProperty(StyleHandle.BORDER_TOP_STYLE_PROP);
        cell.clearProperty(StyleHandle.BORDER_BOTTOM_STYLE_PROP);
        cell.clearProperty(StyleHandle.BORDER_LEFT_STYLE_PROP);
        cell.clearProperty(StyleHandle.BORDER_RIGHT_STYLE_PROP);

        cell.clearProperty(StyleHandle.BORDER_TOP_WIDTH_PROP);
        cell.clearProperty(StyleHandle.BORDER_BOTTOM_WIDTH_PROP);
        cell.clearProperty(StyleHandle.BORDER_LEFT_WIDTH_PROP);
        cell.clearProperty(StyleHandle.BORDER_RIGHT_WIDTH_PROP);

        cell.clearProperty(StyleHandle.MARGIN_TOP_PROP);
        cell.clearProperty(StyleHandle.MARGIN_BOTTOM_PROP);
        cell.clearProperty(StyleHandle.MARGIN_LEFT_PROP);
        cell.clearProperty(StyleHandle.MARGIN_RIGHT_PROP);

        cell.clearProperty(StyleHandle.PADDING_TOP_PROP);
        cell.clearProperty(StyleHandle.PADDING_BOTTOM_PROP);
        cell.clearProperty(StyleHandle.PADDING_LEFT_PROP);
        cell.clearProperty(StyleHandle.PADDING_RIGHT_PROP);

        log.i("covered cell cleared r=" + r + " c=" + c + " drop=none");
    }

    private static void validateGridStructure(GridHandle grid) throws SemanticException {
        int cols = grid.getColumns().getCount();
        int rows = grid.getRows().getCount();

        int problems = 0;
        System.out.println("[TPLGEN][BIRT] grid:validateStructure start rows=" + rows + " cols=" + cols);

        for (int r = 0; r < rows; r++) {
            RowHandle row = (RowHandle) grid.getRows().get(r);
            int cellCount = row.getCells().getCount();
            if (cellCount != cols) {
                problems++;
                System.out.println("[TPLGEN][BIRT] grid:STRUCT PROBLEM row=" + r + " cellCount=" + cellCount + " expected=" + cols);
                break;
            }
        }

        System.out.println("[TPLGEN][BIRT] grid:validateStructure done problems=" + problems);
    }

    private void selfParseValidate(SessionHandle session, Path rpt, DebugSink log) {
        try {
            log.i("selfParse:openDesign start");
            ReportDesignHandle reopened = session.openDesign(rpt.toAbsolutePath().toString());
            log.i("selfParse:openDesign ok bodyCount=" + reopened.getBody().getCount());
        } catch (Exception ex) {
            log.e("selfParse:FAILED " + ex.getClass().getName() + ": " + ex.getMessage());
            throw new TemplateProcessingException("Generated rptdesign cannot be reopened by BIRT model API (corrupted design)", ex);
        }
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

    private DesignElementHandle buildText(ReportDesignHandle report, String srcText, StyleIR style, DebugSink log) throws SemanticException {
        srcText = (srcText == null) ? "" : srcText;

        if (!exprMapper.containsTagsOrParams(srcText)) {
            TextItemHandle t = report.getElementFactory().newTextItem(null);
            t.setContentType(DesignChoiceConstants.TEXT_CONTENT_TYPE_PLAIN);
            t.setContent(srcText);
            applyTextStyle(t, style);
            return t;
        }

        String jsExpr = exprMapper.toHtmlValueExpr(srcText);

        TextDataHandle td = report.getElementFactory().newTextData(null);
        td.setProperty(TextDataHandle.CONTENT_TYPE_PROP, DesignChoiceConstants.TEXT_DATA_CONTENT_TYPE_PLAIN);
        td.setProperty(TextDataHandle.VALUE_EXPR_PROP, jsExpr);

        applyTextStyle(td, style);
        log.i("cellText expr=" + jsExpr);

        return td;
    }

    private static void validateIrTableGeometryOrThrow(TableIR t, DebugSink log) {
        if (t == null || t.getRows() == null) return;
        int rows = t.getRows().size();
        int cols = rows == 0 ? 0 : (t.getRows().get(0).getCells() == null ? 0 : t.getRows().get(0).getCells().size());
        log.i("ir:table raw rows=" + rows + " cols=" + cols);
    }

    private static void validateNormalizedMatrixOrThrow(CellIR[][] m, int rows, int cols, DebugSink log) {
        int[][] occ = new int[rows][cols];
        for (int r = 0; r < rows; r++) Arrays.fill(occ[r], -1);

        int masterId = 0;
        int overlaps = 0;
        int badCovered = 0;
        int outOfBounds = 0;

        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                CellIR cell = m[r][c];
                if (cell == null) continue;
                if (cell.isCovered()) continue;

                int cs = Math.max(cell.getColSpan(), 1);
                int rs = Math.max(cell.getRowSpan(), 1);

                int id = masterId++;
                for (int rr = r; rr < r + rs; rr++) {
                    for (int cc = c; cc < c + cs; cc++) {
                        if (rr >= rows || cc >= cols) {
                            outOfBounds++;
                            continue;
                        }
                        if (occ[rr][cc] != -1) overlaps++;
                        occ[rr][cc] = id;
                    }
                }
            }
        }

        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                CellIR cell = m[r][c];
                if (cell == null) continue;

                boolean covered = cell.isCovered();
                boolean occupied = (occ[r][c] != -1);

                if (covered && !occupied) badCovered++;
            }
        }

        StringBuilder map = new StringBuilder(rows * cols * 4);
        for (int r = 0; r < rows; r++) {
            map.append(String.format("R%03d: ", r));
            for (int c = 0; c < cols; c++) {
                int v = occ[r][c];
                map.append(v == -1 ? " ." : " #");
            }
            map.append("\n");
        }
        log.i("matrix:occMap\n" + map);

        if (outOfBounds > 0 || overlaps > 0 || badCovered > 0) {
            log.e("matrix:GEOMETRY FAIL outOfBounds=" + outOfBounds + " overlaps=" + overlaps + " badCovered=" + badCovered);
            throw new IllegalStateException("Normalized table geometry is invalid. outOfBounds=" + outOfBounds
                    + " overlaps=" + overlaps + " badCovered=" + badCovered);
        }

        log.i("matrix:geometry ok");
    }

    private void validateXmlRowCellCounts(Path rpt, DebugSink log) {
        try {
            var dbf = javax.xml.parsers.DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(true);

            var db = dbf.newDocumentBuilder();
            org.w3c.dom.Document doc;
            try (InputStream in = Files.newInputStream(rpt)) {
                doc = db.parse(in);
            }

            final String NS = "http://www.eclipse.org/birt/2005/design";

            org.w3c.dom.NodeList grids = doc.getElementsByTagNameNS(NS, "grid");
            log.i("xmlRowCellCount: grids total=" + grids.getLength());

            for (int gi = 0; gi < grids.getLength(); gi++) {
                org.w3c.dom.Element gridEl = (org.w3c.dom.Element) grids.item(gi);

                String gridId = gridEl.getAttribute("id");
                String gridName = gridEl.getAttribute("name");

                int cols = countDirectChildElements(gridEl, NS, "column");
                log.i("xmlRowCellCount: grid#" + (gi + 1) + " id=" + gridId
                        + " name=" + (gridName == null || gridName.isBlank() ? "-" : gridName)
                        + " cols=" + cols);

                org.w3c.dom.NodeList children = gridEl.getChildNodes();
                for (int i = 0; i < children.getLength(); i++) {
                    org.w3c.dom.Node n = children.item(i);
                    if (n.getNodeType() != org.w3c.dom.Node.ELEMENT_NODE) continue;

                    org.w3c.dom.Element el = (org.w3c.dom.Element) n;
                    if (!NS.equals(el.getNamespaceURI())) continue;
                    if (!"row".equals(el.getLocalName())) continue;

                    String rowId = el.getAttribute("id");

                    int cellCount = countDirectChildElements(el, NS, "cell");
                    if (cellCount != cols) {
                        log.e("xmlRowCellCount: PROBLEM gridId=" + gridId + " rowId=" + rowId
                                + " cells=" + cellCount + " expected=" + cols);
                        log.e("xmlRowCellCount: rowSnippet=" + shrinkSpaces(serializeElement(el), 800));
                        return;
                    }
                }
            }

        } catch (Exception e) {
            log.e("xmlRowCellCount: FAILED " + e.getClass().getName() + ": " + e.getMessage());
        }
    }

    private static int countDirectChildElements(org.w3c.dom.Element parent, String ns, String localName) {
        int cnt = 0;
        org.w3c.dom.NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            org.w3c.dom.Node n = children.item(i);
            if (n.getNodeType() != org.w3c.dom.Node.ELEMENT_NODE) continue;
            org.w3c.dom.Element el = (org.w3c.dom.Element) n;
            if (!ns.equals(el.getNamespaceURI())) continue;
            if (localName.equals(el.getLocalName())) cnt++;
        }
        return cnt;
    }

    private static String serializeElement(org.w3c.dom.Element el) {
        try {
            var tf = javax.xml.transform.TransformerFactory.newInstance();
            var t = tf.newTransformer();
            t.setOutputProperty(javax.xml.transform.OutputKeys.OMIT_XML_DECLARATION, "yes");
            t.setOutputProperty(javax.xml.transform.OutputKeys.INDENT, "no");

            var sw = new java.io.StringWriter();
            t.transform(new javax.xml.transform.dom.DOMSource(el), new javax.xml.transform.stream.StreamResult(sw));
            return sw.toString();
        } catch (Exception ex) {
            return "<serialize failed: " + ex.getMessage() + ">";
        }
    }

    private void validateXmlGridEffectiveWidth(Path rpt, DebugSink log) {
        // после DROP эта проверка становится осмысленной, но сейчас оставим выключенной
        log.i("xmlValidateEff: skipped");
    }

    private static void clearSlot(SlotHandle slot) throws SemanticException {
        while (slot.getCount() > 0) slot.drop(0);
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

        if (st.isBold()) textEl.setProperty(StyleHandle.FONT_WEIGHT_PROP, DesignChoiceConstants.FONT_WEIGHT_BOLD);
        if (st.isItalic()) textEl.setProperty(StyleHandle.FONT_STYLE_PROP, DesignChoiceConstants.FONT_STYLE_ITALIC);
        if (st.getFontSizePt() != null && st.getFontSizePt() > 0) {
            textEl.setProperty(StyleHandle.FONT_SIZE_PROP, st.getFontSizePt() + "pt");
        }
    }

    private void validateGridEffectiveWidth(GridHandle grid, int rows, int cols, DebugSink log) {
        // после DROP проблем с "effective width" в Designer обычно нет
        log.i("grid:validateEffectiveWidth skipped");
    }

    private static String shrinkSpaces(String s, int max) {
        if (s == null) return "";
        String t = s.replace("\r", "").replace("\n", " ").replaceAll("\\s+", " ").trim();
        return t.length() <= max ? t : t.substring(0, max) + "...";
    }

    private static void writeDebugLog(Path file, StringBuilder dbg) throws IOException {
        String header = "=== TPLGEN DEBUG ===\n" + LocalDateTime.now() + "\n\n";
        Files.writeString(file, header + dbg, StandardCharsets.UTF_8);
    }

    private static void deleteDirRecursive(Path dir) throws IOException {
        if (dir == null) return;
        if (!Files.exists(dir)) return;
        try (var s = Files.walk(dir)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (Exception ignore) {}
            });
        }
    }

    private static String safe(String s) { return s == null ? "" : s; }

    private static final class DebugSink {
        private final StringBuilder b;
        DebugSink(StringBuilder b) { this.b = b; }

        void i(String s) { write("INFO", s); System.out.println("[TPLGEN][BIRT] " + s); }
        void w(String s) { write("WARN", s); System.out.println("[TPLGEN][BIRT] WARN " + s); }
        void e(String s) { write("ERR ", s); System.out.println("[TPLGEN][BIRT] ERROR " + s); }

        private void write(String lvl, String s) {
            b.append("[").append(lvl).append("] ").append(s).append("\n");
        }
    }

    private static void dumpGrid(GridHandle grid, Path file) {
        try {
            if (grid == null) return;

            StringBuilder sb = new StringBuilder(200_000);

            int cols = grid.getColumns().getCount();
            int rows = grid.getRows().getCount();
            sb.append("GRID DUMP\n");
            sb.append("id=").append(grid.getID()).append(" name=").append(grid.getName()).append("\n");
            sb.append("rows=").append(rows).append(" cols=").append(cols).append("\n\n");

            for (int r = 0; r < rows; r++) {
                RowHandle row = (RowHandle) grid.getRows().get(r);
                sb.append("ROW r=").append(r).append(" rowId=").append(row.getID()).append("\n");

                for (int c = 0; c < cols; c++) {
                    CellHandle cell = (CellHandle) row.getCells().get(c);
                    Integer cs = (Integer) cell.getProperty(CellHandle.COL_SPAN_PROP);
                    Integer rs = (Integer) cell.getProperty(CellHandle.ROW_SPAN_PROP);
                    Object drop = cell.getProperty(CellHandle.DROP_PROP);

                    sb.append("  CELL r=").append(r).append(" c=").append(c)
                            .append(" id=").append(cell.getID())
                            .append(" colSpan=").append(cs)
                            .append(" rowSpan=").append(rs)
                            .append(" drop=").append(drop)
                            .append(" contentCount=").append(cell.getContent().getCount())
                            .append("\n");
                }
                sb.append("\n");
            }

            Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }
}
