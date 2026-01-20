package ru.rea.report.ir;

import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

@Getter
@Setter
@Accessors(chain = true)
public final class ImageIR implements BlockIR {
    private Float widthMm;
    private Float heightMm;
    private String anchorType;
    private String name;
}
