package ru.rea.report.ir;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.Accessors;

@Getter
@Setter
@NoArgsConstructor
@Accessors(chain = true)
public final class ParagraphIR implements BlockIR {

    private String text;
    private StyleIR style = new StyleIR();

    public ParagraphIR(String text) {
        this.text = text;
    }
}
