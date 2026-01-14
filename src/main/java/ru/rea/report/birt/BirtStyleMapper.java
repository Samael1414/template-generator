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
                // left/center/right
                handle.setProperty(StyleHandle.TEXT_ALIGN_PROP, style.getAlign());
            }
            if (style.getBackgroundColor() != null && !style.getBackgroundColor().isBlank()) {
                handle.setProperty(StyleHandle.BACKGROUND_COLOR_PROP, style.getBackgroundColor());
            }
        } catch (SemanticException ex) {
            // MVP: не валим генерацию из-за стиля
            System.out.println("[TPLGEN] style-apply failed: " + ex.getMessage());
        }
    }
}
