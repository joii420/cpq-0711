package com.cpq.template.dto;

import java.util.List;

/**
 * 客户报价模板匹配结果。
 *
 * <p>语义(对应 docs/API.md L100/L643):
 * <ul>
 *   <li>{@code CUSTOMER_SPECIFIC} — 找到 customer_id=X AND category_id=Y 的客户专属模板</li>
 *   <li>{@code GENERAL_FALLBACK} — 客户专属为空,回退到 customer_id IS NULL AND category_id=Y 的通用模板</li>
 *   <li>{@code NONE} — 都没找到,前端应阻止或引导管理员配置</li>
 * </ul>
 *
 * <p>匹配规则:V62 撤销 V28 partial unique index 后,同 (customer, category) 可有多个 PUBLISHED;
 * 0 条 → NONE;1 条 → 自动选;N 条 → 前端展示让用户挑。
 */
public class TemplateMatchResult {

    public enum MatchType {
        CUSTOMER_SPECIFIC,
        GENERAL_FALLBACK,
        NONE
    }

    public MatchType matchType;
    public List<TemplateDTO> templates;

    public TemplateMatchResult() {}

    public TemplateMatchResult(MatchType matchType, List<TemplateDTO> templates) {
        this.matchType = matchType;
        this.templates = templates;
    }
}
