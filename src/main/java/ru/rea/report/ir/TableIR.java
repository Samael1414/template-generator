package ru.rea.report.ir;

import lombok.Getter;
import lombok.experimental.Accessors;

import java.util.ArrayList;
import java.util.List;

@Getter
@Accessors(chain = true)
public final class TableIR implements BlockIR {

    private final List<RowIR> rows = new ArrayList<>();

    public TableIR addRow(RowIR row) {
        rows.add(row);
        return this;
    }

    public final class TableNormalizer {

        public static NormalizedTable normalize(TableIR table) {
            int rows = table.getRows().size();

            // оценим max cols как верхнюю границу (по "сырому" + запас), потом подрежем
            int roughCols = table.getRows().stream()
                    .mapToInt(r -> r.getCells() == null ? 0 : r.getCells().size())
                    .max().orElse(0) * 3; // запас, т.к. repeated/covered могут "съесть" колонки

            if (roughCols < 1) roughCols = 1;

            System.out.println("[TPLGEN][TABLE] normalize start rows=" + rows + " roughCols=" + roughCols);

            CellIR[][] m = new CellIR[rows][roughCols];
            boolean[][] occ = new boolean[rows][roughCols];

            int maxUsedCol = -1;

            for (int r = 0; r < rows; r++) {
                RowIR row = table.getRows().get(r);
                List<CellIR> src = row.getCells();
                if (src == null) src = List.of();

                int c = 0;
                for (CellIR srcCell : src) {
                    if (srcCell == null) srcCell = new CellIR("");

                    // найти первую свободную колонку в строке
                    while (c < roughCols && occ[r][c]) c++;
                    if (c >= roughCols) {
                        throw new IllegalStateException("Row " + r + ": exceeded roughCols=" + roughCols);
                    }

                    // поставить мастер
                    m[r][c] = srcCell;
                    srcCell.setCovered(false);

                    int cs = Math.max(srcCell.getColSpan(), 1);
                    int rs = Math.max(srcCell.getRowSpan(), 1);

                    System.out.println("[TPLGEN][TABLE] place master r=" + r + " c=" + c + " cs=" + cs + " rs=" + rs + " text=" + srcCell.getText().substring(0, Math.min(10, srcCell.getText().length())));

                    // ограничить
                    cs = Math.min(cs, roughCols - c);
                    rs = Math.min(rs, rows - r);

                    // отметить область как занятую (включая master)
                    for (int rr = r; rr < r + rs; rr++) {
                        for (int cc = c; cc < c + cs; cc++) {
                            if (occ[rr][cc] && !(rr == r && cc == c)) {
                                // пересечение merge-областей — это гарантированно "corrupted" для BIRT
                                System.err.println("[TPLGEN][TABLE] MERGE OVERLAP at r=" + rr + " c=" + cc + " (master r=" + r + " c=" + c + " cs=" + cs + " rs=" + rs + ")");
                                throw new IllegalStateException(
                                        "Merge overlap at r=" + rr + " c=" + cc +
                                                " (master r=" + r + " c=" + c + " cs=" + cs + " rs=" + rs + ")"
                                );
                            }
                            occ[rr][cc] = true;

                            if (rr == r && cc == c) continue;

                            // covered плейсхолдер
                            if (m[rr][cc] == null) m[rr][cc] = new CellIR("");
                            m[rr][cc].setCovered(true);
                            m[rr][cc].setText("");
                            m[rr][cc].setColSpan(1);
                            m[rr][cc].setRowSpan(1);
                        }
                    }

                    maxUsedCol = Math.max(maxUsedCol, c + cs - 1);
                }
            }

            int finalCols = maxUsedCol + 1;
            System.out.println("[TPLGEN][TABLE] normalize done rows=" + rows + " finalCols=" + finalCols);
            return new NormalizedTable(rows, finalCols, m);
        }


        public record NormalizedTable(int rows, int cols, CellIR[][] cells) {}
    }

}
