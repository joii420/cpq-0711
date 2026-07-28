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

    /**
     * 生成空导入模板时，本 Sheet 第 1 行要写的中文表头（顺序 = Excel 中的自然列序）。
     *
     * <p>task-0728 · B4：模板下载端点按 {@link #sheetName()} 建 sheet、按本方法写表头，
     * 与导入解析同源。<b>列序有语义</b>——{@code SheetRow.getStr} 按「列序 + contains」匹配，
     * 若把「组成用量单位」排到「组成用量」之前，前者会被后者的键抢先命中（读错列）。
     * 新增/调整列时务必同时确认 {@code handle()} 的读取键仍能唯一命中目标列。
     *
     * <p>默认返回空列表 = 该 Handler 未声明表头（模板只建空 sheet，并打 warn）。
     * 报价侧 Q* handler 暂未声明，走默认实现。
     */
    default List<String> templateHeaders() { return List.of(); }
}
