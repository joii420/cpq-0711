package com.cpq.priceadjust.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.util.List;

/**
 * api.md §4.2 · 切版只读预览（裁决 14：不落库）。
 */
public class RevisionPreviewResponse {

    public String revisionNo;

    /**
     * 恒 true（前端类型是字面量 {@code readonly: true}）。
     * {@code readonly} 在 Java 里不是关键字，但显式标注 {@code @JsonProperty} 以防将来被
     * 命名策略改写——前端按字面量类型匹配，字段名一变就是编译期不报、运行期 undefined。
     */
    @JsonProperty("readonly")
    public boolean readonly = true;

    public List<RevisionPreviewLineItemDTO> lineItems;

    public BigDecimal quoteTotalAmount;
}
