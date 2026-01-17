package ru.rea.report.odf;

import org.w3c.dom.*;
import ru.rea.report.ir.PageIR;
import ru.rea.report.ir.StyleIR;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

final class OdtStyleResolver {

    static final String OFFICE_NS = "urn:oasis:names:tc:opendocument:xmlns:office:1.0";
    static final String STYLE_NS  = "urn:oasis:names:tc:opendocument:xmlns:style:1.0";
    static final String FO_NS     = "urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0";
    static final String TEXT_NS   = "urn:oasis:names:tc:opendocument:xmlns:text:1.0";
    static final String TABLE_NS  = "urn:oasis:names:tc:opendocument:xmlns:table:1.0";

    private final Document contentDom;
    private final Document stylesDom;

    private final Map<String, StyleIR> cache = new HashMap<>();

    OdtStyleResolver(Document contentDom, Document stylesDom) {
        this.contentDom = contentDom;
        this.stylesDom = stylesDom;
    }

    StyleIR resolveParagraphStyle(String styleName) {
        return resolve(styleName, "paragraph");
    }

    StyleIR resolveTextStyle(String styleName) {
        return resolve(styleName, "text");
    }

    StyleIR resolveTableCellStyle(String styleName) {
        return resolve(styleName, "table-cell");
    }


    private StyleIR resolve(String styleName, String family) {
        if (styleName == null || styleName.isBlank()) return null;

        String key = family + "::" + styleName.trim();
        if (cache.containsKey(key)) return cache.get(key);

        Element styleEl = findStyleByNameAndFamily(styleName.trim(), family);
        if (styleEl == null) {
            cache.put(key, null);
            return null;
        }

        String parent = attr(styleEl, STYLE_NS, "parent-style-name");
        StyleIR base = null;
        if (parent != null && !parent.isBlank()) {
            base = resolve(parent.trim(), family);
        }

        StyleIR cur = parseStyleElement(styleEl, family);
        StyleIR out = StyleIR.merge(base, cur);

        if (out.getAlign() != null) {
            String a = out.getAlign().trim().toLowerCase(Locale.ROOT);
            if ("start".equals(a)) out.setAlign("left");
            if ("end".equals(a)) out.setAlign("right");
        }

        cache.put(key, out);
        return out;
    }

