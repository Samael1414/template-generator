package ru.rea.report.ir;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.Accessors;

@Getter
@Setter
@NoArgsConstructor
@Accessors(chain = true)
public final class TextRunIR {
    private String text;
    private StyleIR style = new StyleIR();

    public TextRunIR(String text, StyleIR style) {
        this.text = text;
        this.style = (style == null ? new StyleIR() : style);
    }
}
