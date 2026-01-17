package ru.rea.report.ir;

import lombok.Data;
import lombok.Getter;
import lombok.experimental.Accessors;

import java.util.ArrayList;
import java.util.List;

@Data
@Accessors(chain = true)
public class TemplateDocumentIR {

    private final List<BlockIR> blocks = new ArrayList<>();
    private PageIR page;


    public TemplateDocumentIR add(BlockIR block) {
        blocks.add(block);
        return this;
    }
}
