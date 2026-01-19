package ru.rea.report.ir;

public sealed interface BlockIR permits ImageIR, ParagraphIR, TableIR {
}
