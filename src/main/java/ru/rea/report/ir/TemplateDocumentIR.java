package ru.rea.report.ir;

import lombok.Getter;
import lombok.experimental.Accessors;

import java.util.ArrayList;
import java.util.List;

@Getter
@Accessors(chain = true)
public class TemplateDocumentIR {

    private final List<BlockIR> blocks = new ArrayList<>();

    public TemplateDocumentIR add(BlockIR block) {
        blocks.add(block);
        return this;
    }
}
