package ru.rea.report.odf;


import org.springframework.stereotype.Component;
import ru.rea.report.core.TemplateType;
import ru.rea.report.ir.TemplateDocumentIR;

import java.io.InputStream;

@Component
public class OdfTemplateReader {

    private final OdtReader odtReader;
    private final OdsReader odsReader;

    public OdfTemplateReader(OdtReader odtReader, OdsReader odsReader) {
        this.odtReader = odtReader;
        this.odsReader = odsReader;
    }

    public TemplateDocumentIR read(InputStream in, TemplateType type) throws Exception {
        return switch (type) {
            case ODT -> odtReader.read(in);
            case ODS -> odsReader.read(in);
        };
    }
}
