package ru.rea.report.odf;

import org.odftoolkit.odfdom.doc.OdfSpreadsheetDocument;
import org.odftoolkit.odfdom.doc.table.OdfTable;
import org.odftoolkit.odfdom.doc.table.OdfTableCell;
import org.odftoolkit.odfdom.doc.table.OdfTableRow;
import org.springframework.stereotype.Component;
import org.w3c.dom.Element;
import ru.rea.report.exception.BadTemplateException;
import ru.rea.report.ir.*;

import java.io.InputStream;
import java.util.List;

@Component
public class OdsReader {

    private static final int HARD_MAX_ROWS = 250;
    private static final int HARD_MAX_COLS = 80;

    private static final String TABLE_NS = "urn:oasis:names:tc:opendocument:xmlns:table:1.0";

    public TemplateDocumentIR read(InputStream in) {
        try {
            OdfSpreadsheetDocument doc = OdfSpreadsheetDocument.loadDocument(in);
            List<OdfTable> tables = doc.getTableList();
            if (tables.isEmpty()) throw new BadTemplateException("ODS contains no sheets/tables");

            OdfTable sheet = tables.get(0);

            int scanRows = Math.min(sheet.getRowCount(), HARD_MAX_ROWS);
            int scanCols = Math.min(sheet.getColumnCount(), HARD_MAX_COLS);

            int lastRow = -1;
            int lastCol = -1;

            // 1) Сканируем видимый контент и ВАЖНО: учитываем colSpan/rowSpan мастер-ячейки
            for (int r = 0; r < scanRows; r++) {
                OdfTableRow row = sheet.getRowByIndex(r);
                boolean rowHasData = false;

                for (int c = 0; c < scanCols; c++) {
                    OdfTableCell cell = row.getCellByIndex(c);
                    if (cell == null) continue;

                    if (isCoveredCell(cell)) continue; // covered - это не мастер

                    String text = fastCellText(cell);
                    if (text != null && !text.isBlank()) {
                        rowHasData = true;

                        int cs = colSpanOf(cell);
                        int rs = rowSpanOf(cell);

                        // границы таблицы должны включать весь merge-диапазон
                        lastCol = Math.max(lastCol, c + cs - 1);
                        lastRow = Math.max(lastRow, r + rs - 1);
                    }
                }
                if (rowHasData) lastRow = Math.max(lastRow, r);
            }

            if (lastRow < 0 || lastCol < 0) {
                throw new BadTemplateException("ODS sheet is empty (no visible content found)");
            }

            // Ограничиваем тем, что реально можем обработать
            lastRow = Math.min(lastRow, HARD_MAX_ROWS - 1);
            lastCol = Math.min(lastCol, HARD_MAX_COLS - 1);

            int rows = lastRow + 1;
            int cols = lastCol + 1;

            TableIR tableIR = new TableIR();

            // 2) Читаем матрицу целиком до вычисленных границ
            for (int r = 0; r < rows; r++) {
                OdfTableRow row = sheet.getRowByIndex(r);
                RowIR rowIR = new RowIR();

                for (int c = 0; c < cols; c++) {
                    OdfTableCell cell = row.getCellByIndex(c);
                    boolean covered = isCoveredCell(cell);

                    // covered ячейки НЕ добавляем в строку - они должны быть частью span'ов
                    if (covered) {
                        continue; // пропускаем, master создаст span автоматически
                    }

                    CellIR cellIR = new CellIR(fastCellText(cell));

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
        try {
            String s = cell.getStringValue();
            if (s != null && !s.isBlank()) return s;
        } catch (Throwable ignore) { }
        try {
            String s = cell.getDisplayText();
            return (s == null ? "" : s);
        } catch (Throwable ignore) {
            return "";
        }
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
        Element el = (Element) cell.getOdfElement();
        return getIntAttr(el, TABLE_NS, "number-columns-spanned", 1);
    }

    private static int rowSpanOf(OdfTableCell cell) {
        Element el = (Element) cell.getOdfElement();
        return getIntAttr(el, TABLE_NS, "number-rows-spanned", 1);
    }

    private static boolean isCoveredCell(OdfTableCell cell) {
        if (cell == null) return false;
        Element el = (Element) cell.getOdfElement();
        if (el == null) return false;

        String ln = el.getLocalName();
        if ("covered-table-cell".equals(ln)) return true;

        // fallback: иногда попадается qName
        String tag = el.getTagName(); // например "table:covered-table-cell"
        return tag != null && tag.endsWith("covered-table-cell");
    }

}
