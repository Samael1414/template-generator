package ru.rea.report.ir;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.Accessors;

@Getter
@Setter
@NoArgsConstructor
@Accessors(chain = true)
public final class CellIR {

    private String text;
    private int colSpan = 1;
    private int rowSpan = 1;
    private boolean covered = false;

    private StyleIR style = new StyleIR();

    public CellIR(String text) {
        this.text = text;
    }

    public CellIR setColSpan(int colSpan) {
        this.colSpan = Math.max(colSpan, 1);
        return this;
    }

    public CellIR setRowSpan(int rowSpan) {
        this.rowSpan = Math.max(rowSpan, 1);
        return this;
    }

    public CellIR setStyle(StyleIR style) {
        this.style = (style == null ? new StyleIR() : style);
        return this;
    }

    public boolean isCovered() {
        return covered;
    }

    public CellIR setCovered(boolean covered) {
        this.covered = covered;
        return this;
    }
}
