package ru.rea.report.ir;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@Accessors(chain = true)
public final class ParagraphIR implements BlockIR {

    private String text;

    private StyleIR style = new StyleIR();

    private List<TextRunIR> runs = new ArrayList<>();

    public ParagraphIR(String text) {
        this.text = text;
    }

    public ParagraphIR addRun(TextRunIR run) {
        if (run != null) runs.add(run);
        return this;
    }
}
