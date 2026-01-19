package ru.rea.report.birt;

import org.w3c.dom.*;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public final class RptDesignSanitizer {
    private RptDesignSanitizer() {}

    public static final class Options {

        public boolean forceDropAllForCoveredCells = false;

        public String forceReportVersion = null;

        public boolean fixRowCellCount = true;

        public boolean compactRowsRemoveCoveredCells = true;

    }

    public static void sanitize(Path rptDesign, Options opt) {
        Objects.requireNonNull(rptDesign, "rptDesign");
        if (opt == null) opt = new Options();

        try {
            Document doc = parse(rptDesign);

            if (opt.forceReportVersion != null && !opt.forceReportVersion.isBlank()) {
                Element root = doc.getDocumentElement();
                if ("report".equals(root.getLocalName())) {
                    root.setAttribute("version", opt.forceReportVersion.trim());
                }
            }

            String ns = doc.getDocumentElement().getNamespaceURI();
            NodeList grids = doc.getElementsByTagNameNS(ns, "grid");
            for (int gi = 0; gi < grids.getLength(); gi++) {
                Element gridEl = (Element) grids.item(gi);
                sanitizeGrid(doc, gridEl, ns, opt);
            }

            if (opt.fixRowCellCount) {
                fixGridRowCellCounts(doc, ns);
            }

            write(doc, rptDesign);

        } catch (Exception e) {
            throw new IllegalStateException("Failed to sanitize rptdesign: " + rptDesign, e);
        }
    }

    private static void fixGridRowCellCounts(Document doc, String ns) {
        NodeList grids = doc.getElementsByTagNameNS(ns, "grid");
        for (int gi = 0; gi < grids.getLength(); gi++) {
            Element gridEl = (Element) grids.item(gi);

            int cols = countDirect(gridEl, ns, "column");
            if (cols <= 0) continue;

            NodeList children = gridEl.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                Node n = children.item(i);
                if (n.getNodeType() != Node.ELEMENT_NODE) continue;

                Element rowEl = (Element) n;
                if (!ns.equals(rowEl.getNamespaceURI())) continue;
                if (!"row".equals(rowEl.getLocalName())) continue;

                // дальше тело твоего fixGridRowCellCounts без изменений,
                // только newDropCell(doc, ns) и getIntProperty(..., ns, ...)
            }
        }
    }



    private static void sanitizeGrid(Document doc, Element gridEl, String ns, Options opt) {
        int cols = countDirect(gridEl, ns, "column");
        if (cols <= 0) return;

        List<Element> rows = directChildren(gridEl, ns, "row");
        if (rows.isEmpty()) return;

        if (opt.fixRowCellCount) {
            for (Element rowEl : rows) {
                List<Element> cells = directChildren(rowEl, ns, "cell");
                if (cells.size() == cols) continue;

                if (cells.size() > cols) {
                    for (int i = cells.size() - 1; i >= cols; i--) {
                        rowEl.removeChild(cells.get(i));
                    }
                } else {
                    for (int i = cells.size(); i < cols; i++) {
                        Element cell = doc.createElementNS(ns, "cell");
                        cell.setAttribute("id", nextSyntheticId(doc, "cell"));
                        rowEl.appendChild(cell);
                    }
                }
            }
        }

        rows = directChildren(gridEl, ns, "row");

        int rCount = rows.size();
        Element[][] cellAt = new Element[rCount][cols];
        for (int r = 0; r < rCount; r++) {
            List<Element> cells = directChildren(rows.get(r), ns, "cell");
            for (int c = 0; c < cols && c < cells.size(); c++) {
                cellAt[r][c] = cells.get(c);
            }
        }

        int[][] masterR = new int[rCount][cols];
        int[][] masterC = new int[rCount][cols];
        for (int r = 0; r < rCount; r++) {
            Arrays.fill(masterR[r], -1);
            Arrays.fill(masterC[r], -1);
        }

        for (int r = 0; r < rCount; r++) {
            for (int c = 0; c < cols; c++) {
                Element cell = cellAt[r][c];
                if (cell == null) continue;

                int cs = getIntProperty(cell, ns, "colSpan", 1);
                int rs = getIntProperty(cell, ns, "rowSpan", 1);

                cs = Math.max(cs, 1);
                rs = Math.max(rs, 1);

                if (cs == 1 && rs == 1) continue;

                int maxR = Math.min(r + rs, rCount);
                int maxC = Math.min(c + cs, cols);

                for (int rr = r; rr < maxR; rr++) {
                    for (int cc = c; cc < maxC; cc++) {
                        masterR[rr][cc] = r;
                        masterC[rr][cc] = c;
                    }
                }
            }
        }

        if (opt.compactRowsRemoveCoveredCells) {
            for (int r = 0; r < rCount; r++) {
                Element rowEl = rows.get(r);

                List<Element> cells = directChildren(rowEl, ns, "cell");

                if (cells.size() < cols) continue;

                Element[] byCol = new Element[cols];
                for (int c = 0; c < cols; c++) byCol[c] = cells.get(c);

                for (Element ce : cells) rowEl.removeChild(ce);

                for (int c = 0; c < cols; c++) {
                    int mr = masterR[r][c];
                    int mc = masterC[r][c];

                    boolean covered = (mr >= 0 && mc >= 0 && !(mr == r && mc == c));
                    if (covered) continue;

                    Element cell = byCol[c];
                    if (cell != null) rowEl.appendChild(cell);
                }
            }
            return;
        }

        for (int r = 0; r < rCount; r++) {
            for (int c = 0; c < cols; c++) {
                Element cell = cellAt[r][c];
                if (cell == null) continue;

                int mr = masterR[r][c];
                int mc = masterC[r][c];

                boolean isCovered = (mr >= 0 && mc >= 0 && !(mr == r && mc == c));
                if (!isCovered) {
                    removeProperty(cell, ns, "drop");
                    continue;
                }

                removeProperty(cell, ns, "colSpan");
                removeProperty(cell, ns, "rowSpan");

                removeAllContentElements(cell, ns);

                if (opt.forceDropAllForCoveredCells) {
                    setOrReplaceProperty(cell, ns, "drop", "all");
                } else {
                    removeProperty(cell, ns, "drop");
                }
            }
        }
    }

    private static Document parse(Path p) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        try (InputStream in = Files.newInputStream(p)) {
            return dbf.newDocumentBuilder().parse(in);
        }
    }

    private static void write(Document doc, Path p) throws Exception {
        TransformerFactory tf = TransformerFactory.newInstance();
        Transformer t = tf.newTransformer();
        t.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        t.setOutputProperty(OutputKeys.INDENT, "yes");
        try (OutputStream out = Files.newOutputStream(p)) {
            t.transform(new DOMSource(doc), new StreamResult(out));
        }
    }

    private static int countDirect(Element parent, String ns, String localName) {
        int cnt = 0;
        NodeList kids = parent.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node n = kids.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element e = (Element) n;
            if (!ns.equals(e.getNamespaceURI())) continue;
            if (localName.equals(e.getLocalName())) cnt++;
        }
        return cnt;
    }

    private static List<Element> directChildren(Element parent, String ns, String localName) {
        List<Element> out = new ArrayList<>();
        NodeList kids = parent.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node n = kids.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element e = (Element) n;
            if (!ns.equals(e.getNamespaceURI())) continue;
            if (localName.equals(e.getLocalName())) out.add(e);
        }
        return out;
    }

    private static int getIntProperty(Element cell, String ns, String name, int def) {
        NodeList kids = cell.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node n = kids.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element e = (Element) n;
            if (!ns.equals(e.getNamespaceURI())) continue;
            if (!"property".equals(e.getLocalName())) continue;
            if (!name.equals(e.getAttribute("name"))) continue;
            try {
                return Integer.parseInt(e.getTextContent().trim());
            } catch (Exception ignore) {
                return def;
            }
        }
        return def;
    }

    private static void removeProperty(Element cell, String ns, String name) {
        NodeList kids = cell.getChildNodes();
        List<Node> del = new ArrayList<>();
        for (int i = 0; i < kids.getLength(); i++) {
            Node n = kids.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element e = (Element) n;
            if (!ns.equals(e.getNamespaceURI())) continue;
            if (!"property".equals(e.getLocalName())) continue;
            if (!name.equals(e.getAttribute("name"))) continue;
            del.add(e);
        }
        for (Node n : del) cell.removeChild(n);
    }

    private static void setOrReplaceProperty(Element cell, String ns, String name, String value) {
        removeProperty(cell, ns, name);
        Document doc = cell.getOwnerDocument();
        Element prop = doc.createElementNS(ns, "property");
        prop.setAttribute("name", name);
        prop.setTextContent(value);
        cell.appendChild(prop);
    }

    private static void removeAllContentElements(Element cell, String ns) {
        NodeList kids = cell.getChildNodes();
        List<Node> del = new ArrayList<>();
        for (int i = 0; i < kids.getLength(); i++) {
            Node n = kids.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element e = (Element) n;
            if (!ns.equals(e.getNamespaceURI())) continue;
            String ln = e.getLocalName();
            if ("text".equals(ln) || "text-data".equals(ln) || "grid".equals(ln) || "image".equals(ln)) {
                del.add(e);
            }
        }
        for (Node n : del) cell.removeChild(n);
    }

    private static String nextSyntheticId(Document doc, String prefix) {
        NodeList all = doc.getElementsByTagNameNS(doc.getDocumentElement().getNamespaceURI(), "*");
        long max = 0;
        for (int i = 0; i < all.getLength(); i++) {
            Node n = all.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element e = (Element) n;
            String id = e.getAttribute("id");
            if (id == null || id.isBlank()) continue;
            try {
                max = Math.max(max, Long.parseLong(id.trim()));
            } catch (Exception ignore) {}
        }
        return String.valueOf(max + 1);
    }
}
