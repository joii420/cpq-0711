package com.cpq.priceadjust.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.UUID;

/**
 * api.md §4.2 · 切版只读预览的单个产品行。
 *
 * <p>三个 JSON 字段是**原样透传**的快照片段（{@link com.fasterxml.jackson.databind.JsonNode}，
 * 不反序列化成强类型）——前端 {@code QuotationPriceRevisionsDrawer.tsx} 对它们只做
 * {@code JSON.stringify(...)} 渲染，服务端做任何结构加工都只会引入与快照原貌的偏差。
 */
public class RevisionPreviewLineItemDTO {
    public UUID lineItemId;
    /** 快照不存料号，从当前 {@code quotation_line_item} 反查；该行已被删除时为 "—"。 */
    public String materialNo;
    /** 🔒 来自快照（验收 #55）。 */
    public JsonNode quoteCardValues;
    /** 🔒 同样来自快照，**禁止**读当前值——报价侧看着完全正常，极难发现（验收 #55 专防）。 */
    public JsonNode costingCardValues;
    /** 结构 = {@code { "<componentId>": <snapshot_rows 原始 JSON> }}，前端当前不消费。 */
    public JsonNode snapshotRows;
}
