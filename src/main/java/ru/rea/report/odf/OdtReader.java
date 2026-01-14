package ru.rea.report.odf;

import org.odftoolkit.odfdom.doc.OdfTextDocument;
import org.springframework.stereotype.Component;
import ru.rea.report.exception.BadTemplateException;
import ru.rea.report.ir.ParagraphIR;
import ru.rea.report.ir.TemplateDocumentIR;

import java.io.InputStream;

@Component
public class OdtReader {

    public TemplateDocumentIR read(InputStream in) {
        try {
            OdfTextDocument doc = OdfTextDocument.loadDocument(in);

            TemplateDocumentIR ir = new TemplateDocumentIR();
            String text = doc.getContentRoot().getTextContent();

            String[] lines = text.split("\\R");
            for (String line : lines) {
                if (line != null && !line.isBlank()) {
                    ir.add(new ParagraphIR(line.trim()));
                }
            }
            return ir;

        } catch (Exception e) {
            throw new BadTemplateException("Failed to read ODT template", e);
        }
    }
}
