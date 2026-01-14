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
}
