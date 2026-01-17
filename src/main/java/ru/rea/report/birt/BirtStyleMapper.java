package ru.rea.report.birt;

import org.eclipse.birt.report.model.api.DesignElementHandle;
import org.eclipse.birt.report.model.api.StyleHandle;
import org.eclipse.birt.report.model.api.activity.SemanticException;
import org.eclipse.birt.report.model.api.elements.DesignChoiceConstants;
import org.springframework.stereotype.Component;
import ru.rea.report.ir.StyleIR;

@Component
public class BirtStyleMapper {

    public void apply(DesignElementHandle handle, StyleIR style) {
        if (style == null || handle == null) return;

        try {
            if (style.isBold()) {
                handle.setProperty(StyleHandle.FONT_WEIGHT_PROP, DesignChoiceConstants.FONT_WEIGHT_BOLD);
            }
            if (style.isItalic()) {
                handle.setProperty(StyleHandle.FONT_STYLE_PROP, DesignChoiceConstants.FONT_STYLE_ITALIC);
            }
            if (style.getFontSizePt() != null) {
                handle.setProperty(StyleHandle.FONT_SIZE_PROP, style.getFontSizePt() + "pt");
            }
            if (style.getAlign() != null && !style.getAlign().isBlank()) {
                handle.setProperty(StyleHandle.TEXT_ALIGN_PROP, style.getAlign());
            }
            if (style.getBackgroundColor() != null && !style.getBackgroundColor().isBlank()) {
                handle.setProperty(StyleHandle.BACKGROUND_COLOR_PROP, style.getBackgroundColor());
            }
            
            try {
                handle.setProperty(StyleHandle.BORDER_TOP_STYLE_PROP, DesignChoiceConstants.LINE_STYLE_SOLID);
                handle.setProperty(StyleHandle.BORDER_BOTTOM_STYLE_PROP, DesignChoiceConstants.LINE_STYLE_SOLID);
                handle.setProperty(StyleHandle.BORDER_LEFT_STYLE_PROP, DesignChoiceConstants.LINE_STYLE_SOLID);
                handle.setProperty(StyleHandle.BORDER_RIGHT_STYLE_PROP, DesignChoiceConstants.LINE_STYLE_SOLID);
                
                handle.setProperty(StyleHandle.BORDER_TOP_WIDTH_PROP, "1px");
                handle.setProperty(StyleHandle.BORDER_BOTTOM_WIDTH_PROP, "1px");
                handle.setProperty(StyleHandle.BORDER_LEFT_WIDTH_PROP, "1px");
                handle.setProperty(StyleHandle.BORDER_RIGHT_WIDTH_PROP, "1px");
                
                handle.setProperty(StyleHandle.BORDER_TOP_COLOR_PROP, "#000000");
                handle.setProperty(StyleHandle.BORDER_BOTTOM_COLOR_PROP, "#000000");
                handle.setProperty(StyleHandle.BORDER_LEFT_COLOR_PROP, "#000000");
                handle.setProperty(StyleHandle.BORDER_RIGHT_COLOR_PROP, "#000000");
            } catch (Exception e) {
                System.out.println("[TPLGEN] style-borders failed: " + e.getMessage());
            }
        } catch (SemanticException ex) {
            System.out.println("[TPLGEN] style-apply failed: " + ex.getMessage());
        }
    }
}
