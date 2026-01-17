package ru.rea.report.odf;

import org.odftoolkit.odfdom.doc.OdfSpreadsheetDocument;
import org.odftoolkit.odfdom.doc.table.OdfTable;
import org.odftoolkit.odfdom.doc.table.OdfTableCell;
import org.odftoolkit.odfdom.doc.table.OdfTableRow;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import ru.rea.report.exception.BadTemplateException;
import ru.rea.report.ir.*;

import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class OdsReader {

    private static final int HARD_MAX_ROWS = 250;
    private static final int HARD_MAX_COLS = 80;

    private static final String TABLE_NS  = "urn:oasis:names:tc:opendocument:xmlns:table:1.0";
    private static final String OFFICE_NS = "urn:oasis:names:tc:opendocument:xmlns:office:1.0";
    private static final String STYLE_NS  = "urn:oasis:names:tc:opendocument:xmlns:style:1.0";
    private static final String FO_NS     = "urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0";


    private final Map<String, StyleIR> styleCache = new HashMap<>();
    private final Map<String, Float> colWidthCache = new HashMap<>();


    public TemplateDocumentIR read(InputStream in) {
        try {
            OdfSpreadsheetDocument doc = OdfSpreadsheetDocument.loadDocument(in);
            Document contentDom = doc.getContentDom();

            List<OdfTable> tables = doc.getTableList();
            if (tables.isEmpty()) throw new BadTemplateException("ODS contains no sheets/tables");

            OdfTable sheet = tables.get(0);

            int scanRows = Math.min(sheet.getRowCount(), HARD_MAX_ROWS);
            int scanCols = Math.min(sheet.getColumnCount(), HARD_MAX_COLS);

            int lastRow = -1;
            int lastCol = -1;

            for (int r = 0; r < scanRows; r++) {
                OdfTableRow row = sheet.getRowByIndex(r);
                boolean rowHasData = false;

                for (int c = 0; c < scanCols; c++) {
                    OdfTableCell cell = row.getCellByIndex(c);
                    if (cell == null) continue;

                    if (isCoveredCell(cell)) continue;

                    String text = fastCellText(cell);
                    if (text != null && !text.isBlank()) {
                        rowHasData = true;

                        int cs = colSpanOf(cell);
                        int rs = rowSpanOf(cell);

                        lastCol = Math.max(lastCol, c + cs - 1);
                        lastRow = Math.max(lastRow, r + rs - 1);
                    }
                }
                if (rowHasData) lastRow = Math.max(lastRow, r);
            }

            if (lastRow < 0 || lastCol < 0) {
                throw new BadTemplateException("ODS sheet is empty (no visible content found)");
            }

            lastRow = Math.min(lastRow, HARD_MAX_ROWS - 1);
            lastCol = Math.min(lastCol, HARD_MAX_COLS - 1);

            int firstRow = 0;
            outerRow:
            for (int r = 0; r <= lastRow; r++) {
                OdfTableRow row = sheet.getRowByIndex(r);
                for (int c = 0; c <= lastCol; c++) {
                    OdfTableCell cell = row.getCellByIndex(c);
                    if (cell == null) continue;
                    if (isCoveredCell(cell)) continue;
                    String text = fastCellText(cell);
                    if (text != null && !text.isBlank()) { firstRow = r; break outerRow; }
                }
            }

            int firstCol = 0;
            outerCol:
            for (int c = 0; c <= lastCol; c++) {
                for (int r = firstRow; r <= lastRow; r++) {
                    OdfTableRow row = sheet.getRowByIndex(r);
                    OdfTableCell cell = row.getCellByIndex(c);
                    if (cell == null) continue;
                    if (isCoveredCell(cell)) continue;
                    String text = fastCellText(cell);
                    if (text != null && !text.isBlank()) { firstCol = c; break outerCol; }
                }
            }

            int rows = (lastRow - firstRow + 1);
            int cols = (lastCol - firstCol + 1);

            TableIR tableIR = new TableIR();

            for (int r = 0; r < rows; r++) {
                int rAbs = firstRow + r;
                OdfTableRow row = sheet.getRowByIndex(rAbs);
                RowIR rowIR = new RowIR();

                for (int c = 0; c < cols; c++) {
                    int cAbs = firstCol + c;
                    OdfTableCell cell = row.getCellByIndex(cAbs);

                    if (isCoveredCell(cell)) {
                        CellIR cellIR = new CellIR("");
                        cellIR.setCovered(true);
                        rowIR.addCell(cellIR);
                        continue;
                    }

                    CellIR cellIR = new CellIR(fastCellText(cell));

                    StyleIR st = resolveCellStyle(contentDom, cell);
                    cellIR.setStyle(st);

                    if (cell != null) {
                        int cs = colSpanOf(cell);
                        int rs = rowSpanOf(cell);

                        cs = Math.min(Math.max(cs, 1), cols - c);
                        rs = Math.min(Math.max(rs, 1), rows - r);

                        if (cs > 1) cellIR.setColSpan(cs);
                        if (rs > 1) cellIR.setRowSpan(rs);
                    }

                    rowIR.addCell(cellIR);
                }

                tableIR.addRow(rowIR);
            }

            return new TemplateDocumentIR().add(tableIR);

        } catch (BadTemplateException e) {
            throw e;
        } catch (Exception e) {
            throw new BadTemplateException("Failed to read ODS template", e);
        }
    }

    private static String fastCellText(OdfTableCell cell) {
        if (cell == null) return "";

        String s = "";
        try {
            s = cell.getStringValue();
        } catch (Throwable ignore) { }

        if (s == null || s.isBlank()) {
            try {
                s = cell.getDisplayText();
            } catch (Throwable ignore) {
                s = "";
            }
        }

        if (s == null) s = "";
        s = s.replace("\u00AD", "");

        s = normalizeBracketTags(s);

        return s;
    }

    private static String normalizeBracketTags(String s) {
        if (s == null || s.isEmpty() || s.indexOf('[') < 0) return s;

        StringBuilder out = new StringBuilder(s.length());
        boolean in = false;

        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);

            if (ch == '[') {
                in = true;
                out.append(ch);
                continue;
            }
            if (ch == ']') {
                in = false;
                out.append(ch);
                continue;
            }

            if (in) {
                if (ch == '\r' || ch == '\n' || ch == '\t' || ch == ' ') continue;
            }

            out.append(ch);
        }
        return out.toString();
    }
    private static int getIntAttr(Element el, String namespaceUri, String localName, int def) {
        if (el == null) return def;
        String v = el.getAttributeNS(namespaceUri, localName);
        if (v == null || v.isBlank()) return def;
        try {
            int n = Integer.parseInt(v.trim());
            return Math.max(n, 1);
        } catch (Exception ignore) {
            return def;
        }
    }

    private static int colSpanOf(OdfTableCell cell) {
        if (cell == null) return 1;
        Element el = (Element) cell.getOdfElement();
        return getIntAttr(el, TABLE_NS, "number-columns-spanned", 1);
    }

    private static int rowSpanOf(OdfTableCell cell) {
        if (cell == null) return 1;
        Element el = (Element) cell.getOdfElement();
        return getIntAttr(el, TABLE_NS, "number-rows-spanned", 1);
    }

    private static boolean isCoveredCell(OdfTableCell cell) {
        if (cell == null) return false;
        Element el = (Element) cell.getOdfElement();
        if (el == null) return false;

        String ln = el.getLocalName();
        if ("covered-table-cell".equals(ln)) return true;

        String tag = el.getTagName();
        return tag != null && tag.endsWith("covered-table-cell");
    }


    private StyleIR resolveCellStyle(Document contentDom, OdfTableCell cell) {
        if (cell == null || contentDom == null) return null;

        Element cellEl = (Element) cell.getOdfElement();
        if (cellEl == null) return null;

        String styleName = cellEl.getAttributeNS(TABLE_NS, "style-name");
        if (styleName == null || styleName.isBlank()) return null;

        if (styleCache.containsKey(styleName)) return styleCache.get(styleName);

        Element styleEl = findStyleByName(contentDom, styleName);
        if (styleEl == null) {
            styleCache.put(styleName, null);
            return null;
        }

        StyleIR st = new StyleIR();

        Element cellProps = firstChild(styleEl, STYLE_NS, "table-cell-properties");
        if (cellProps != null) {
            String bg = cellProps.getAttributeNS(FO_NS, "background-color");
            if (bg != null && !bg.isBlank() && !"transparent".equalsIgnoreCase(bg)) {
                st.setBackgroundColor(bg.trim());
            }

            BorderSpec all = parseFoBorder(cellProps.getAttributeNS(FO_NS, "border"));
            BorderSpec top = parseFoBorder(cellProps.getAttributeNS(FO_NS, "border-top"));
            BorderSpec bottom = parseFoBorder(cellProps.getAttributeNS(FO_NS, "border-bottom"));
            BorderSpec left = parseFoBorder(cellProps.getAttributeNS(FO_NS, "border-left"));
            BorderSpec right = parseFoBorder(cellProps.getAttributeNS(FO_NS, "border-right"));
            applyBorderSpec(st, all, all, all, all);
            applyBorderSpec(st, top, bottom, left, right);
        }

        Element textProps = firstChild(styleEl, STYLE_NS, "text-properties");
        if (textProps != null) {
            String color = textProps.getAttributeNS(FO_NS, "color");
            if (color != null && !color.isBlank()) st.setFontColor(color.trim());

            String fw = textProps.getAttributeNS(FO_NS, "font-weight");
            if (fw != null && !fw.isBlank()) {
                String v = fw.trim().toLowerCase(Locale.ROOT);
                if ("bold".equals(v) || "700".equals(v) || "800".equals(v) || "900".equals(v)) st.setBold(true);
            }

            String fs = textProps.getAttributeNS(FO_NS, "font-style");
            if (fs != null && !fs.isBlank()) {
                if ("italic".equalsIgnoreCase(fs.trim()) || "oblique".equalsIgnoreCase(fs.trim())) st.setItalic(true);
            }

            String fsz = textProps.getAttributeNS(FO_NS, "font-size");
            Integer pt = parseFoSizeToPtInt(fsz);
            if (pt != null && pt > 0) st.setFontSizePt(pt);
        }

        Element paraProps = firstChild(styleEl, STYLE_NS, "paragraph-properties");
        if (paraProps != null) {
            String ta = paraProps.getAttributeNS(FO_NS, "text-align");
            if (ta != null && !ta.isBlank()) st.setAlign(ta.trim());
        }

        boolean empty =
                (st.getBackgroundColor() == null || st.getBackgroundColor().isBlank()) &&
                        (st.getFontColor() == null || st.getFontColor().isBlank()) &&
                        (st.getBorderColor() == null || st.getBorderColor().isBlank()) &&
                        st.getAlign() == null &&
                        st.getFontSizePt() == null &&
                        !st.isBold() &&
                        !st.isItalic() &&
                        !st.isBorder() &&
                        st.getBorderTopWidthPt() == null &&
                        st.getBorderBottomWidthPt() == null &&
                        st.getBorderLeftWidthPt() == null &&
                        st.getBorderRightWidthPt() == null;

        StyleIR out = empty ? null : st;
        styleCache.put(styleName, out);
        return out;
    }

    private static void applyBorderSpec(StyleIR st, BorderSpec top, BorderSpec bottom, BorderSpec left, BorderSpec right) {
        if (st == null) return;

        if (top != null && top.present) {
            st.setBorder(true);
            st.setBorderTopWidthPt(top.widthPt);
            if (top.color != null) st.setBorderColor(top.color);
        }
        if (bottom != null && bottom.present) {
            st.setBorder(true);
            st.setBorderBottomWidthPt(bottom.widthPt);
            if (bottom.color != null) st.setBorderColor(bottom.color);
        }
        if (left != null && left.present) {
            st.setBorder(true);
            st.setBorderLeftWidthPt(left.widthPt);
            if (left.color != null) st.setBorderColor(left.color);
        }
        if (right != null && right.present) {
            st.setBorder(true);
            st.setBorderRightWidthPt(right.widthPt);
            if (right.color != null) st.setBorderColor(right.color);
        }
    }


    private static BorderSpec parseFoBorder(String spec) {
        if (spec == null) return null;
        String s = spec.trim();
        if (s.isEmpty()) return null;
        if ("none".equalsIgnoreCase(s)) return new BorderSpec(false, 0f, null);

        String[] parts = s.split("\\s+");
        if (parts.length < 2) return null;

        Float w = parseFoSizeToPt(parts[0]);
        if (w == null) w = 0.75f; // fallback

        String color = null;
        for (String p : parts) {
            if (p.startsWith("#") && (p.length() == 7 || p.length() == 4)) {
                color = p;
                break;
            }
        }

        boolean present = w > 0.0001f && !s.toLowerCase(Locale.ROOT).contains("none");
        return new BorderSpec(present, w, color);
    }

    private static Float parseFoSizeToPt(String size) {
        if (size == null) return null;
        String s = size.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) return null;

        try {
            if (s.endsWith("pt")) {
                return Float.parseFloat(s.substring(0, s.length() - 2).trim());
            }
            if (s.endsWith("px")) {
                float px = Float.parseFloat(s.substring(0, s.length() - 2).trim());
                return px * 0.75f;
            }
            if (s.endsWith("mm")) {
                float mm = Float.parseFloat(s.substring(0, s.length() - 2).trim());
                return mm * (72f / 25.4f);
            }
            if (s.endsWith("cm")) {
                float cm = Float.parseFloat(s.substring(0, s.length() - 2).trim());
                return (cm * 10f) * (72f / 25.4f);
            }
            return Float.parseFloat(s);
        } catch (Exception ignore) {
            return null;
        }
    }

    private static Integer parseFoSizeToPtInt(String size) {
        Float pt = parseFoSizeToPt(size);
        if (pt == null) return null;
        return Math.max(1, Math.round(pt));
    }



    private static final class BorderSpec {
        final boolean present;
        final float widthPt;
        final String color;

        BorderSpec(boolean present, float widthPt, String color) {
            this.present = present;
            this.widthPt = widthPt;
            this.color = color;
        }
    }



    private Element findStyleByName(Document dom, String styleName) {
        Element root = dom.getDocumentElement();
        if (root == null) return null;

        Element auto = firstDescendant(root, OFFICE_NS, "automatic-styles");
        Element found = findStyleInContainer(auto, styleName);
        if (found != null) return found;

        Element styles = firstDescendant(root, OFFICE_NS, "styles");
        return findStyleInContainer(styles, styleName);
    }

    private Element findStyleInContainer(Element container, String styleName) {
        if (container == null) return null;

        NodeList kids = container.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node n = kids.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element e = (Element) n;

            if (!STYLE_NS.equals(e.getNamespaceURI())) continue;
            if (!"style".equals(e.getLocalName())) continue;

            String name = e.getAttributeNS(STYLE_NS, "name");
            if (styleName.equals(name)) return e;
        }
        return null;
    }

    private Element firstChild(Element parent, String ns, String local) {
        if (parent == null) return null;
        NodeList kids = parent.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node n = kids.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element e = (Element) n;
            if (ns.equals(e.getNamespaceURI()) && local.equals(e.getLocalName())) return e;
        }
        return null;
    }

    private Element firstDescendant(Element parent, String ns, String local) {
        if (parent == null) return null;
        NodeList all = parent.getElementsByTagNameNS(ns, local);
        if (all.getLength() == 0) return null;
        return (Element) all.item(0);
    }
}
