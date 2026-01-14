package ru.rea.report.ir;

import lombok.Getter;
import lombok.experimental.Accessors;

import java.util.ArrayList;
import java.util.List;

@Getter
@Accessors(chain = true)
public final class RowIR {

    private final List<CellIR> cells = new ArrayList<>();

    public RowIR addCell(CellIR cell) {
        cells.add(cell);
        return this;
    }
}
