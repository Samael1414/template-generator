package ru.rea.report.ir;

import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@Accessors(chain = true)
public final class TableIR implements BlockIR {

    private final List<RowIR> rows = new ArrayList<>();
    private List<Float> columnWidthsMm;
    private String sheetName;
    public TableIR addRow(RowIR row) {
        rows.add(row);
        return this;
    }

    public final class TableNormalizer {

        public static NormalizedTable normalize(TableIR table) {
            List<RowIR> rowsList = (table == null || table.getRows() == null) ? List.of() : table.getRows();
            int rows = rowsList.size();
            if (rows == 0) return new NormalizedTable(0, 0, new CellIR[0][0]);

            int cols = 0;
            for (RowIR r : rowsList) {
                int sz = (r == null || r.getCells() == null) ? 0 : r.getCells().size();
                cols = Math.max(cols, sz);
            }

            CellIR[][] m = new CellIR[rows][cols];
            for (int r = 0; r < rows; r++) {
                for (int c = 0; c < cols; c++) {
                    m[r][c] = new CellIR("");
                }
            }

            for (int r = 0; r < rows; r++) {
                RowIR row = rowsList.get(r);
                List<CellIR> cells = (row == null || row.getCells() == null) ? List.of() : row.getCells();
                int lim = Math.min(cells.size(), cols);

                for (int c = 0; c < lim; c++) {
                    CellIR src = cells.get(c);
                    if (src == null) continue;

                    CellIR dst = new CellIR(src.getText());
                    dst.setCovered(src.isCovered());

                    int cs = Math.max(src.getColSpan(), 1);
                    int rs = Math.max(src.getRowSpan(), 1);

                    cs = Math.min(cs, cols - c);
                    rs = Math.min(rs, rows - r);

                    dst.setColSpan(cs);
                    dst.setRowSpan(rs);
                    dst.setStyle(src.getStyle());

                    m[r][c] = dst;
                }
            }

            repairBrokenHorizontalMerges(m, rows, cols);

            for (int r = 0; r < rows; r++) {
                for (int c = 0; c < cols; c++) {
                    CellIR master = m[r][c];
                    if (master == null) continue;
                    if (master.isCovered()) continue;

                    int cs = Math.max(master.getColSpan(), 1);
                    int rs = Math.max(master.getRowSpan(), 1);
                    cs = Math.min(cs, cols - c);
                    rs = Math.min(rs, rows - r);

                    if (cs == 1 && rs == 1) continue;

                    for (int rr = r; rr < r + rs; rr++) {
                        for (int cc = c; cc < c + cs; cc++) {
                            if (rr == r && cc == c) continue;

                            // делаем coered плейсхолдер
                            CellIR cov = m[rr][cc];
                            if (cov == null) {
                                cov = new CellIR("");
                                m[rr][cc] = cov;
                            }
                            cov.setCovered(true);
                            cov.setText("");
                            cov.setColSpan(1);
                            cov.setRowSpan(1);
                            cov.setStyle(null);
                        }
                    }
                }
            }

            for (int r = 0; r < rows; r++) {
                for (int c = 0; c < cols; c++) {
                    if (m[r][c] == null) m[r][c] = new CellIR("");
                }
            }

            System.out.println("[TPLGEN][TABLE] normalize done rows=" + rows + " finalCols=" + cols);
            return new NormalizedTable(rows, cols, m);
        }

        private static void repairBrokenHorizontalMerges(CellIR[][] m, int rows, int cols) {
            for (int r = 0; r < rows; r++) {
                for (int c = 0; c < cols; c++) {
                    CellIR left = m[r][c];
                    if (left == null) continue;
                    if (left.isCovered()) continue;

                    if (left.getRowSpan() != 1) continue;
                    if (left.getColSpan() != 1) continue;
                    String lt = left.getText();
                    if (lt == null || lt.isBlank()) continue;

                    int spanAdd = 0;
                    int d = c + 1;

                    while (d < cols) {
                        CellIR nxt = m[r][d];
                        if (nxt == null) break;
                        if (nxt.isCovered()) break;
                        if (nxt.getRowSpan() != 1) break;

                        String nt = nxt.getText();
                        boolean empty = (nt == null || nt.isBlank());
                        int ncs = Math.max(nxt.getColSpan(), 1);

                        if (!empty || ncs <= 1) break;

                        int add = Math.min(ncs, cols - d);
                        spanAdd += add;

                        for (int k = 0; k < add; k++) {
                            int cc = d + k;
                            CellIR cov = m[r][cc];
                            if (cov == null) cov = new CellIR("");
                            cov.setCovered(true);
                            cov.setText("");
                            cov.setColSpan(1);
                            cov.setRowSpan(1);
                            cov.setStyle(null);
                            m[r][cc] = cov;
                        }

                        d += add;
                    }

                    if (spanAdd > 0) {
                        left.setColSpan(1 + spanAdd);
                    }
                }
            }
        }
        public record NormalizedTable(int rows, int cols, CellIR[][] cells) {}
    }

}
