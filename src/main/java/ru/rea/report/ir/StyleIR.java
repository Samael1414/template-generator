package ru.rea.report.ir;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.Accessors;

@Getter
@Setter
@NoArgsConstructor
@Accessors(chain = true)
public final class StyleIR {

    private boolean bold;
    private boolean italic;


    private String align;

    private Integer fontSizePt;

    private String backgroundColor;
    private String borderColor;
}
