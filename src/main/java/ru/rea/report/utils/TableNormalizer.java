package ru.rea.report.utils;

import ru.rea.report.ir.*;

import java.util.List;

public final class TableNormalizer {

    private TableNormalizer() {}

    public static void trimAllTables(TemplateDocumentIR ir) {
        if (ir == null || ir.getBlocks() == null) return;

        for (BlockIR b : ir.getBlocks()) {
            if (b instanceof TableIR t) {
                trim(t);
            }
        }
    }

    public static void trim(TableIR table) {
        List<RowIR> rows = table.getRows();
        if (rows == null || rows.isEmpty()) return;

        // 1) последняя непустая строка
        int lastRow = -1;
        for (int r = 0; r < rows.size(); r++) {
            if (!isRowEmpty(rows.get(r))) lastRow = r;
        }
        if (lastRow == -1) return; // вся таблица пустая

        // 2) последняя непустая колонка
        int lastCol = -1;
        for (int r = 0; r <= lastRow; r++) {
            RowIR row = rows.get(r);
            if (row.getCells() == null) continue;

            for (int c = 0; c < row.getCells().size(); c++) {
                String t = safe(row.getCells().get(c).getText());
                if (!t.isBlank()) lastCol = Math.max(lastCol, c);
            }
        }

        // 3) обрезаем строки (хвост)
        rows.subList(lastRow + 1, rows.size()).clear();

        // 4) обрезаем колонки в каждой строке
        if (lastCol >= 0) {
            for (RowIR row : rows) {
                if (row.getCells() == null) continue;
                if (lastCol + 1 < row.getCells().size()) {
                    row.getCells().subList(lastCol + 1, row.getCells().size()).clear();
                }
            }
        }
    }

    private static boolean isRowEmpty(RowIR row) {
        if (row == null || row.getCells() == null) return true;
        for (CellIR cell : row.getCells()) {
            if (!safe(cell.getText()).isBlank()) return false;
        }
        return true;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}