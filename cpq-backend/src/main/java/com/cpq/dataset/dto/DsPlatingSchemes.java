package com.cpq.dataset.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * api.md §8.5 电镀方案<b>只读</b>列表（S-9 / 裁决 D-21 / AC-49 ~ AC-51）。
 *
 * <p>补的是「免版本表在新体系里没有查看入口」的缺口。
 *
 * <p>🚫 <b>只读，没有配套写端点</b>（AC-51）：电镀方案是免版本表，写入语义是「按主键覆盖更新」，
 * 只能经导入通道 {@code POST /dataset/{dataset}/import}。前端页面上不出现任何新增 / 编辑 / 删除 / 保存按钮。
 *
 * <p>{@code columns} 由后端<b>按数据集下发</b>（报价 10 列 / 详细核价 8 列，两张表字段本就不同），
 * 前端不得写死 —— 见 {@link Column}。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class DsPlatingSchemes {

    public long total;
    public List<Column> columns;
    public List<Map<String, Object>> items;

    public DsPlatingSchemes(long total, List<Column> columns, List<Map<String, Object>> items) {
        this.total = total;
        this.columns = columns;
        this.items = items;
    }

    /**
     * 列定义（api.md §8.5 只约定 {@code name / label / type} 三个键）。
     *
     * <p>⚠️ 刻意<b>不</b>直接下发 {@code ColumnDef}：那会带上 {@code editable} / {@code required} /
     * {@code compared}，而本页是只读的（AC-51）—— 下发 {@code editable=true} 会误导前端渲染出编辑态。
     * 这不是「手写第二份列定义」：三个字段全部投影自 Registry 的 {@code SheetDef.columns}，唯一真源没变。
     */
    public record Column(String name, String label, String type) {}
}