    private StyleIR parseStyleElement(Element styleEl, String family) {
        StyleIR st = new StyleIR();

        Element pp = firstChild(styleEl, STYLE_NS, "paragraph-properties");
        if (pp != null) {
            st.setAlign(nonBlank(pp.getAttributeNS(FO_NS, "text-align")));
            st.setMarginTopPt(parseFoSizeToPt(pp.getAttributeNS(FO_NS, "margin-top")));
            st.setMarginBottomPt(parseFoSizeToPt(pp.getAttributeNS(FO_NS, "margin-bottom")));
            st.setMarginLeftPt(parseFoSizeToPt(pp.getAttributeNS(FO_NS, "margin-left")));
            st.setMarginRightPt(parseFoSizeToPt(pp.getAttributeNS(FO_NS, "margin-right")));
            st.setTextIndentPt(parseFoSizeToPt(pp.getAttributeNS(FO_NS, "text-indent")));

            String lh = nonBlank(pp.getAttributeNS(FO_NS, "line-height"));
            if (lh != null) {
                Integer perc = parsePercentInt(lh);
                if (perc != null) st.setLineHeightPercent(perc);
                else st.setLineHeightPt(parseFoSizeToPt(lh));
            }

            String bg = nonBlank(pp.getAttributeNS(FO_NS, "background-color"));
            if (bg != null && !"transparent".equalsIgnoreCase(bg)) st.setBackgroundColor(bg);
        }

        Element tp = firstChild(styleEl, STYLE_NS, "text-properties");
        if (tp != null) {
            String color = nonBlank(tp.getAttributeNS(FO_NS, "color"));
            if (color != null) st.setFontColor(color);

            String fsz = nonBlank(tp.getAttributeNS(FO_NS, "font-size"));
            Integer pt = parseFoSizeToPtInt(fsz);
            if (pt != null) st.setFontSizePt(pt);

            String fw = nonBlank(tp.getAttributeNS(FO_NS, "font-weight"));
            if (fw != null) {
                String v = fw.trim().toLowerCase(Locale.ROOT);
                if ("bold".equals(v) || "700".equals(v) || "800".equals(v) || "900".equals(v)) st.setBold(true);
            }

            String fs = nonBlank(tp.getAttributeNS(FO_NS, "font-style"));
            if (fs != null) {
                String v = fs.trim().toLowerCase(Locale.ROOT);
                if ("italic".equals(v) || "oblique".equals(v)) st.setItalic(true);
            }

            String ul = nonBlank(tp.getAttributeNS(STYLE_NS, "text-underline-style"));
            if (ul != null && !"none".equalsIgnoreCase(ul)) st.setUnderline(true);

            String ff = nonBlank(tp.getAttributeNS(FO_NS, "font-family"));
            if (ff != null) st.setFontFamily(stripQuotes(ff));
        }

        Element cp = firstChild(styleEl, STYLE_NS, "table-cell-properties");
        if (cp != null) {
            String bg = nonBlank(cp.getAttributeNS(FO_NS, "background-color"));
            if (bg != null && !"transparent".equalsIgnoreCase(bg)) st.setBackgroundColor(bg);

            st.setPaddingTopPt(parseFoSizeToPt(cp.getAttributeNS(FO_NS, "padding-top")));
            st.setPaddingBottomPt(parseFoSizeToPt(cp.getAttributeNS(FO_NS, "padding-bottom")));
            st.setPaddingLeftPt(parseFoSizeToPt(cp.getAttributeNS(FO_NS, "padding-left")));
            st.setPaddingRightPt(parseFoSizeToPt(cp.getAttributeNS(FO_NS, "padding-right")));

            BorderSpec all = parseFoBorder(cp.getAttributeNS(FO_NS, "border"));
            BorderSpec top = parseFoBorder(cp.getAttributeNS(FO_NS, "border-top"));
            BorderSpec bottom = parseFoBorder(cp.getAttributeNS(FO_NS, "border-bottom"));
            BorderSpec left = parseFoBorder(cp.getAttributeNS(FO_NS, "border-left"));
            BorderSpec right = parseFoBorder(cp.getAttributeNS(FO_NS, "border-right"));

            applyBorderSpec(st, all, all, all, all);
            applyBorderSpec(st, top, bottom, left, right);
        }

        return st;
    }

    private Element findStyleByNameAndFamily(String name, String family) {
        Element e = findInDoc(contentDom, name, family);
        if (e != null) return e;

        e = findInDoc(stylesDom, name, family);
        return e;
    }

    private Element findInDoc(Document doc, String name, String family) {
        if (doc == null) return null;

        Element root = doc.getDocumentElement();
        if (root == null) return null;

        Element auto = firstDescendant(root, OFFICE_NS, "automatic-styles");
        Element found = findStyleInContainer(auto, name, family);
        if (found != null) return found;

        Element styles = firstDescendant(root, OFFICE_NS, "styles");
        return findStyleInContainer(styles, name, family);
    }

