package com.cpq.dataset.importer;

import com.cpq.dataset.registry.SheetDef;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 已解析的一个 sheet（task-260902 · B-6）。 */
public class ParsedSheet {

    public final SheetDef spec;
    /** Registry 声明为 DB 列、但 Excel 表头里缺失的列 label（→ 表头不一致错误）。 */
    public final Set<String> missingHeaders = new LinkedHashSet<>();
    public final List<ParsedRow> rows = new ArrayList<>();
    /** Excel 里存在该 sheet（哪怕只有表头）。false = 本次文件根本没这个 sheet。 */
    public boolean present;

    public ParsedSheet(SheetDef spec) {
        this.spec = spec;
    }
}
