package ru.rea.report.odf;

import org.odftoolkit.odfdom.doc.OdfTextDocument;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import ru.rea.report.exception.BadTemplateException;
import ru.rea.report.ir.*;

import java.io.InputStream;
import java.util.Locale;

@Component
public class OdtReader {

    private static final String TEXT_NS   = OdtStyleResolver.TEXT_NS;
    private static final String TABLE_NS  = OdtStyleResolver.TABLE_NS;
    private static final String DRAW_NS = "urn:oasis:names:tc:opendocument:xmlns:drawing:1.0";
    private static final String SVG_NS  = "urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0";
    private static final String XLINK_NS = "http://www.w3.org/1999/xlink";


    public TemplateDocumentIR read(InputStream in) {
        try {
            OdfTextDocument doc = OdfTextDocument.loadDocument(in);

            Document contentDom = doc.getContentDom();

            Document stylesDom = null;
            try {
                stylesDom = doc.getStylesDom();
            } catch (Throwable ignore) {
            }

            OdtStyleResolver styles = new OdtStyleResolver(contentDom, stylesDom);

            TemplateDocumentIR ir = new TemplateDocumentIR();
            PageIR page = styles.resolvePage();
            if (page != null) ir.setPage(page);


            Node officeText = doc.getContentRoot();
            collectBlocks(officeText, ir, styles);

            if (ir.getBlocks().isEmpty()) {
                String text = officeText.getTextContent();
                if (text != null && !text.isBlank()) {
                    ParagraphIR p = new ParagraphIR(text.trim());
                    p.addRun(new TextRunIR(text.trim(), new StyleIR()));
                    ir.add(p);
                }
            }

            return ir;

        } catch (Exception e) {
            throw new BadTemplateException("Failed to read ODT template", e);
        }
    }

    private static void collectBlocks(Node node, TemplateDocumentIR ir, OdtStyleResolver styles) {
        collectBlocks(node, ir, styles, new ListCtx());
    }

