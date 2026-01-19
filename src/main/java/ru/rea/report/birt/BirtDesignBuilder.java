package ru.rea.report.birt;

import com.ibm.icu.util.ULocale;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.util.Supplier;
import org.eclipse.birt.report.model.api.*;
import org.eclipse.birt.report.model.api.activity.SemanticException;
import org.eclipse.birt.report.model.api.core.IModuleModel;
import org.eclipse.birt.report.model.api.elements.DesignChoiceConstants;
import org.eclipse.birt.report.model.api.elements.structures.EmbeddedImage;
import org.springframework.stereotype.Component;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import ru.rea.report.exception.TemplateProcessingException;
import ru.rea.report.ir.*;
import ru.rea.report.tags.TagRegistry;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.*;
import java.lang.reflect.Method;
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
    private static final String DROP_ALL = DesignChoiceConstants.DROP_TYPE_ALL;

    public void build(TemplateDocumentIR ir, TagRegistry tags, OutputStream out) {
        build(ir, tags, null, out);
    }

    public void build(TemplateDocumentIR ir, TagRegistry tags, Path optionalPngPath, OutputStream out) {
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
            report.setProperty("layoutPreference", "fixed layout");
            report.setProperty("imageDPI", "96");

            ensureMasterPage(report, ir, log);
            logMasterPage(report, log);

            declareParams(report, tags, log);

            GridHandle masterGrid = report.getElementFactory().newGridItem("MASTER_GRID", 1, 1);
            logGridGeometry(masterGrid, "MASTER_GRID", log);
            masterGrid.setProperty(GridHandle.WIDTH_PROP, "100%");
            ColumnHandle mgCol = (ColumnHandle) masterGrid.getColumns().get(0);


            RowHandle mgRow = (RowHandle) masterGrid.getRows().get(0);
            CellHandle mgCell = (CellHandle) mgRow.getCells().get(0);

            int blocks = (ir == null || ir.getBlocks() == null) ? 0 : ir.getBlocks().size();
            log.i("blocks=" + blocks);

            if (ir != null && ir.getBlocks() != null) {
                int idx = 0;

                List<ParagraphIR> paraBuf = new ArrayList<>();

                for (BlockIR block : ir.getBlocks()) {
                    idx++;

                    if (block instanceof ParagraphIR p) {
                        paraBuf.add(p);
                        continue;
                    }

                    if (!paraBuf.isEmpty()) {
                        GridHandle pg = buildParagraphsGrid(report, paraBuf, log);
                        if (pg != null) mgCell.getContent().add(pg);
                        paraBuf.clear();
                    }

                    if (block instanceof ImageIR img) {
                        GridHandle ig = buildImageGrid(report, img, log);
                        if (ig != null) mgCell.getContent().add(ig);
                        continue;
                    }

                    if (block instanceof TableIR t) {
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

                if (!paraBuf.isEmpty()) {
                    GridHandle pg = buildParagraphsGrid(report, paraBuf, log);
                    if (pg != null) mgCell.getContent().add(pg);
                    paraBuf.clear();
                }
            }

            report.getBody().add(masterGrid);

            applyOptionalPng(report, optionalPngPath, log);
            logAllImages(report, log);

            tmpRpt = debugDir.resolve("generated.rptdesign");
            report.saveAs(tmpRpt.toAbsolutePath().toString());
            logXmlImageGeometry(tmpRpt, EMBEDDED_LOGO_NAME, log);
            log.i("build:saved " + tmpRpt);

            RptDesignSanitizer.Options opt = new RptDesignSanitizer.Options();
            opt.forceDropAllForCoveredCells = true;
            opt.fixRowCellCount = true;
            opt.compactRowsRemoveCoveredCells = true;
            log.i("logo:dataHead(beforeSan)=" + readEmbeddedImageDataHead(tmpRpt, "logo.png"));
            RptDesignSanitizer.sanitize(tmpRpt, opt);

            logXmlImageGeometry(tmpRpt, EMBEDDED_LOGO_NAME, log);
            log.i("logo:dataHead(afterSan)=" + readEmbeddedImageDataHead(tmpRpt, "logo.png"));
            log.i("build:sanitized rptdesign dropCovered=" + opt.forceDropAllForCoveredCells + " ver=" + opt.forceReportVersion);

            String xml = Files.readString(tmpRpt, StandardCharsets.UTF_8);
            int end = xml.lastIndexOf("</report>");
            if (end < 0) throw new IllegalStateException("No </report> in rptdesign");
            String tail = xml.substring(end + "</report>".length()).trim();
            if (!tail.isEmpty()) {
                throw new IllegalStateException("Garbage after </report>: " + tail.substring(0, Math.min(tail.length(), 200)));
            }

            validateXmlRowCellCounts(tmpRpt, log);
            validateXmlDropSemantics(tmpRpt, log);
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

    private void validateXmlDropSemantics(Path rpt, DebugSink log) {
        try {
            var dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(true);

            var db = dbf.newDocumentBuilder();
            org.w3c.dom.Document doc;
            try (InputStream in = Files.newInputStream(rpt)) {
                doc = db.parse(in);
            }

            final String NS = "http://www.eclipse.org/birt/2005/design";

            org.w3c.dom.NodeList grids = doc.getElementsByTagNameNS(NS, "grid");
            for (int gi = 0; gi < grids.getLength(); gi++) {
                org.w3c.dom.Element gridEl = (org.w3c.dom.Element) grids.item(gi);

                int cols = countDirectChildElements(gridEl, NS, "column");
                if (cols <= 0) continue;

                int[] carry = new int[cols];

                org.w3c.dom.NodeList children = gridEl.getChildNodes();
                for (int i = 0; i < children.getLength(); i++) {
                    org.w3c.dom.Node n = children.item(i);
                    if (n.getNodeType() != org.w3c.dom.Node.ELEMENT_NODE) continue;
                    org.w3c.dom.Element el = (org.w3c.dom.Element) n;
                    if (!NS.equals(el.getNamespaceURI())) continue;
                    if (!"row".equals(el.getLocalName())) continue;

                    List<Element> cells = new ArrayList<>();
                    org.w3c.dom.NodeList rowKids = el.getChildNodes();
                    for (int j = 0; j < rowKids.getLength(); j++) {
                        org.w3c.dom.Node rc = rowKids.item(j);
                        if (rc.getNodeType() != org.w3c.dom.Node.ELEMENT_NODE) continue;
                        org.w3c.dom.Element cel = (org.w3c.dom.Element) rc;
                        if (NS.equals(cel.getNamespaceURI()) && "cell".equals(cel.getLocalName())) {
                            cells.add(cel);
                        }
                    }

                    int col = 0;
                    for (int ci = 0; ci < cells.size(); ci++, col++) {
                        org.w3c.dom.Element cellEl = cells.get(ci);
                        boolean isDrop = hasProperty(cellEl, NS, "drop", "all");
                        int cs = getIntProperty(cellEl, NS, "colSpan", 1);
                        int rs = getIntProperty(cellEl, NS, "rowSpan", 1);

                        if (col < carry.length && carry[col] > 0 && !isDrop) {
                            log.e("xmlDropSem: EXPECT DROP due to rowSpan at col=" + col + " rowId=" + el.getAttribute("id"));
                            return;
                        }

                        if (isDrop) {
                            if (cs > 1 || rs > 1) {
                                log.e("xmlDropSem: DROP cell must not have spans rowId=" + el.getAttribute("id")
                                        + " cellId=" + cellEl.getAttribute("id"));
                                return;
                            }
                            if (hasAnyContent(cellEl, NS)) {
                                log.e("xmlDropSem: DROP cell must be empty rowId=" + el.getAttribute("id")
                                        + " cellId=" + cellEl.getAttribute("id"));
                                return;
                            }
                        }

                        for (int k = 1; k < cs; k++) {
                            int nxt = ci + k;
                            if (nxt >= cells.size()) break;
                            org.w3c.dom.Element nextEl = cells.get(nxt);
                            boolean nextDrop = hasProperty(nextEl, NS, "drop", "all");
                            if (!nextDrop) {
                                log.e("xmlDropSem: EXPECT DROP after colSpan rowId=" + el.getAttribute("id")
                                        + " masterCellId=" + cellEl.getAttribute("id") + " at offset=" + k);
                                return;
                            }
                        }

                        if (rs > 1) {
                            for (int k = 0; k < cs && (col + k) < carry.length; k++) {
                                carry[col + k] = Math.max(carry[col + k], rs - 1);
                            }
                        }
                    }

                    for (int k = 0; k < carry.length; k++) if (carry[k] > 0) carry[k]--;
                }
            }
        } catch (Exception e) {
            log.e("xmlDropSem: FAILED " + e.getClass().getName() + ": " + e.getMessage());
        }
    }

    private static boolean hasAnyContent(org.w3c.dom.Element cellEl, String ns) {
        org.w3c.dom.NodeList kids = cellEl.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            org.w3c.dom.Node n = kids.item(i);
            if (n.getNodeType() != org.w3c.dom.Node.ELEMENT_NODE) continue;
            org.w3c.dom.Element el = (org.w3c.dom.Element) n;
            if (!ns.equals(el.getNamespaceURI())) continue;
            String ln = el.getLocalName();
            if ("text".equals(ln) || "text-data".equals(ln) || "grid".equals(ln) || "image".equals(ln)) return true;
        }
        return false;
    }

    private static boolean hasProperty(org.w3c.dom.Element parent, String ns, String name, String expectValue) {
        org.w3c.dom.NodeList kids = parent.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            org.w3c.dom.Node n = kids.item(i);
            if (n.getNodeType() != org.w3c.dom.Node.ELEMENT_NODE) continue;
            org.w3c.dom.Element el = (org.w3c.dom.Element) n;
            if (!ns.equals(el.getNamespaceURI())) continue;
            if (!"property".equals(el.getLocalName())) continue;
            if (!name.equals(el.getAttribute("name"))) continue;
            String v = el.getTextContent();
            return expectValue.equals(v);
        }
        return false;
    }

    private static int getIntProperty(org.w3c.dom.Element parent, String ns, String name, int def) {
        org.w3c.dom.NodeList kids = parent.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            org.w3c.dom.Node n = kids.item(i);
            if (n.getNodeType() != org.w3c.dom.Node.ELEMENT_NODE) continue;
            org.w3c.dom.Element el = (org.w3c.dom.Element) n;
            if (!ns.equals(el.getNamespaceURI())) continue;
            if (!"property".equals(el.getLocalName())) continue;
            if (!name.equals(el.getAttribute("name"))) continue;
            try {
                return Integer.parseInt(el.getTextContent().trim());
            } catch (Exception ignore) {
                return def;
            }
        }
        return def;
    }

    private void ensureMasterPage(ReportDesignHandle report, TemplateDocumentIR ir, DebugSink log) throws SemanticException {
        if (report.getMasterPages().getCount() > 0) {
            log.i("masterPage: exists count=" + report.getMasterPages().getCount());
            return;
        }

        float wMm = 500f;
        float hMm = 210f;
        float mtMm = 0f, mlMm = 0f, mbMm = 0f, mrMm = 0f;
        String orientation = "landscape";

        if (ir != null && ir.getPage() != null) {
            PageIR p = ir.getPage();

            if (p.getWidthMm() != null && p.getWidthMm() > 0) wMm = p.getWidthMm();
            if (p.getHeightMm() != null && p.getHeightMm() > 0) hMm = p.getHeightMm();

            if (p.getMarginTopMm() != null && p.getMarginTopMm() >= 0) mtMm = p.getMarginTopMm();
            if (p.getMarginLeftMm() != null && p.getMarginLeftMm() >= 0) mlMm = p.getMarginLeftMm();
            if (p.getMarginBottomMm() != null && p.getMarginBottomMm() >= 0) mbMm = p.getMarginBottomMm();
            if (p.getMarginRightMm() != null && p.getMarginRightMm() >= 0) mrMm = p.getMarginRightMm();

            String ori = p.getOrientation();
            if (ori != null) {
                ori = ori.trim().toLowerCase(Locale.ROOT);
                if ("portrait".equals(ori) || "landscape".equals(ori)) {
                    orientation = ori;
                } else {
                    orientation = (wMm > hMm) ? "landscape" : "portrait";
                }
            } else {
                orientation = (wMm > hMm) ? "landscape" : "portrait";
            }
        }

        if ("portrait".equals(orientation) && wMm > hMm) {
            float t = wMm; wMm = hMm; hMm = t;
        } else if ("landscape".equals(orientation) && wMm < hMm) {
            float t = wMm; wMm = hMm; hMm = t;
        }

        String width = fmtMm(wMm);
        String height = fmtMm(hMm);

        String top = fmtMm(mtMm);
        String left = fmtMm(mlMm);
        String bottom = fmtMm(mbMm);
        String right = fmtMm(mrMm);

        log.i("masterPage: create custom " + orientation + " " + width + " x " + height
                + " margins t=" + top + " l=" + left + " b=" + bottom + " r=" + right);

        SimpleMasterPageHandle mp = report.getElementFactory().newSimpleMasterPage("Simple MasterPage");

        mp.setProperty("type", "custom");
        mp.setProperty("orientation", orientation);

        mp.setProperty("topMargin", top);
        mp.setProperty("leftMargin", left);
        mp.setProperty("bottomMargin", bottom);
        mp.setProperty("rightMargin", right);

        mp.setProperty("height", height);
        mp.setProperty("width", width);

        mp.setProperty("showHeaderOnFirst", "false");
        mp.setProperty("showFooterOnLast", "false");
        mp.setProperty("headerHeight", "0mm");
        mp.setProperty("footerHeight", "0mm");

        report.getMasterPages().add(mp);
    }

    private static String fmtMm(float mm) {
        float v = Math.round(mm * 10f) / 10f;
        if (v < 0f) v = 0f;
        if (Math.abs(v - Math.round(v)) < 0.0001f) {
            return Math.round(v) + "mm";
        }
        return v + "mm";
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

            String dt = mapParamType(tags.paramTypeOf(paramName));
            if (dt == null) dt = inferParamType(paramName);

            p.setDataType(dt);
            p.setProperty(ScalarParameterHandle.PROMPT_TEXT_PROP, paramName);
            p.setIsRequired(false);
            report.getParameters().add(p);

            added++;
        }

        log.i("params: added=" + added + " skipped=" + skipped + " totalNow=" + report.getParameters().getCount());
    }

    private static String mapParamType(TagRegistry.ParamType t) {
        if (t == null) return null;

        return switch (t) {
            case STRING -> DesignChoiceConstants.PARAM_TYPE_STRING;
            case INTEGER -> DesignChoiceConstants.PARAM_TYPE_INTEGER;
            case DECIMAL -> DesignChoiceConstants.PARAM_TYPE_DECIMAL;
            case BOOLEAN -> DesignChoiceConstants.PARAM_TYPE_BOOLEAN;
            case DATE -> DesignChoiceConstants.PARAM_TYPE_DATE;
            case DATE_TIME -> DesignChoiceConstants.PARAM_TYPE_DATETIME;
        };
    }


    private static String inferParamType(String name) {
        if (name == null) return DesignChoiceConstants.PARAM_TYPE_STRING;

        String n = name.trim().toLowerCase(Locale.ROOT);
        if (n.isEmpty()) return DesignChoiceConstants.PARAM_TYPE_STRING;

        if (n.startsWith("is_") || n.startsWith("has_") || n.startsWith("flag_") || n.endsWith("_flag")) {
            return DesignChoiceConstants.PARAM_TYPE_BOOLEAN;
        }

        if (n.endsWith("_date") || n.contains("date_") || n.endsWith("date")) {
            return DesignChoiceConstants.PARAM_TYPE_DATE;
        }
        if (n.endsWith("_datetime") || n.contains("datetime") || n.endsWith("_time") || n.contains("time_")) {
            return DesignChoiceConstants.PARAM_TYPE_DATETIME;
        }

        if (n.equals("year") || n.endsWith("_year") || n.contains("year_")) {
            return DesignChoiceConstants.PARAM_TYPE_INTEGER;
        }
        if (n.contains("count") || n.contains("qty") || n.contains("num") || n.contains("employees") || n.contains("workers")) {
            return DesignChoiceConstants.PARAM_TYPE_INTEGER;
        }

        if (n.contains("amount") || n.startsWith("summ_") || n.contains("_sum") || n.contains("sum_")
                || n.contains("percent") || n.contains("ratio") || n.contains("total") || n.contains("value")) {
            return DesignChoiceConstants.PARAM_TYPE_DECIMAL;
        }

        return DesignChoiceConstants.PARAM_TYPE_STRING;
    }
    private GridHandle buildGridFromTable(ReportDesignHandle report, TableIR table, DebugSink log) throws SemanticException {
        if (table == null || table.getRows() == null || table.getRows().isEmpty()) {
            log.i("grid:skip empty table");
            return null;
        }

        TableIR.TableNormalizer.NormalizedTable nt = TableIR.TableNormalizer.normalize(table);
        int rows = nt.rows();
        int cols = nt.cols();
        CellIR[][] m = nt.cells();

        log.i("grid:normalize rows=" + rows + " cols=" + cols);
        validateNormalizedMatrixOrThrow(m, rows, cols, log);

        GridHandle grid = report.getElementFactory().newGridItem(null, cols, rows);
        float contentWidthMm = getContentWidthMm(report);

        List<Float> colMm = normalizeColWidthsMm(table.getColWidthsMm(), cols);

        if (contentWidthMm > 0.1f && colMm != null && sumPositive(colMm) > 0.1f) {
            log.i("grid:widthMode=" + ((contentWidthMm > 0.1f && colMm != null && sumPositive(colMm) > 0.1f) ? "MM" : "PCT"));

            grid.setProperty(GridHandle.WIDTH_PROP, fmtMm(contentWidthMm));

            float sum = sumPositive(colMm);
            float k = contentWidthMm / sum;
            log.i("grid:table fromOds=" + table.isFromOds()
                    + " contentWidthMm=" + contentWidthMm
                    + " rawColMm=" + (table.getColWidthsMm() == null ? "null" : table.getColWidthsMm().size())
                    + " normColMm=" + (colMm == null ? "null" : colMm.size())
                    + " sumColMm=" + sumPositive(colMm));
            if (colMm != null) {
                log.i("grid:colMm=" + colMm);
            }


            for (int c = 0; c < cols; c++) {
                float src = Math.max(0f, colMm.get(c) == null ? 0f : colMm.get(c));
                float wmm = Math.max(1f, src * k);
                ColumnHandle colH = (ColumnHandle) grid.getColumns().get(c);
                log.i("grid:col[" + c + "] widthProp=" + colH.getProperty(ColumnHandle.WIDTH_PROP));
                colH.setProperty(ColumnHandle.WIDTH_PROP, fmtMm(wmm));
            }
        } else {
            grid.setProperty(GridHandle.WIDTH_PROP, "100%");
            double colPct = 100.0 / cols;
            for (int c = 0; c < cols; c++) {
                ColumnHandle colH = (ColumnHandle) grid.getColumns().get(c);
                log.i("grid:col[" + c + "] widthProp=" + colH.getProperty(ColumnHandle.WIDTH_PROP));
                colH.setProperty(ColumnHandle.WIDTH_PROP, String.format(Locale.ROOT, "%.4f%%", colPct));
            }
        }




        CellHandle[][] cellHandles = new CellHandle[rows][cols];
        for (int r = 0; r < rows; r++) {
            RowHandle rowH = (RowHandle) grid.getRows().get(r);
            for (int c = 0; c < cols; c++) {
                cellHandles[r][c] = (CellHandle) rowH.getCells().get(c);
            }
        }

        boolean[][] absorbed = new boolean[rows][cols];

        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                if (absorbed[r][c]) continue;

                CellIR cell = m[r][c];
                if (cell == null) continue;

                if (cell.isCovered()) continue;

                markMasterNotDropped(cellHandles[r][c]);

                int rawCs = Math.max(cell.getColSpan(), 1);
                int rawRs = Math.max(cell.getRowSpan(), 1);

                int cs = Math.min(rawCs, cols - c);
                int rs = Math.min(rawRs, rows - r);

                if (!table.isFromOds() && rs == 1 && cs >= 1) {
                    while (true) {
                        int nextCol = c + cs;
                        if (nextCol >= cols) break;

                        if (absorbed[r][nextCol]) break;

                        CellIR next = m[r][nextCol];
                        if (next == null) break;
                        if (next.isCovered()) break;

                        int nextRs = Math.max(next.getRowSpan(), 1);
                        int nextCs = Math.max(next.getColSpan(), 1);

                        boolean nextIsEmpty = (next.getText() == null || next.getText().isBlank());
                        if (nextRs != 1 || nextCs <= 1 || !nextIsEmpty) break;

                        int add = Math.min(nextCs, cols - nextCol);
                        log.i("grid:absorb empty master r=" + r + " c=" + nextCol + " addCs=" + add + " into master c=" + c);

                        for (int k = 0; k < add; k++) {
                            int cc = nextCol + k;
                            absorbed[r][cc] = true;
                            markCoveredDrop(cellHandles[r][cc], r, cc, log);
                        }

                        cs = Math.min(cs + add, cols - c);
                    }
                }

                if (cs > 1 || rs > 1) {
                    log.i("grid:masterSpan r=" + r + " c=" + c + " cs=" + cs + " rs=" + rs);
                    applySpanAndDropCovered(report, cellHandles, r, c, cs, rs, log);
                }
            }
        }

        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                CellHandle ghCell = cellHandles[r][c];
                CellIR cell = m[r][c];
                if (cell == null) continue;

                if (absorbed[r][c]) continue;

                if (cell.isCovered()) {
                    if (!DROP_ALL.equals(ghCell.getProperty(CellHandle.DROP_PROP))) {
                        markCoveredDrop(ghCell, r, c, log);
                    }
                    continue;
                }

                Object drop = ghCell.getProperty(CellHandle.DROP_PROP);
                if (DROP_ALL.equals(drop)) continue;

                applyCellStyle(ghCell, cell.getStyle());

                String txt = cell.getText();
                if (txt != null && !txt.isBlank()) {
                    ghCell.getContent().add(buildText(report, txt, cell.getStyle(), log));
                }
            }
        }

        validateGridStructure(grid);
        return grid;
    }

    private static List<Float> normalizeColWidthsMm(List<Float> src, int cols) {
        if (cols <= 0) return null;
        if (src == null || src.isEmpty()) return null;

        ArrayList<Float> cleaned = new ArrayList<>(src.size());
        for (Float v : src) cleaned.add(v == null ? 0f : Math.max(0f, v));

        if (cleaned.size() == cols) return cleaned;

        ArrayList<Float> out = new ArrayList<>(cols);

        if (cleaned.size() == 1) {
            float w = cleaned.get(0);
            for (int i = 0; i < cols; i++) out.add(w);
            return out;
        }

        if (cleaned.size() < cols) {
            out.addAll(cleaned);
            float fill = 0f;

            fill = cleaned.get(cleaned.size() - 1);

            if (fill <= 0f) {
                float sum = 0f; int cnt = 0;
                for (Float v : cleaned) if (v != null && v > 0f) { sum += v; cnt++; }
                fill = (cnt > 0) ? (sum / cnt) : 0f;
            }

            while (out.size() < cols) out.add(fill);
            return out;
        }

        for (int i = 0; i < cols; i++) out.add(cleaned.get(i));
        return out;
    }


    private static void markCoveredDrop(CellHandle cell, int r, int c, DebugSink log) throws SemanticException {
        clearSlot(cell.getContent());
        cell.clearProperty(CellHandle.COL_SPAN_PROP);
        cell.clearProperty(CellHandle.ROW_SPAN_PROP);

        cell.setProperty(CellHandle.DROP_PROP, DROP_ALL);

        clearCellStyleProps(cell);
        log.i("covered cell DROPPED r=" + r + " c=" + c);
    }

    private static void applySpanAndDropCovered(
            ReportDesignHandle report,
            CellHandle[][] cellHandles,
            int r,
            int c,
            int cs,
            int rs,
            DebugSink log
    ) throws SemanticException {
        CellHandle master = cellHandles[r][c];
        markMasterNotDropped(master);

        if (cs > 1) master.setColumnSpan(cs);
        if (rs > 1) master.setRowSpan(rs);

        for (int rr = r; rr < r + rs; rr++) {
            for (int cc = c; cc < c + cs; cc++) {
                if (rr == r && cc == c) continue;
                markCoveredDrop(cellHandles[rr][cc], rr, cc, log);
            }
        }
    }

    private static void markMasterNotDropped(CellHandle cell) throws SemanticException {
        cell.clearProperty(CellHandle.DROP_PROP);
    }

    private static void clearCellStyleProps(CellHandle cell) throws SemanticException {
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

    private DesignElementHandle buildText(
            ReportDesignHandle report,
            String srcText,
            StyleIR style,
            DebugSink log
    ) throws SemanticException {

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
        td.setProperty(TextDataHandle.CONTENT_TYPE_PROP, DesignChoiceConstants.TEXT_CONTENT_TYPE_HTML);

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
        int nulls = 0;
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                if (m[r][c] == null) nulls++;
            }
        }
        if (nulls > 0) {
            log.e("matrix:NULLS " + nulls);
            throw new IllegalStateException("Normalized matrix contains null cells: " + nulls);
        }

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
            var dbf = DocumentBuilderFactory.newInstance();
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
            var tf = TransformerFactory.newInstance();
            var t = tf.newTransformer();
            t.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
            t.setOutputProperty(OutputKeys.INDENT, "no");

            var sw = new StringWriter();
            t.transform(new DOMSource(el), new StreamResult(sw));
            return sw.toString();
        } catch (Exception ex) {
            return "<serialize failed: " + ex.getMessage() + ">";
        }
    }

    private void validateXmlGridEffectiveWidth(Path rpt, DebugSink log) {
        log.i("xmlValidateEff: skipped");
    }

    private static void clearSlot(SlotHandle slot) throws SemanticException {
        while (slot.getCount() > 0) slot.drop(0);
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

        if (st.getFontColor() != null && !st.getFontColor().isBlank()) {
            textEl.setProperty(StyleHandle.COLOR_PROP, st.getFontColor());
        }
    }


    private static void applyCellStyle(CellHandle cell, StyleIR st) throws SemanticException {
        final String baseColor = "#000000";
        final String baseWidthPx = "1px";

        setBorderSide(cell, StyleHandle.BORDER_TOP_COLOR_PROP,   StyleHandle.BORDER_TOP_STYLE_PROP,   StyleHandle.BORDER_TOP_WIDTH_PROP,   baseColor, baseWidthPx);
        setBorderSide(cell, StyleHandle.BORDER_BOTTOM_COLOR_PROP,StyleHandle.BORDER_BOTTOM_STYLE_PROP,StyleHandle.BORDER_BOTTOM_WIDTH_PROP,baseColor, baseWidthPx);
        setBorderSide(cell, StyleHandle.BORDER_LEFT_COLOR_PROP,  StyleHandle.BORDER_LEFT_STYLE_PROP,  StyleHandle.BORDER_LEFT_WIDTH_PROP,  baseColor, baseWidthPx);
        setBorderSide(cell, StyleHandle.BORDER_RIGHT_COLOR_PROP, StyleHandle.BORDER_RIGHT_STYLE_PROP, StyleHandle.BORDER_RIGHT_WIDTH_PROP, baseColor, baseWidthPx);

        if (st == null) return;

        if (st.getBackgroundColor() != null && !st.getBackgroundColor().isBlank()) {
            cell.setProperty(StyleHandle.BACKGROUND_COLOR_PROP, st.getBackgroundColor());
        }

        String birtAlign = normalizeBirtAlign(st.getAlign());
        if (birtAlign != null) {
            cell.setProperty(StyleHandle.TEXT_ALIGN_PROP, birtAlign);
        }

        String borderColor = (st.getBorderColor() != null && !st.getBorderColor().isBlank())
                ? st.getBorderColor()
                : baseColor;

        cell.setProperty(StyleHandle.BORDER_TOP_COLOR_PROP, borderColor);
        cell.setProperty(StyleHandle.BORDER_BOTTOM_COLOR_PROP, borderColor);
        cell.setProperty(StyleHandle.BORDER_LEFT_COLOR_PROP, borderColor);
        cell.setProperty(StyleHandle.BORDER_RIGHT_COLOR_PROP, borderColor);

        String topW = toBirtBorderWidthPx(st.getBorderTopWidthPt());
        String bottomW = toBirtBorderWidthPx(st.getBorderBottomWidthPt());
        String leftW = toBirtBorderWidthPx(st.getBorderLeftWidthPt());
        String rightW = toBirtBorderWidthPx(st.getBorderRightWidthPt());

        if (topW != null)    cell.setProperty(StyleHandle.BORDER_TOP_WIDTH_PROP, topW);
        if (bottomW != null) cell.setProperty(StyleHandle.BORDER_BOTTOM_WIDTH_PROP, bottomW);
        if (leftW != null)   cell.setProperty(StyleHandle.BORDER_LEFT_WIDTH_PROP, leftW);
        if (rightW != null)  cell.setProperty(StyleHandle.BORDER_RIGHT_WIDTH_PROP, rightW);

        if (st.getPaddingTopPt() != null) cell.setProperty(StyleHandle.PADDING_TOP_PROP, toBirtPt(st.getPaddingTopPt()));
        if (st.getPaddingBottomPt() != null) cell.setProperty(StyleHandle.PADDING_BOTTOM_PROP, toBirtPt(st.getPaddingBottomPt()));
        if (st.getPaddingLeftPt() != null) cell.setProperty(StyleHandle.PADDING_LEFT_PROP, toBirtPt(st.getPaddingLeftPt()));
        if (st.getPaddingRightPt() != null) cell.setProperty(StyleHandle.PADDING_RIGHT_PROP, toBirtPt(st.getPaddingRightPt()));

    }

    private static String toBirtPt(Float pt) {
        if (pt == null) return null;
        if (pt <= 0.01f) return null;
        float v = Math.round(pt * 10f) / 10f;
        return v + "pt";
    }


    private static void setBorderSide(
            CellHandle cell,
            String colorProp,
            String styleProp,
            String widthProp,
            String color,
            String width
    ) throws SemanticException {
        cell.setProperty(colorProp, color);
        cell.setProperty(styleProp, DesignChoiceConstants.LINE_STYLE_SOLID);
        cell.setProperty(widthProp, width);
    }

    private static String normalizeBirtAlign(String odsAlign) {
        if (odsAlign == null) return null;
        String v = odsAlign.trim().toLowerCase(Locale.ROOT);
        if (v.isEmpty()) return null;

        return switch (v) {
            case "start" -> "left";
            case "end" -> "right";
            case "left", "right", "center", "justify" -> v;
            default -> null;
        };
    }

    private static String toBirtBorderWidthPx(Float pt) {
        if (pt == null) return null;
        if (pt <= 0.01f) return null;

        float pxF = pt * (96f / 72f);

        int px;
        if (pxF <= 1.2f) px = 1;
        else if (pxF <= 2.2f) px = 2;
        else if (pxF <= 3.2f) px = 3;
        else px = 4;

        return px + "px";
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

    private GridHandle buildParagraphsGrid(
            ReportDesignHandle report,
            List<ParagraphIR> paragraphs,
            DebugSink log
    ) throws SemanticException {

        if (paragraphs == null || paragraphs.isEmpty()) return null;

        int rows = paragraphs.size();
        GridHandle g = report.getElementFactory().newGridItem(null, 1, rows);

        g.setProperty(GridHandle.WIDTH_PROP, "100%");

        ColumnHandle col = (ColumnHandle) g.getColumns().get(0);

        for (int i = 0; i < rows; i++) {
            ParagraphIR p = paragraphs.get(i);
            if (p == null) continue;

            RowHandle row = (RowHandle) g.getRows().get(i);
            CellHandle cell = (CellHandle) row.getCells().get(0);

            StyleIR st = p.getStyle();
            if (st != null) {
                String a = normalizeBirtAlign(st.getAlign());
                if (a != null) cell.setProperty(StyleHandle.TEXT_ALIGN_PROP, a);
            }

            String txt = p.getText();
            if (txt == null) txt = "";

            cell.getContent().add(buildText(report, txt, st, log));
        }

        return g;
    }

    private static final String EMBEDDED_LOGO_NAME = "logo.png";

    private void applyOptionalPng(ReportDesignHandle report, Path pngPath, DebugSink log) {
        if (report == null || pngPath == null) return;

        try {
            if (!Files.exists(pngPath)) {
                log.i("png: skip (not exists): " + pngPath);
                return;
            }

            ensureEmbeddedImage(report, EMBEDDED_LOGO_NAME, pngPath, log);
            log.i("png: ensured embedded=" + EMBEDDED_LOGO_NAME);

        } catch (Exception e) {
            log.i("png: failed (" + e.getClass().getSimpleName() + "): " + e.getMessage());
        }
    }


    private void applyEmbeddedToImageItem(ImageHandle img, String embName, DebugSink log, String where) throws Exception {
        img.setImageName(embName);

        try {
            img.setProperty(ImageHandle.SOURCE_PROP, DesignChoiceConstants.IMAGE_REF_TYPE_EMBED);
        } catch (Throwable ignore) {
            img.setProperty(ImageHandle.SOURCE_PROP, "embed");
        }

        img.clearProperty(ImageHandle.URI_PROP);

        log.i("png: applied embedded (" + where + ") id=" + img.getID()
                + " name=" + img.getName() + " emb=" + embName);
    }



    private void ensureEmbeddedImage(ReportDesignHandle report, String embName, Path pngPath, DebugSink log) throws Exception {
        if (report == null || embName == null || embName.isBlank() || pngPath == null) return;

        byte[] pngBytes = Files.readAllBytes(pngPath);
        log.i("png: path=" + pngPath + " size=" + pngBytes.length + " head=" + hexPrefix(pngBytes, 16));

        if (!isPng(pngBytes)) throw new IllegalStateException("File is not PNG. head=" + hexPrefix(pngBytes, 16));
        if (pngBytes.length == 0) throw new IllegalStateException("PNG is empty: " + pngPath);

        PropertyHandle images = report.getPropertyHandle(IModuleModel.IMAGES_PROP);
        EmbeddedImageHandle existing = findEmbeddedImageByName(images, embName);

        if (existing == null) {
            EmbeddedImage st = StructureFactory.createEmbeddedImage();
            st.setName(embName);
            st.setType(DesignChoiceConstants.IMAGE_TYPE_IMAGE_PNG);

            // ВАЖНО: кладём СЫРЫЕ PNG bytes
            st.setData(pngBytes);

            images.addItem(st);
            log.i("embeddedImage: added name=" + embName + " pngBytes=" + pngBytes.length);
        } else {
            existing.setType(DesignChoiceConstants.IMAGE_TYPE_IMAGE_PNG);

            // ВАЖНО: кладём СЫРЫЕ PNG bytes
            Object s = existing.getStructure();
            if (s instanceof EmbeddedImage ei) {
                ei.setData(pngBytes);
            } else {
                // если вдруг структура не та — всё равно пробуем через property как bytes
                existing.setProperty(EmbeddedImage.DATA_MEMBER, new String(pngBytes, java.nio.charset.Charset.forName(EmbeddedImage.CHARSET)));
            }

            log.i("embeddedImage: updated name=" + embName + " pngBytes=" + pngBytes.length);
        }
    }


    private EmbeddedImageHandle findEmbeddedImageByName(PropertyHandle images, String embName) {
        if (images == null || embName == null) return null;

        for (java.util.Iterator<?> it = images.iterator(); it.hasNext(); ) {
            Object o = it.next();

            if (o instanceof EmbeddedImageHandle eh) {
                if (embName.equals(eh.getName())) return eh;
                continue;
            }

            if (o instanceof StructureHandle sh) {
                Object s = sh.getStructure();
                if (s instanceof EmbeddedImage ei) {
                    Object n = ei.getProperty(null, EmbeddedImage.NAME_MEMBER);
                    if (embName.equals(n)) return (EmbeddedImageHandle) sh;
                }
            }
        }
        return null;
    }


    private ImageHandle findFirstImageInSlot(SlotHandle slot) throws Exception {
        if (slot == null) return null;

        int n = slot.getCount();
        for (int i = 0; i < n; i++) {
            DesignElementHandle el = (DesignElementHandle) slot.get(i);
            ImageHandle found = findFirstImageInElement(el);
            if (found != null) return found;
        }
        return null;
    }

    private ImageHandle findFirstImageInElement(DesignElementHandle el) throws Exception {
        if (el == null) return null;

        if (el instanceof ImageHandle img) return img;

        List<?> slotDefs = getSlotDefsCompat(el);
        if (slotDefs == null || slotDefs.isEmpty()) return null;

        for (Object sd : slotDefs) {
            String slotName = getSlotNameCompat(sd);
            if (slotName == null || slotName.isBlank()) continue;

            SlotHandle child = getSlotHandleCompat(el, sd, slotName);
            ImageHandle inSlot = findFirstImageInSlot(child);
            if (inSlot != null) return inSlot;
        }

        return null;
    }

    @SuppressWarnings("unchecked")
    private List<?> getSlotDefsCompat(DesignElementHandle el) {
        try {
            Object defn = el.getDefn();
            if (defn == null) return null;

            try {
                Method m = defn.getClass().getMethod("getSlotDefinitions");
                Object r = m.invoke(defn);
                if (r instanceof List) return (List<?>) r;
            } catch (NoSuchMethodException ignore) {
            }

            try {
                Method m = defn.getClass().getMethod("getSlots");
                Object r = m.invoke(defn);
                if (r instanceof List) return (List<?>) r;
            } catch (NoSuchMethodException ignore) {
                return null;
            }

            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private String getSlotNameCompat(Object slotDef) {
        if (slotDef == null) return null;
        try {
            Method m = slotDef.getClass().getMethod("getName");
            Object r = m.invoke(slotDef);
            return (r == null) ? null : String.valueOf(r);
        } catch (Exception e) {
            return null;
        }
    }


    private SlotHandle getSlotHandleCompat(DesignElementHandle el, Object slotDef, String slotName) {
        if (el == null) return null;

        // 1) Иногда в других версиях/сборках мог быть getSlot(String) — пробуем рефлексией
        try {
            Method m = el.getClass().getMethod("getSlot", String.class);
            Object r = m.invoke(el, slotName);
            if (r instanceof SlotHandle sh) return sh;
        } catch (NoSuchMethodException ignore) {
            // в BIRT 4.20 обычно нет
        } catch (Exception ignore) {
            // не критично
        }

        // 2) BIRT 4.20: getSlot(int). Нужно получить slotId из slotDef
        Integer slotId = getSlotIdCompat(slotDef);
        if (slotId == null) return null;

        try {
            return el.getSlot(slotId);
        } catch (Exception ignore) {
            return null;
        }
    }

    private Integer getSlotIdCompat(Object slotDef) {
        if (slotDef == null) return null;
        String[] candidates = new String[] {
                "getSlotID", "getSlotId", "getID", "getId", "getIndex"
        };

        for (String mn : candidates) {
            try {
                Method m = slotDef.getClass().getMethod(mn);
                Object r = m.invoke(slotDef);
                if (r instanceof Integer i) return i;
                if (r instanceof Number n) return n.intValue();
                if (r != null) {
                    try { return Integer.parseInt(String.valueOf(r).trim()); } catch (Exception ignore) {}
                }
            } catch (NoSuchMethodException ignore) {
            } catch (Exception ignore) {
            }
        }
        return null;
    }

    private GridHandle buildImageGrid(ReportDesignHandle report, ImageIR img, DebugSink log) throws SemanticException {
        GridHandle g = report.getElementFactory().newGridItem(null, 1, 1);
        g.setProperty(GridHandle.WIDTH_PROP, "100%");

        RowHandle row = (RowHandle) g.getRows().get(0);
        CellHandle cell = (CellHandle) row.getCells().get(0);

        cell.setProperty(StyleHandle.TEXT_ALIGN_PROP, "center");

        ImageHandle ih = report.getElementFactory().newImage(null);
        log.i("image:item props source=" + ih.getProperty(ImageHandle.SOURCE_PROP)
                + " imageName=" + ih.getProperty(ImageHandle.IMAGE_NAME_PROP)
                + " uri=" + ih.getProperty(ImageHandle.URI_PROP));


        if (img.getWidthMm() != null) ih.setProperty(StyleHandle.WIDTH_PROP, fmtMm(img.getWidthMm()));
        if (img.getHeightMm() != null) ih.setProperty(StyleHandle.HEIGHT_PROP, fmtMm(img.getHeightMm()));

        ih.setProperty(ImageHandle.SOURCE_PROP, "embed");
        ih.setProperty(ImageHandle.IMAGE_NAME_PROP, EMBEDDED_LOGO_NAME);
        ih.clearProperty(ImageHandle.URI_PROP);

        cell.getContent().add(ih);

        log.i("image:block added name=" + img.getName()
                + " w=" + img.getWidthMm() + "mm h=" + img.getHeightMm() + "mm"
                + " -> embed=" + EMBEDDED_LOGO_NAME);
        return g;
    }

    private static boolean isPng(byte[] b) {
        return b != null && b.length >= 8
                && (b[0] & 0xFF) == 0x89
                && b[1] == 0x50 && b[2] == 0x4E && b[3] == 0x47
                && b[4] == 0x0D && b[5] == 0x0A && b[6] == 0x1A && b[7] == 0x0A;
    }

    private static String hexPrefix(byte[] b, int n) {
        if (b == null) return "null";
        int k = Math.min(n, b.length);
        StringBuilder sb = new StringBuilder(k * 3);
        for (int i = 0; i < k; i++) sb.append(String.format("%02X ", b[i]));
        return sb.toString().trim();
    }

    private static String readEmbeddedImageDataHead(Path rpt, String imageName) {
        try {
            String xml = Files.readString(rpt, StandardCharsets.UTF_8);

            int iName = xml.indexOf("<property name=\"name\">" + imageName + "</property>");
            if (iName < 0) return "name-not-found";

            int iDataOpen = xml.indexOf("<property name=\"data\">", iName);
            if (iDataOpen < 0) return "data-open-not-found";

            int start = iDataOpen + "<property name=\"data\">".length();
            int end = xml.indexOf("</property>", start);
            if (end < 0) return "data-close-not-found";

            String data = xml.substring(start, end)
                    .replace("\r", "")
                    .replace("\n", "")
                    .replaceAll("\\s+", "");

            return data.substring(0, Math.min(24, data.length()));
        } catch (Exception e) {
            return "read-failed:" + e.getClass().getSimpleName();
        }
    }

    private void logMasterPage(ReportDesignHandle report, DebugSink log) throws SemanticException {
        if (report == null || report.getMasterPages() == null || report.getMasterPages().getCount() == 0) {
            log.i("mp: none");
            return;
        }

        for (int i = 0; i < report.getMasterPages().getCount(); i++) {
            DesignElementHandle h = (DesignElementHandle) report.getMasterPages().get(i);
            if (!(h instanceof SimpleMasterPageHandle mp)) {
                log.i("mp#" + i + ": " + h.getClass().getSimpleName() + " name=" + h.getName());
                continue;
            }

            log.i("mp#" + i
                    + " name=" + mp.getName()
                    + " type=" + mp.getProperty("type")
                    + " orientation=" + mp.getProperty("orientation")
                    + " width=" + mp.getProperty("width")
                    + " height=" + mp.getProperty("height")
                    + " margins t=" + mp.getProperty("topMargin")
                    + " l=" + mp.getProperty("leftMargin")
                    + " b=" + mp.getProperty("bottomMargin")
                    + " r=" + mp.getProperty("rightMargin"));
        }
    }

    private void logGridGeometry(GridHandle g, String tag, DebugSink log) throws SemanticException {
        if (g == null) return;

        log.i(tag + ": grid id=" + g.getID()
                + " name=" + g.getName()
                + " widthProp=" + g.getProperty(GridHandle.WIDTH_PROP)
                + " colCount=" + g.getColumns().getCount()
                + " rowCount=" + g.getRows().getCount());

        // колонки
        for (int c = 0; c < g.getColumns().getCount(); c++) {
            ColumnHandle col = (ColumnHandle) g.getColumns().get(c);
            log.i(tag + ":  col[" + c + "] width=" + col.getProperty(ColumnHandle.WIDTH_PROP));
        }

        // строки + ячейки (коротко)
        for (int r = 0; r < g.getRows().getCount(); r++) {
            RowHandle row = (RowHandle) g.getRows().get(r);
            Object h = row.getProperty(RowHandle.HEIGHT_PROP);
            log.i(tag + ":  row[" + r + "] height=" + h + " cellCount=" + row.getCells().getCount());

            for (int c = 0; c < row.getCells().getCount(); c++) {
                CellHandle cell = (CellHandle) row.getCells().get(c);
                Object cs = cell.getProperty(CellHandle.COL_SPAN_PROP);
                Object rs = cell.getProperty(CellHandle.ROW_SPAN_PROP);
                Object drop = cell.getProperty(CellHandle.DROP_PROP);

                log.i(tag + ":    cell[" + r + "," + c + "] id=" + cell.getID()
                        + " cs=" + cs + " rs=" + rs + " drop=" + drop
                        + " align=" + cell.getProperty(StyleHandle.TEXT_ALIGN_PROP)
                        + " padT=" + cell.getProperty(StyleHandle.PADDING_TOP_PROP)
                        + " padB=" + cell.getProperty(StyleHandle.PADDING_BOTTOM_PROP)
                        + " padL=" + cell.getProperty(StyleHandle.PADDING_LEFT_PROP)
                        + " padR=" + cell.getProperty(StyleHandle.PADDING_RIGHT_PROP)
                        + " contentCount=" + cell.getContent().getCount());
            }
        }
    }


    private void logAllImages(ReportDesignHandle report, DebugSink log) throws SemanticException {
        log.i("img: scan start");

        // Body
        logImagesInSlot(report.getBody(), "body", log);

        // MasterPages
        if (report.getMasterPages() != null) {
            for (int i = 0; i < report.getMasterPages().getCount(); i++) {
                DesignElementHandle mph = (DesignElementHandle) report.getMasterPages().get(i);
                logImagesInElement(mph, "master#" + i, log);
            }
        }

        log.i("img: scan done");
    }

    private void logImagesInSlot(SlotHandle slot, String where, DebugSink log) throws SemanticException {
        if (slot == null) return;
        for (int i = 0; i < slot.getCount(); i++) {
            DesignElementHandle el = (DesignElementHandle) slot.get(i);
            logImagesInElement(el, where, log);
        }
    }
    private static float sumPositive(List<Float> mm) {
        if (mm == null) return 0f;
        float s = 0f;
        for (Float v : mm) {
            if (v == null) continue;
            if (v > 0f) s += v;
        }
        return s;
    }


    private void logImagesInElement(DesignElementHandle el, String where, DebugSink log) throws SemanticException {
        if (el == null) return;

        if (el instanceof ImageHandle img) {
            log.i("img: where=" + where
                    + " id=" + img.getID()
                    + " name=" + img.getName()
                    + " source=" + img.getProperty(ImageHandle.SOURCE_PROP)
                    + " imageName=" + img.getProperty(ImageHandle.IMAGE_NAME_PROP)
                    + " uri=" + img.getProperty(ImageHandle.URI_PROP)
                    + " w=" + img.getProperty(StyleHandle.WIDTH_PROP)
                    + " h=" + img.getProperty(StyleHandle.HEIGHT_PROP));
            return;
        }

        // рекурсивно по слотам (используй твои getSlotDefsCompat/getSlotHandleCompat)
        List<?> slotDefs = getSlotDefsCompat(el);
        if (slotDefs == null) return;

        for (Object sd : slotDefs) {
            String slotName = getSlotNameCompat(sd);
            if (slotName == null || slotName.isBlank()) continue;
            SlotHandle child = getSlotHandleCompat(el, sd, slotName);
            logImagesInSlot(child, where + "/" + el.getClass().getSimpleName() + ":" + slotName, log);
        }
    }

    private void logXmlImageGeometry(Path rpt, String imageName, DebugSink log) {
        try {
            var dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(true);
            var db = dbf.newDocumentBuilder();

            org.w3c.dom.Document doc;
            try (InputStream in = Files.newInputStream(rpt)) { doc = db.parse(in); }

            final String NS = doc.getDocumentElement().getNamespaceURI();

            NodeList images = doc.getElementsByTagNameNS(NS, "image");
            for (int i = 0; i < images.getLength(); i++) {
                Element img = (Element) images.item(i);

                String imgName = findProp(img, NS, "imageName");
                if (imageName != null && !imageName.equals(imgName)) continue;

                String src = findProp(img, NS, "source");
                String w = findProp(img, NS, "width");
                String h = findProp(img, NS, "height");

                log.i("xml:image id=" + img.getAttribute("id")
                        + " imageName=" + imgName
                        + " source=" + src
                        + " w=" + w + " h=" + h);
            }
        } catch (Exception e) {
            log.i("xml:image log failed: " + e.getMessage());
        }
    }

    private static String findProp(Element parent, String ns, String name) {
        NodeList kids = parent.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node n = kids.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element el = (Element) n;
            if (!ns.equals(el.getNamespaceURI())) continue;
            if (!"property".equals(el.getLocalName())) continue;
            if (!name.equals(el.getAttribute("name"))) continue;
            return el.getTextContent().trim();
        }
        return null;
    }

    private static float parseMm(String v) {
        if (v == null) return 0f;
        String s = v.trim().toLowerCase(Locale.ROOT);
        if (s.endsWith("mm")) s = s.substring(0, s.length() - 2);
        try { return Float.parseFloat(s.replace(',', '.')); } catch (Exception e) { return 0f; }
    }

    private static float getContentWidthMm(ReportDesignHandle report) throws SemanticException {
        if (report.getMasterPages().getCount() == 0) return 0f;
        var mp = (SimpleMasterPageHandle) report.getMasterPages().get(0);
        float w = parseMm(String.valueOf(mp.getProperty("width")));
        float ml = parseMm(String.valueOf(mp.getProperty("leftMargin")));
        float mr = parseMm(String.valueOf(mp.getProperty("rightMargin")));
        float cw = w - ml - mr;
        return Math.max(cw, 0f);
    }

    private static void fixGridRowCellCounts(org.w3c.dom.Document doc) {
        final String NS = "http://www.eclipse.org/birt/2005/design";

        NodeList grids = doc.getElementsByTagNameNS(NS, "grid");
        for (int gi = 0; gi < grids.getLength(); gi++) {
            Element gridEl = (Element) grids.item(gi);

            int cols = countDirectChildElements(gridEl, NS, "column");
            if (cols <= 0) continue;

            NodeList children = gridEl.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                Node n = children.item(i);
                if (n.getNodeType() != Node.ELEMENT_NODE) continue;

                Element rowEl = (Element) n;
                if (!NS.equals(rowEl.getNamespaceURI())) continue;
                if (!"row".equals(rowEl.getLocalName())) continue;

                // собрать текущие cell (только direct children)
                ArrayList<Element> cells = new ArrayList<>();
                NodeList rowKids = rowEl.getChildNodes();
                for (int j = 0; j < rowKids.getLength(); j++) {
                    Node rc = rowKids.item(j);
                    if (rc.getNodeType() != Node.ELEMENT_NODE) continue;
                    Element cel = (Element) rc;
                    if (NS.equals(cel.getNamespaceURI()) && "cell".equals(cel.getLocalName())) {
                        cells.add(cel);
                    }
                }

                // построить новый список ячеек с учетом colSpan
                ArrayList<Element> rebuilt = new ArrayList<>(cols);
                for (Element cellEl : cells) {
                    int cs = getIntProperty(cellEl, NS, "colSpan", 1);
                    if (cs < 1) cs = 1;

                    rebuilt.add(cellEl);

                    // вставляем (cs-1) drop-ячейки сразу после мастера
                    for (int k = 1; k < cs; k++) {
                        rebuilt.add(newDropCell(doc, NS));
                    }
                }

                // добить до cols
                while (rebuilt.size() < cols) {
                    rebuilt.add(newDropCell(doc, NS));
                }

                // если переполнено — режем (лучше, чем отдавать битый дизайн)
                if (rebuilt.size() > cols) {
                    rebuilt.subList(cols, rebuilt.size()).clear();
                }

                // удалить все старые cell из row
                for (Element old : cells) {
                    rowEl.removeChild(old);
                }

                // вставить заново в конец row (в порядке)
                for (Element c : rebuilt) {
                    // гарантируем корректность drop-cell
                    if (isDropAll(c, NS)) {
                        removeProperty(c, NS, "colSpan");
                        removeProperty(c, NS, "rowSpan");
                        removeAllContentElements(c); // текст/грид/картинки
                    }
                    rowEl.appendChild(c);
                }
            }
        }
    }

    private static Element newDropCell(org.w3c.dom.Document doc, String ns) {
        Element cell = doc.createElementNS(ns, "cell");
        Element p = doc.createElementNS(ns, "property");
        p.setAttribute("name", "drop");
        p.setTextContent("all");
        cell.appendChild(p);
        return cell;
    }

    private static boolean isDropAll(Element cellEl, String ns) {
        NodeList kids = cellEl.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node n = kids.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element el = (Element) n;
            if (!ns.equals(el.getNamespaceURI())) continue;
            if (!"property".equals(el.getLocalName())) continue;
            if (!"drop".equals(el.getAttribute("name"))) continue;
            return "all".equals(el.getTextContent().trim());
        }
        return false;
    }

    private static void removeProperty(Element parent, String ns, String name) {
        NodeList kids = parent.getChildNodes();
        for (int i = kids.getLength() - 1; i >= 0; i--) {
            Node n = kids.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element el = (Element) n;
            if (!ns.equals(el.getNamespaceURI())) continue;
            if (!"property".equals(el.getLocalName())) continue;
            if (!name.equals(el.getAttribute("name"))) continue;
            parent.removeChild(el);
        }
    }

    private static void removeAllContentElements(Element cellEl) {
        NodeList kids = cellEl.getChildNodes();
        for (int i = kids.getLength() - 1; i >= 0; i--) {
            Node n = kids.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element el = (Element) n;
            String ln = el.getLocalName();
            if ("text".equals(ln) || "text-data".equals(ln) || "grid".equals(ln) || "image".equals(ln)) {
                cellEl.removeChild(el);
            }
        }
    }


}
