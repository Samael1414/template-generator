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
                        DesignElementHandle el = buildTextData(report, p.getText());
                        report.getBody().add(el);

                    } else if (block instanceof TableIR t) {
                        GridHandle grid = buildGridFromTable(report, t);
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
        mp.setPageType("a4");
        mp.setProperty(SimpleMasterPageHandle.TOP_MARGIN_PROP, "2cm");
        mp.setProperty(SimpleMasterPageHandle.LEFT_MARGIN_PROP, "2cm");
        mp.setProperty(SimpleMasterPageHandle.BOTTOM_MARGIN_PROP, "2cm");
        mp.setProperty(SimpleMasterPageHandle.RIGHT_MARGIN_PROP, "2cm");
        mp.setProperty(SimpleMasterPageHandle.HEADER_HEIGHT_PROP, "0cm");
        mp.setProperty(SimpleMasterPageHandle.FOOTER_HEIGHT_PROP, "0cm");
        mp.setProperty(SimpleMasterPageHandle.SHOW_HEADER_ON_FIRST_PROP, "false");
        mp.setProperty(SimpleMasterPageHandle.SHOW_FOOTER_ON_LAST_PROP, "false");
        report.getMasterPages().add(mp);
    }

    private void declareParams(ReportDesignHandle report, TagRegistry tags) throws SemanticException {
        if (tags == null || tags.isEmpty()) return;

        for (String paramName : tags.paramNames()) {
            if (hasParameter(report, paramName)) continue;

            ScalarParameterHandle p = report.getElementFactory().newScalarParameter(paramName);
            p.setDataType(DesignChoiceConstants.PARAM_TYPE_STRING);
            p.setProperty(ScalarParameterHandle.PROMPT_TEXT_PROP, paramName);
            report.getParameters().add(p);
        }
    }

    /**
     * Главное отличие: создаём text-data с valueExpr (как в рабочем шаблоне),
     * а не text с HTML content (который приводит к &amp;{...}).
     */
    private DesignElementHandle buildTextData(ReportDesignHandle report, String srcText) throws SemanticException {
        String expr = exprMapper.toHtmlValueExpr(srcText);



        // ВАРИАНТ 2 (если у тебя нет newTextDataItem, часто есть newTextData)
         TextDataHandle td = report.getElementFactory().newTextData(null);

        td.setProperty("contentType", DesignChoiceConstants.TEXT_DATA_CONTENT_TYPE_HTML);

        td.setProperty("valueExpr", expr);

        return td;
    }

    private GridHandle buildGridFromTable(ReportDesignHandle report, TableIR table) throws SemanticException {
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

            int limit = row.getCells() == null ? 0 : Math.min(row.getCells().size(), cols);
            for (int c = 0; c < limit; c++) {
                CellIR cell = row.getCells().get(c);
                CellHandle ghCell = (CellHandle) gridRow.getCells().get(c);

                if (cell.isCovered()) continue;

                int cs = Math.max(cell.getColSpan(), 1);
                int rs = Math.max(cell.getRowSpan(), 1);

                cs = Math.min(cs, cols - c);
                rs = Math.min(rs, rows - r);

                if (cs > 1) ghCell.setColumnSpan(cs);
                if (rs > 1) ghCell.setRowSpan(rs);

                String txt = cell.getText();
                if (txt == null || txt.isBlank()) continue;

                ghCell.getContent().add(buildTextData(report, txt));
            }
        }

        return grid;
    }

    private static boolean hasParameter(ReportDesignHandle report, String paramName) {
        if (report == null || paramName == null || paramName.isBlank()) return false;

        SlotHandle slot = report.getParameters();
        for (int i = 0; i < slot.getCount(); i++) {
            Object obj = slot.get(i);
            if (obj instanceof ParameterHandle p) {
                if (paramName.equals(p.getName())) return true;
            }
        }
        return false;
    }
}
