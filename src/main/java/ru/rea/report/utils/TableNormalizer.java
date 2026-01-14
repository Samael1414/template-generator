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

        int lastRow = -1;
        int lastCol = -1;
        for (int r = 0; r < rows.size(); r++) {
            RowIR row = rows.get(r);
            if (row == null || row.getCells() == null) continue;

            for (int c = 0; c < row.getCells().size(); c++) {
                CellIR cell = row.getCells().get(c);
                if (cell == null) continue;

                boolean hasContent = !safe(cell.getText()).isBlank();
                boolean hasSpan = cell.getColSpan() > 1 || cell.getRowSpan() > 1;
                if (!hasContent && !hasSpan) {
                    continue;
                }

                lastRow = Math.max(lastRow, r + cell.getRowSpan() - 1);
                lastCol = Math.max(lastCol, c + cell.getColSpan() - 1);
            }
        }
        if (lastRow == -1 || lastCol == -1) return;

        rows.subList(lastRow + 1, rows.size()).clear();

        for (RowIR row : rows) {
            if (row.getCells() == null) continue;
            if (lastCol + 1 < row.getCells().size()) {
                row.getCells().subList(lastCol + 1, row.getCells().size()).clear();
            }
        }
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