    private static void collectBlocks(Node node, TemplateDocumentIR ir, OdtStyleResolver styles, ListCtx ctx) {
        if (node == null) return;

        if (is(node, TEXT_NS, "p")) {
            Element pEl = (Element) node;

            ImageIR img = findFirstImageInParagraph(pEl);
            if (img != null) {
                if (!paragraphHasMeaningfulText(pEl)) {
                    ir.add(img);
                    return;
                }
                ir.add(img);
            }
            ParagraphIR p = parseParagraph(pEl, styles, ctx.currentPrefix());
            if (p != null && p.getText() != null && !p.getText().isBlank()) {
                ir.add(p);
            }
            return;
        }


        if (is(node, TEXT_NS, "list")) {
            ctx.pushLevel();
            NodeList kids = node.getChildNodes();

            for (int i = 0; i < kids.getLength(); i++) {
                Node li = kids.item(i);
                if (!is(li, TEXT_NS, "list-item")) continue;

                ctx.nextIndex();

                NodeList liKids = li.getChildNodes();
                for (int j = 0; j < liKids.getLength(); j++) {
                    Node x = liKids.item(j);
                    collectBlocks(x, ir, styles, ctx);
                }
            }

            ctx.popLevel();
            return;
        }

        if (is(node, TABLE_NS, "table")) {
            TableIR t = parseTable((Element) node, styles);
            if (t != null && t.getRows() != null && !t.getRows().isEmpty()) {
                ir.add(t);
            }
            return;
        }

        if (is(node, DRAW_NS, "frame")) {
            ImageIR img = parseImageFrame((Element) node);
            if (img != null) ir.add(img);
            return;
        }


        NodeList kids = node.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            collectBlocks(kids.item(i), ir, styles, ctx);
        }
    }

    private static final class ListCtx {
        private final int[] counters = new int[16];
        private int level = 0;
        void pushLevel() {
            if (level < counters.length) {
                level++;
                counters[level - 1] = 0;
            }
        }

        void popLevel() {
            if (level > 0) {
                counters[level - 1] = 0;
                level--;
            }
        }

        void nextIndex() {
            if (level <= 0) return;
            counters[level - 1] = counters[level - 1] + 1;
        }

        String currentPrefix() {
            if (level <= 0) return null;

            StringBuilder sb = new StringBuilder(32);
            for (int i = 0; i < level; i++) {
                int v = counters[i];
                if (v <= 0) break;
                sb.append(v).append('.');
            }
            if (sb.length() == 0) return null;

            sb.append(' ');
            return sb.toString();
        }
    }


    private static ParagraphIR parseParagraph(Element pEl, OdtStyleResolver styles, String listPrefix) {
        String pStyleName = attr(pEl, TEXT_NS, "style-name");

        StyleIR pStyle = styles.resolveParagraphStyle(pStyleName);

        ParagraphIR p = new ParagraphIR();
        p.setStyle(pStyle == null ? new StyleIR() : pStyle);

        StringBuilder plain = new StringBuilder(128);

        StyleIR textFromName = styles.resolveTextStyle(pStyleName);
        StyleIR baseText = StyleIR.merge(textFromName, p.getStyle());
        if (baseText == null) baseText = (p.getStyle() == null ? new StyleIR() : p.getStyle());

        if (listPrefix != null && !listPrefix.isBlank()) {
            plain.append(listPrefix);
            p.addRun(new TextRunIR(listPrefix, baseText));
        }

        appendRunsDeep(pEl, p, plain, styles, baseText);

        String out = normalizePlainText(plain.toString());
        p.setText(out);


        if (p.getRuns() == null || p.getRuns().isEmpty()) {
            p.addRun(new TextRunIR(out, baseText));
        }

        return p;
    }


    private static void appendRunsDeep(
            Node node,
            ParagraphIR target,
            StringBuilder plain,
            OdtStyleResolver styles,
            StyleIR currentTextStyle
    ) {
        if (node == null) return;

        short t = node.getNodeType();

        if (t == Node.TEXT_NODE) {
            String s = node.getNodeValue();
            if (s != null && !s.isEmpty()) {
                plain.append(s);
                target.addRun(new TextRunIR(s, currentTextStyle));
            }
            return;
        }

        if (is(node, TEXT_NS, "line-break")) {
            plain.append('\n');
            target.addRun(new TextRunIR("\n", currentTextStyle));
            return;
        }

        if (is(node, TEXT_NS, "s")) {
            int c = intAttr((Element) node, TEXT_NS, "c", 1);
            String spaces = " ".repeat(Math.max(1, c));
            plain.append(spaces);
            target.addRun(new TextRunIR(spaces, currentTextStyle));
            return;
        }

        if (is(node, TEXT_NS, "span")) {
            Element span = (Element) node;
            String spanStyleName = attr(span, TEXT_NS, "style-name");

            StyleIR spanText = styles.resolveTextStyle(spanStyleName);
            StyleIR merged = StyleIR.merge(currentTextStyle, spanText);

            NodeList kids = span.getChildNodes();
            for (int i = 0; i < kids.getLength(); i++) {
                appendRunsDeep(kids.item(i), target, plain, styles, merged);
            }
            return;
        }

        if (is(node, TEXT_NS, "a")) {
            NodeList kids = node.getChildNodes();
            for (int i = 0; i < kids.getLength(); i++) {
                appendRunsDeep(kids.item(i), target, plain, styles, currentTextStyle);
            }
            return;
        }

        NodeList kids = node.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            appendRunsDeep(kids.item(i), target, plain, styles, currentTextStyle);
        }
    }

    private static TableIR parseTable(Element tableEl, OdtStyleResolver styles) {
        TableIR t = new TableIR();

        NodeList kids = tableEl.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node n = kids.item(i);
            if (!is(n, TABLE_NS, "table-row")) continue;

            Element rowEl = (Element) n;
            RowIR row = new RowIR();

            NodeList rowKids = rowEl.getChildNodes();
            for (int j = 0; j < rowKids.getLength(); j++) {
                Node cn = rowKids.item(j);
                if (!is(cn, TABLE_NS, "table-cell")) continue;

                Element cellEl = (Element) cn;

                String cellStyleName = attr(cellEl, TABLE_NS, "style-name");
                StyleIR cellStyle = styles.resolveTableCellStyle(cellStyleName);
                if (cellStyle == null) cellStyle = new StyleIR();

                String cellText = extractCellText(cellEl, styles);
                cellText = normalizePlainText(cellText);

                if (cellStyle.getAlign() == null) {
                    String pAlign = extractFirstParagraphAlign(cellEl, styles);
                    if (pAlign != null) {
                        cellStyle.setAlign(pAlign);
                    }
                }

                CellIR cell = new CellIR(cellText);
                cell.setStyle(cellStyle);

                int cs = intAttr(cellEl, TABLE_NS, "number-columns-spanned", 1);
                int rs = intAttr(cellEl, TABLE_NS, "number-rows-spanned", 1);
                if (cs > 1) cell.setColSpan(cs);
                if (rs > 1) cell.setRowSpan(rs);

                row.addCell(cell);
            }

            t.addRow(row);
        }

        return t;
    }

    private static String extractFirstParagraphAlign(Element cellEl, OdtStyleResolver styles) {
        if (cellEl == null) return null;

        NodeList kids = cellEl.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node n = kids.item(i);
            if (!is(n, TEXT_NS, "p")) continue;

            Element pEl = (Element) n;
            String pStyleName = attr(pEl, TEXT_NS, "style-name");
            StyleIR pStyle = styles.resolveParagraphStyle(pStyleName);
            if (pStyle == null) return null;

            String a = pStyle.getAlign();
            return (a == null || a.isBlank()) ? null : a.trim();
        }
        return null;
    }


    private static String extractCellText(Element cellEl, OdtStyleResolver styles) {
        StringBuilder sb = new StringBuilder(128);

        NodeList kids = cellEl.getChildNodes();
        boolean firstP = true;

        for (int i = 0; i < kids.getLength(); i++) {
            Node n = kids.item(i);
            if (!is(n, TEXT_NS, "p")) continue;

            Element pEl = (Element) n;

            ParagraphIR p = parseParagraph(pEl, styles, null);
            if (p == null) continue;

            if (!firstP) sb.append('\n');
            firstP = false;

            sb.append(p.getText() == null ? "" : p.getText());
        }

        return sb.toString();
    }


    private static String normalizePlainText(String s) {
        if (s == null) return "";
        String out = s
                .replace('\u00A0', ' ')
                .replace("\r\n", "\n").replace("\r", "\n")
                .replaceAll("[ \\t\\x0B\\f]+", " ")
                .trim();
        return out;
    }

    private static boolean is(Node n, String ns, String local) {
        return n != null
                && n.getNodeType() == Node.ELEMENT_NODE
                && ns.equals(n.getNamespaceURI())
                && local.equals(n.getLocalName());
    }

    private static String attr(Element el, String ns, String local) {
        if (el == null) return null;
        String v = el.getAttributeNS(ns, local);
        if (v == null) return null;
        v = v.trim();
        return v.isEmpty() ? null : v;
    }

    private static int intAttr(Element el, String ns, String local, int def) {
        if (el == null) return def;
        String v = el.getAttributeNS(ns, local);
        if (v == null || v.isBlank()) return def;
        try {
            return Math.max(1, Integer.parseInt(v.trim()));
        } catch (Exception ignore) {
            return def;
        }
    }

    private static ImageIR findFirstImageInParagraph(Element pEl) {
        NodeList kids = pEl.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node n = kids.item(i);
            if (is(n, DRAW_NS, "frame")) {
                return parseImageFrame((Element) n);
            }
        }
        return null;
    }

    private static ImageIR parseImageFrame(Element frameEl) {
        if (frameEl == null) return null;

        if (!hasChild(frameEl, DRAW_NS, "image")) return null;

        String w = frameEl.getAttributeNS(SVG_NS, "width");
        String h = frameEl.getAttributeNS(SVG_NS, "height");

        Float wMm = parseLengthToMm(w);
        Float hMm = parseLengthToMm(h);

        ImageIR ir = new ImageIR();
        ir.setWidthMm(wMm);
        ir.setHeightMm(hMm);

        String anchor = frameEl.getAttributeNS(TEXT_NS, "anchor-type");
        if (anchor != null && !anchor.isBlank()) ir.setAnchorType(anchor.trim());

        String name = frameEl.getAttributeNS(DRAW_NS, "name"); // draw:name
        if (name != null && !name.isBlank()) ir.setName(name.trim());

        return ir;
    }

    private static boolean hasChild(Element parent, String ns, String local) {
        NodeList kids = parent.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node n = kids.item(i);
            if (is(n, ns, local)) return true;
        }
        return false;
    }

    private static Float parseLengthToMm(String s) {
        if (s == null) return null;
        String v = s.trim().toLowerCase(Locale.ROOT);
        if (v.isEmpty()) return null;

        try {
            if (v.endsWith("mm")) return Float.parseFloat(v.replace("mm", "").trim());
            if (v.endsWith("cm")) return Float.parseFloat(v.replace("cm", "").trim()) * 10f;
            if (v.endsWith("pt")) return Float.parseFloat(v.replace("pt", "").trim()) * (25.4f / 72f);
            if (v.endsWith("in")) return Float.parseFloat(v.replace("in", "").trim()) * 25.4f;
            return Float.parseFloat(v);
        } catch (Exception ignore) {
            return null;
        }
    }
    private static boolean paragraphHasMeaningfulText(Element pEl) {
        if (pEl == null) return false;

        NodeList kids = pEl.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node n = kids.item(i);

            if (n.getNodeType() == Node.TEXT_NODE) {
                String s = n.getNodeValue();
                if (s != null && !s.trim().isEmpty()) return true;
            }

            if (is(n, TEXT_NS, "s")) continue;

            if (is(n, TEXT_NS, "span")) {
                if (paragraphHasMeaningfulText((Element) n)) return true;
            }
        }
        return false;
    }


}
