package com.cpq.dataset.dto;

import com.cpq.dataset.registry.ColumnDef;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * api.md §2 单个「带版本 sheet」的元数据（前端抽屉 tab + 表格渲染 + 前置校验）。
 *
 * <p>{@code columns} 直接复用 {@link ColumnDef} —— 它的 JSON 形态就是 api.md §2 的列契约
 * （服务端内部元数据已由 {@code @JsonIgnore} 挡掉）。
 * 🚫 <b>刻意不再包一层映射 DTO</b>：多一层就多一处双写，Registry 加字段时必然漏改
 * （{@code PricingSheetRegistry} 类注释自陈的「双写漂移」就是这么来的）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class DsSheetMeta {

    public String sheetKey;
    public String sheetName;
    public int sortOrder;
    public String axisColumn;
    public String axisLabel;
    public List<ColumnDef> columns;
}
