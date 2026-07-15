package com.cpq.quotation.dto;

import java.math.BigDecimal;
import java.util.List;

/** POST /api/cpq/costing-orders/{coid}/version-switch 响应体（api.md §3）。
 * 前端只用这些字段做增量刷新，不整单重新 getById（守 AP-31）。 */
public class VersionSwitchResponseDTO {
    public String lineItemId;

    /** 该卡片重算后的核价卡片值（行内含 view_version），原始 JSON 字符串，
     * 与 QuotationDTO.LineItemDTO#costingCardValues 同形态，前端直接替换用。 */
    public String costingCardValues;

    /** 该卡片重算后的核价 Excel 值（若受影响），原始 JSON 字符串。 */
    public String costingExcelColumns;

    /** 更新后的单据总价（Σ 核价成本 subtotal，不含 Step3 折扣）。 */
    public BigDecimal costingTotalAmount;

    /** 实际触发重查/重算的页签 componentId 列表。 */
    public List<String> affectedTabs;
}
