package com.cpq.costing.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * task-0717 比对视图 — 逐销售料号 × 两侧 × 逐页签取值矩阵。
 * 契约见 dev-docs/task-0717-比对视图/api.md §2。
 *
 * <p>一次性返回所有页签所有小计；前端按已配置列取数，新增/删除列无需重新请求（api.md §2 前言）。
 * 所有数值均复用 {@code CardSnapshotService} 渲染报价单/核价单 Tab 时已写入
 * {@code quote_card_values}/{@code costing_card_values} 的 {@code subtotal}/{@code subtotalByColumn}
 * 字段（见 {@link com.cpq.costing.service.ComparisonViewService}），不新写取值路径（AC-3 单源纪律）。
 */
public class ComparisonDataDTO {

    public List<RowDTO> rows;

    public static class RowDTO {
        /** 销售料号（比对连接键）。 */
        public String partNo;
        public String productName;
        /** BOTH | QUOTE_ONLY | COSTING_ONLY */
        public String presence;
        /** 报价侧取值；presence=COSTING_ONLY 时为 null。 */
        public SideDTO quote;
        /** 核价侧取值；presence=QUOTE_ONLY 时为 null。 */
        public SideDTO costing;
    }

    public static class SideDTO {
        /** 产品卡片总计（SUBTOTAL 组件独立公式值，2 位口径）。 */
        public BigDecimal productTotal;
        /** key = componentId（页签）。 */
        public Map<String, TabValueDTO> tabs;
    }

    public static class TabValueDTO {
        /** 页签合计（该页签所有 is_subtotal 列之和，4 位口径）；缺失为 null。 */
        public BigDecimal tabTotal;
        /** key = is_subtotal 字段名；缺失/无此类字段为 null。 */
        public Map<String, BigDecimal> subtotals;
    }
}
