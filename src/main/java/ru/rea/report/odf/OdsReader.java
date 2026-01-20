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
    private volatile StyleIR defaultCellStyle;


    private static final String TABLE_NS  = "urn:oasis:names:tc:opendocument:xmlns:table:1.0";
    private static final String OFFICE_NS = "urn:oasis:names:tc:opendocument:xmlns:office:1.0";
    private static final String STYLE_NS  = "urn:oasis:names:tc:opendocument:xmlns:style:1.0";
    private static final String FO_NS     = "urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0";


    private final Map<String, StyleIR> styleCache = new HashMap<>();
    private final Map<String, Float> colWidthCache = new HashMap<>();


    public TemplateDocumentIR read(InputStream in) {
        styleCache.clear();
        colWidthCache.clear();
        defaultCellStyle = null;

        try {
            OdfSpreadsheetDocument doc = OdfSpreadsheetDocument.loadDocument(in);
            Document contentDom = doc.getContentDom();

            List<OdfTable> tables = doc.getTableList();
            if (tables.isEmpty()) throw new BadTemplateException("ODS contains no sheets/tables");

            OdfTable sheet = tables.get(0);

            System.out.println("[ODS] sheet rows=" + sheet.getRowCount()
                    + " cols(sheet.getColumnCount)=" + sheet.getColumnCount());

            int[] bounds = detectBoundsByDom(contentDom);
            int lastRow = bounds[0];
            int lastCol = bounds[1];

            System.out.println("[ODS] domBounds lastRow=" + lastRow + " lastCol=" + lastCol);

            if (lastRow < 0 || lastCol < 0) {
                throw new BadTemplateException("ODS sheet is empty (no visible content found)");
            }

            // Пока фиксируем начало таблицы с A1
            int firstRow = 0;
            int firstCol = 0;

            int rows = Math.min(lastRow - firstRow + 1, HARD_MAX_ROWS);
            int cols = Math.min(lastCol - firstCol + 1, HARD_MAX_COLS);

            TableIR tableIR = new TableIR();

            for (int r = 0; r < rows; r++) {
                int rAbs = firstRow + r;
                OdfTableRow row = sheet.getRowByIndex(rAbs);
                RowIR rowIR = new RowIR();

                for (int c = 0; c < cols; c++) {
                    int cAbs = firstCol + c;

                    OdfTableCell cell;
                    try {
                        cell = row.getCellByIndex(cAbs);
                    } catch (Throwable ex) {
                        // если ODFDOM не даёт ячейку — считаем пустой
                        rowIR.addCell(new CellIR(""));
                        continue;
                    }

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

        StyleIR base = resolveDefaultCellStyle(contentDom);
        StyleIR specific = resolveStyleRecursive(contentDom, styleName, 0);

        StyleIR merged = StyleIR.merge(base, specific);

        boolean empty = (merged == null) ||
                ((merged.getBackgroundColor() == null || merged.getBackgroundColor().isBlank()) &&
                        (merged.getFontColor() == null || merged.getFontColor().isBlank()) &&
                        (merged.getBorderColor() == null || merged.getBorderColor().isBlank()) &&
                        merged.getAlign() == null &&
                        merged.getFontSizePt() == null &&
                        !merged.isBold() &&
                        !merged.isItalic() &&
                        !merged.isBorder() &&
                        merged.getBorderTopWidthPt() == null &&
                        merged.getBorderBottomWidthPt() == null &&
                        merged.getBorderLeftWidthPt() == null &&
                        merged.getBorderRightWidthPt() == null);

        return empty ? null : merged;
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

        String s = spec.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) return null;
        if ("none".equals(s)) return new BorderSpec(false, 0f, null);

        String[] parts = s.split("\\s+");

        Float widthPt = null;
        String color = null;
        boolean none = false;

        for (String p : parts) {
            if (p == null || p.isBlank()) continue;

            if ("none".equals(p)) {
                none = true;
                continue;
            }

            if (p.startsWith("#") && (p.length() == 7 || p.length() == 4)) {
                color = p;
                continue;
            }

            Float w = parseFoSizeToPt(p);
            if (w != null) {
                widthPt = w;
            }
        }

        if (none) return new BorderSpec(false, 0f, color);

        if (widthPt == null) {
            widthPt = 0.75f;
        }

        boolean present = widthPt > 0.0001f;
        return new BorderSpec(present, widthPt, color);
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

    private StyleIR resolveDefaultCellStyle(Document contentDom) {
        if (defaultCellStyle != null) return defaultCellStyle;
        if (contentDom == null) return null;

        Element root = contentDom.getDocumentElement();
        if (root == null) return null;

        Element styles = firstDescendant(root, OFFICE_NS, "styles");
        if (styles == null) return null;

        NodeList ds = styles.getElementsByTagNameNS(STYLE_NS, "default-style");
        for (int i = 0; i < ds.getLength(); i++) {
            Node n = ds.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element el = (Element) n;

            String fam = el.getAttributeNS(STYLE_NS, "family");
            if (!"table-cell".equals(fam)) continue;

            StyleIR st = parseCellStyleProps(el);
            defaultCellStyle = st;
            return defaultCellStyle;
        }

        defaultCellStyle = null;
        return null;
    }

    private StyleIR parseCellStyleProps(Element styleEl) {
        if (styleEl == null) return null;

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

            if (all == null) {
                Float w = parseBorderLineWidthToPt(cellProps.getAttributeNS(STYLE_NS, "border-line-width"));
                if (w != null) all = new BorderSpec(true, w, null);
            }
            if (top == null) {
                Float w = parseBorderLineWidthToPt(cellProps.getAttributeNS(STYLE_NS, "border-line-width-top"));
                if (w != null) top = new BorderSpec(true, w, null);
            }
            if (bottom == null) {
                Float w = parseBorderLineWidthToPt(cellProps.getAttributeNS(STYLE_NS, "border-line-width-bottom"));
                if (w != null) bottom = new BorderSpec(true, w, null);
            }
            if (left == null) {
                Float w = parseBorderLineWidthToPt(cellProps.getAttributeNS(STYLE_NS, "border-line-width-left"));
                if (w != null) left = new BorderSpec(true, w, null);
            }
            if (right == null) {
                Float w = parseBorderLineWidthToPt(cellProps.getAttributeNS(STYLE_NS, "border-line-width-right"));
                if (w != null) right = new BorderSpec(true, w, null);
            }

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

        return empty ? null : st;
    }


    private static Float parseBorderLineWidthToPt(String v) {
        if (v == null) return null;
        String s = v.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) return null;
        String first = s.split("\\s+")[0].trim();
        return parseFoSizeToPt(first);
    }


    private StyleIR resolveStyleRecursive(Document contentDom, String styleName, int depth) {
        if (styleName == null || styleName.isBlank()) return null;
        if (depth > 32) return null; // защита от циклов

        if (styleCache.containsKey(styleName)) return styleCache.get(styleName);

        Element styleEl = findStyleByName(contentDom, styleName);
        if (styleEl == null) {
            styleCache.put(styleName, null);
            return null;
        }

        String parent = styleEl.getAttributeNS(STYLE_NS, "parent-style-name");
        StyleIR parentSt = (parent == null || parent.isBlank())
                ? null
                : resolveStyleRecursive(contentDom, parent, depth + 1);

        StyleIR selfSt = parseCellStyleProps(styleEl);

        StyleIR out = StyleIR.merge(parentSt, selfSt);

        styleCache.put(styleName, out);
        return out;
    }

    private static boolean isSignificantCell(OdfTableCell cell) {
        if (cell == null) return false;
        Element el = (Element) cell.getOdfElement();
        if (el == null) return false;

        String text = fastCellText(cell);
        if (text != null && !text.isBlank()) return true;

        String styleName = el.getAttributeNS(TABLE_NS, "style-name");
        if (styleName != null && !styleName.isBlank()) return true;

        int cs = colSpanOf(cell);
        int rs = rowSpanOf(cell);
        return cs > 1 || rs > 1;
    }

    private static int detectScanCols(OdfTable sheet, int scanRows) {
        int max = 0;
        for (int r = 0; r < scanRows; r++) {
            OdfTableRow row = sheet.getRowByIndex(r);
            if (row == null) continue;

            int cc;
            try {
                cc = row.getCellCount();
            } catch (Throwable ex) {
                cc = 0;
            }
            if (cc > max) max = cc;
        }
        return max;
    }

    private int[] detectBoundsByDom(Document contentDom) {
        int[] max = new int[]{-1, -1};
        if (contentDom == null) return max;

        Element root = contentDom.getDocumentElement();
        if (root == null) return max;

        NodeList tables = root.getElementsByTagNameNS(TABLE_NS, "table");
        if (tables.getLength() == 0) return max;

        Element tableEl = (Element) tables.item(0);

        NodeList rowNodes = tableEl.getElementsByTagNameNS(TABLE_NS, "table-row");

        int rIndex = 0;
        for (int ri = 0; ri < rowNodes.getLength() && rIndex < HARD_MAX_ROWS; ri++) {
            Node rn = rowNodes.item(ri);
            if (rn.getNodeType() != Node.ELEMENT_NODE) continue;
            Element rowEl = (Element) rn;

            int rowRepeat = getIntAttr(rowEl, TABLE_NS, "number-rows-repeated", 1);
            if (rowRepeat < 1) rowRepeat = 1;

            int cIndex = 0;
            NodeList kids = rowEl.getChildNodes();
            for (int k = 0; k < kids.getLength() && cIndex < HARD_MAX_COLS; k++) {
                Node cn = kids.item(k);
                if (cn.getNodeType() != Node.ELEMENT_NODE) continue;
                Element cellEl = (Element) cn;

                String ln = cellEl.getLocalName();
                if (!"table-cell".equals(ln) && !"covered-table-cell".equals(ln)) continue;

                int colRepeat = getIntAttr(cellEl, TABLE_NS, "number-columns-repeated", 1);
                if (colRepeat < 1) colRepeat = 1;

                int cs = getIntAttr(cellEl, TABLE_NS, "number-columns-spanned", 1);
                int rs = getIntAttr(cellEl, TABLE_NS, "number-rows-spanned", 1);
                if (cs < 1) cs = 1;
                if (rs < 1) rs = 1;

                int endCol = Math.min(HARD_MAX_COLS - 1, cIndex + colRepeat - 1);
                endCol = Math.min(HARD_MAX_COLS - 1, endCol + cs - 1);

                int endRow = Math.min(HARD_MAX_ROWS - 1, (rIndex + rowRepeat - 1) + rs - 1);

                max[0] = Math.max(max[0], endRow);
                max[1] = Math.max(max[1], endCol);

                cIndex += colRepeat;
            }

            rIndex += rowRepeat;
        }

        return max;
    }

}
