package com.cpq.basicdata.v6.parser;

import java.util.List;

/**
 * 每个 Excel Sheet 对应一个 Handler。
 * <ul>
 *   <li>{@link #sheetName()} 返回中文 Sheet 名（用于在 Workbook 里定位）</li>
 *   <li>{@link #handle(List, ImportContext)} 收到解析好的 SheetRow 列表，自行写库并返回 SheetImportResult</li>
 * </ul>
 * <p>每个 Handler 在独立事务里执行（per-Sheet 事务），一个 Sheet 失败不影响其它。
 */
public interface SheetHandler {

    /** 此 Handler 处理的 Excel Sheet 中文名。 */
    String sheetName();

    /** 处理已解析的行，返回每 Sheet 的成功/失败行数与错误明细。 */
    SheetImportResult handle(List<SheetRow> rows, ImportContext ctx);
}
