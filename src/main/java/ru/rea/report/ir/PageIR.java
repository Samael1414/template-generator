package ru.rea.report.ir;

import lombok.Data;

@Data
public class PageIR {
    private Float widthMm;
    private Float heightMm;
    private String orientation;
    private Float marginTopMm;
    private Float marginLeftMm;
    private Float marginBottomMm;
    private Float marginRightMm;
}
