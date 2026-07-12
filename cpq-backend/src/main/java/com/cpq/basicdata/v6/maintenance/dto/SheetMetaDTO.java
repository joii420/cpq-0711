package com.cpq.basicdata.v6.maintenance.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** §2 单个 sheet 的列定义元数据（前端渲染 + 校验，从 PricingSheetRegistry 生成）。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class SheetMetaDTO {

    public String sheetKey;
    public String tabName;
    public String group;             // FEE / BOM / CAPACITY_ENERGY / TOOLING
    public int order;
    public boolean masterDetail;
    public String salesPartAnchor;   // code / finished_material_no / material_no
    public List<ColumnDef> columns;
}
