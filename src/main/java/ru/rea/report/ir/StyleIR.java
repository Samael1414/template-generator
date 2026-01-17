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

    private String backgroundColor;
    private String fontColor;
    private String borderColor;
    private String align;
    private String fontFamily;

    private Integer fontSizePt;
    private boolean bold;
    private boolean italic;
    private boolean underline;

    private Float borderTopWidthPt;
    private Float borderBottomWidthPt;
    private Float borderLeftWidthPt;
    private Float borderRightWidthPt;
    private boolean border;

    private Float marginTopPt;
    private Float marginBottomPt;
    private Float marginLeftPt;
    private Float marginRightPt;
    private Float textIndentPt;
    private Float lineHeightPt;
    private Integer lineHeightPercent;

    private Float paddingTopPt;
    private Float paddingBottomPt;
    private Float paddingLeftPt;
    private Float paddingRightPt;

    public static StyleIR merge(StyleIR base, StyleIR override) {
        StyleIR out = new StyleIR();
        if (base != null) copyInto(out, base);
        if (override != null) copyInto(out, override);
        return out;
    }

    private static void copyInto(StyleIR dst, StyleIR src) {
        if (src.backgroundColor != null) dst.backgroundColor = src.backgroundColor;
        if (src.fontColor != null) dst.fontColor = src.fontColor;
        if (src.borderColor != null) dst.borderColor = src.borderColor;
        if (src.align != null) dst.align = src.align;
        if (src.fontFamily != null) dst.fontFamily = src.fontFamily;

        if (src.fontSizePt != null) dst.fontSizePt = src.fontSizePt;
        if (src.bold) dst.bold = true;
        if (src.italic) dst.italic = true;
        if (src.underline) dst.underline = true;

        if (src.borderTopWidthPt != null) dst.borderTopWidthPt = src.borderTopWidthPt;
        if (src.borderBottomWidthPt != null) dst.borderBottomWidthPt = src.borderBottomWidthPt;
        if (src.borderLeftWidthPt != null) dst.borderLeftWidthPt = src.borderLeftWidthPt;
        if (src.borderRightWidthPt != null) dst.borderRightWidthPt = src.borderRightWidthPt;
        if (src.border) dst.border = true;

        if (src.marginTopPt != null) dst.marginTopPt = src.marginTopPt;
        if (src.marginBottomPt != null) dst.marginBottomPt = src.marginBottomPt;
        if (src.marginLeftPt != null) dst.marginLeftPt = src.marginLeftPt;
        if (src.marginRightPt != null) dst.marginRightPt = src.marginRightPt;
        if (src.textIndentPt != null) dst.textIndentPt = src.textIndentPt;

        if (src.lineHeightPt != null) dst.lineHeightPt = src.lineHeightPt;
        if (src.lineHeightPercent != null) dst.lineHeightPercent = src.lineHeightPercent;

        if (src.paddingTopPt != null) dst.paddingTopPt = src.paddingTopPt;
        if (src.paddingBottomPt != null) dst.paddingBottomPt = src.paddingBottomPt;
        if (src.paddingLeftPt != null) dst.paddingLeftPt = src.paddingLeftPt;
        if (src.paddingRightPt != null) dst.paddingRightPt = src.paddingRightPt;
    }
}
