package ru.rea.report.birt;

import com.ibm.icu.util.ULocale;
import lombok.RequiredArgsConstructor;
import org.eclipse.birt.report.model.api.*;
import org.eclipse.birt.report.model.api.activity.SemanticException;
import org.eclipse.birt.report.model.api.elements.DesignChoiceConstants;
import org.springframework.stereotype.Component;
import ru.rea.report.exception.TemplateProcessingException;
import ru.rea.report.ir.*;
import ru.rea.report.tags.TagRegistry;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

@Component
@RequiredArgsConstructor
public class BirtDesignBuilder {

    private final IDesignEngine designEngine;
    private final BirtExpressionMapper exprMapper;

    public void build(TemplateDocumentIR ir, TagRegistry tags, OutputStream out) {
        try {
            SessionHandle session = designEngine.newSessionHandle(ULocale.getDefault());
            ReportDesignHandle report = session.createDesign();

            ensureMasterPage(report);
            declareParams(report, tags);

            if (ir != null && ir.getBlocks() != null) {
                for (BlockIR block : ir.getBlocks()) {
                    if (block instanceof ParagraphIR p) {
                        TextItemHandle text = report.getElementFactory().newTextItem(null);
                        text.setContentType(DesignChoiceConstants.TEXT_DATA_CONTENT_TYPE_HTML);
                        text.setContent(exprMapper.mapTextToBirtHtml(p.getText(), tags));
                        report.getBody().add(text);

                    } else if (block instanceof TableIR t) {
                        GridHandle grid = buildGridFromTable(report, t, tags);
                        if (grid != null) report.getBody().add(grid);
                    }
                }
            }

            Path tmp = Files.createTempFile("tplgen-", ".rptdesign");
            report.saveAs(tmp.toAbsolutePath().toString());

            try (InputStream in = Files.newInputStream(tmp)) {
                in.transferTo(out);
                out.flush();
            } finally {
                Files.deleteIfExists(tmp);
            }

        } catch (Exception e) {
            throw new TemplateProcessingException("Failed to build rptdesign via BIRT model API", e);
        }
    }

    private void ensureMasterPage(ReportDesignHandle report) throws SemanticException {
        if (report.getMasterPages().getCount() > 0) return;
        SimpleMasterPageHandle mp = report.getElementFactory().newSimpleMasterPage(BirtResources.MASTER_PAGE);
        report.getMasterPages().add(mp);
    }

    private void declareParams(ReportDesignHandle report, TagRegistry tags) throws SemanticException {
        if (tags == null || tags.isEmpty()) return;

        for (var e : tags.entries()) {
            String paramName = e.getValue();

            if (hasParameter(report, paramName)) {
                continue;
            }

            ScalarParameterHandle p = report.getElementFactory().newScalarParameter(paramName);
            p.setDataType(DesignChoiceConstants.PARAM_TYPE_STRING);

            // prompt = raw (то, что в шаблоне), чтобы в дизайнере было понятно
            p.setProperty(ScalarParameterHandle.PROMPT_TEXT_PROP, e.getKey());

            report.getParameters().add(p);
        }
    }

    private GridHandle buildGridFromTable(ReportDesignHandle report, TableIR table, TagRegistry tags) throws SemanticException {
        if (table == null || table.getRows() == null || table.getRows().isEmpty()) return null;

        int rows = table.getRows().size();
        int cols = 0;
        for (RowIR r : table.getRows()) {
            if (r.getCells() != null) cols = Math.max(cols, r.getCells().size());
        }
        if (rows <= 0 || cols <= 0) return null;

        GridHandle grid = report.getElementFactory().newGridItem(null, cols, rows);

        for (int r = 0; r < rows; r++) {
            RowIR row = table.getRows().get(r);
            RowHandle gridRow = (RowHandle) grid.getRows().get(r);

            int limit = row.getCells() == null ? 0 : row.getCells().size();
            for (int c = 0; c < limit; c++) {
                CellIR cell = row.getCells().get(c);
                CellHandle ghCell = (CellHandle) gridRow.getCells().get(c);

                if (cell.getColSpan() > 1) ghCell.setColumnSpan(cell.getColSpan());
                if (cell.getRowSpan() > 1) ghCell.setRowSpan(cell.getRowSpan());

                TextItemHandle text = report.getElementFactory().newTextItem(null);
                text.setContentType(DesignChoiceConstants.TEXT_DATA_CONTENT_TYPE_HTML);
                text.setContent(exprMapper.mapTextToBirtHtml(cell.getText(), tags));

                ghCell.getContent().add(text);
            }
        }
        return grid;
    }

    private static boolean hasParameter(ReportDesignHandle report, String paramName) {
        if (report == null || paramName == null || paramName.isBlank()) return false;

        SlotHandle slot = report.getParameters();
        for (int i = 0; i < slot.getCount(); i++) {
            Object obj = slot.get(i);
            if (obj instanceof ScalarParameterHandle p) {
                if (paramName.equals(p.getName())) return true;
            } else if (obj instanceof ParameterHandle p) {
                if (paramName.equals(p.getName())) return true;
            } else if (obj instanceof ReportItemHandle) {
                // на всякий случай, но обычно сюда не попадает
            }
        }
        return false;
    }

}
