package com.cpq.template.dto;

import java.util.UUID;

/**
 * 报价导入向导「选模板」步骤的自动默认值。
 *
 * <p>customerTemplateSource:
 * <ul>
 *   <li>LAST_USED — 跟随该客户上次使用的报价模板线的最新发布版本</li>
 *   <li>CUSTOMER_SPECIFIC_FALLBACK — 无历史,退客户专属最新发布</li>
 *   <li>GENERAL_FALLBACK — 无历史且无客户专属,退通用最新发布</li>
 *   <li>NONE — 无任何可用报价模板</li>
 * </ul>
 * costingTemplateSource: CUSTOMER_SPECIFIC | GENERAL | NONE(核价不做记忆)。
 */
public class QuoteImportAutoDefaults {

    public UUID categoryId;
    public String categoryName;

    public UUID customerTemplateId;
    public UUID customerTemplateSeriesId;
    public String customerTemplateName;
    public String customerTemplateVersion;
    public String customerTemplateSource;

    public UUID costingTemplateId;
    public String costingTemplateName;
    public String costingTemplateVersion;
    public String costingTemplateSource;
}