    private Element findStyleInContainer(Element container, String styleName, String family) {
        if (container == null) return null;
        NodeList kids = container.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node n = kids.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element e = (Element) n;

            if (!STYLE_NS.equals(e.getNamespaceURI())) continue;
            if (!"style".equals(e.getLocalName())) continue;

            String name = e.getAttributeNS(STYLE_NS, "name");
            if (!styleName.equals(name)) continue;

            String fam = e.getAttributeNS(STYLE_NS, "family");
            if (family.equals(fam)) return e;
        }
        return null;
    }

    private static Element firstChild(Element parent, String ns, String local) {
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

    private static Element firstDescendant(Element parent, String ns, String local) {
        if (parent == null) return null;
        NodeList all = parent.getElementsByTagNameNS(ns, local);
        if (all.getLength() == 0) return null;
        return (Element) all.item(0);
    }

    private static String attr(Element el, String ns, String local) {
        if (el == null) return null;
        String v = el.getAttributeNS(ns, local);
        return nonBlank(v);
    }

    private static String nonBlank(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static String stripQuotes(String s) {
        if (s == null) return null;
        String t = s.trim();
        if ((t.startsWith("\"") && t.endsWith("\"")) || (t.startsWith("'") && t.endsWith("'"))) {
            return t.substring(1, t.length() - 1).trim();
        }
        return t;
    }

    private static Integer parsePercentInt(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (!t.endsWith("%")) return null;
        try {
            return Integer.parseInt(t.substring(0, t.length() - 1).trim());
        } catch (Exception ignore) {
            return null;
        }
    }

    private static Float parseFoSizeToPt(String size) {
        if (size == null) return null;
        String s = size.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) return null;

        try {
            if (s.endsWith("pt")) return Float.parseFloat(s.substring(0, s.length() - 2).trim());
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

    private static BorderSpec parseFoBorder(String spec) {
        if (spec == null) return null;
        String s = spec.trim();
        if (s.isEmpty()) return null;
        if ("none".equalsIgnoreCase(s)) return new BorderSpec(false, 0f, null);

        String[] parts = s.split("\\s+");
        if (parts.length < 2) return null;

        Float w = parseFoSizeToPt(parts[0]);
        if (w == null) w = 0.75f;

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

    public PageIR resolvePage() {
        if (stylesDom == null) return null;

        try {
            Element masterPage = firstElementByTag(stylesDom, STYLE_NS, "master-page");
            if (masterPage == null) return null;

            String pageLayoutName = masterPage.getAttributeNS(STYLE_NS, "page-layout-name");
            if (pageLayoutName == null || pageLayoutName.isBlank()) return null;

            Element pageLayout = findByAttr(stylesDom, STYLE_NS, "page-layout", STYLE_NS, "name", pageLayoutName);
            if (pageLayout == null) return null;

            Element props = firstChildElement(pageLayout, STYLE_NS, "page-layout-properties");
            if (props == null) return null;

            PageIR p = new PageIR();

            Float w = parseMm(props.getAttributeNS(FO_NS, "page-width"));
            Float h = parseMm(props.getAttributeNS(FO_NS, "page-height"));

            Float mt = parseMm(props.getAttributeNS(FO_NS, "margin-top"));
            Float mb = parseMm(props.getAttributeNS(FO_NS, "margin-bottom"));
            Float ml = parseMm(props.getAttributeNS(FO_NS, "margin-left"));
            Float mr = parseMm(props.getAttributeNS(FO_NS, "margin-right"));

            if (w != null) p.setWidthMm(w);
            if (h != null) p.setHeightMm(h);
            if (mt != null) p.setMarginTopMm(mt);
            if (mb != null) p.setMarginBottomMm(mb);
            if (ml != null) p.setMarginLeftMm(ml);
            if (mr != null) p.setMarginRightMm(mr);

            if (w != null && h != null) {
                p.setOrientation(w > h ? "landscape" : "portrait");
            }

            return p;

        } catch (Exception e) {
            return null;
        }
    }

    private static Element firstElementByTag(Document dom, String ns, String local) {
        NodeList nl = dom.getElementsByTagNameNS(ns, local);
        if (nl == null || nl.getLength() == 0) return null;
        Node n = nl.item(0);
        return (n instanceof Element) ? (Element) n : null;
    }

    private static Element firstChildElement(Element parent, String ns, String local) {
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

    private static Element findByAttr(
            Document dom,
            String ns,
            String localName,
            String attrNs,
            String attrLocal,
            String attrValue
    ) {
        NodeList nl = dom.getElementsByTagNameNS(ns, localName);
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (!(n instanceof Element e)) continue;
            String v = e.getAttributeNS(attrNs, attrLocal);
            if (attrValue.equals(v)) return e;
        }
        return null;
    }

    private static Float parseMm(String s) {
        if (s == null) return null;
        String v = s.trim().toLowerCase(Locale.ROOT);
        if (v.isEmpty()) return null;

        try {
            if (v.endsWith("mm")) return Float.parseFloat(v.substring(0, v.length() - 2).trim());
            if (v.endsWith("cm")) return Float.parseFloat(v.substring(0, v.length() - 2).trim()) * 10f;
            if (v.endsWith("in")) return Float.parseFloat(v.substring(0, v.length() - 2).trim()) * 25.4f;
            if (v.endsWith("pt")) return Float.parseFloat(v.substring(0, v.length() - 2).trim()) * (25.4f / 72f);
            return Float.parseFloat(v);
        } catch (Exception ignore) {
            return null;
        }
    }


}
