package com.cpq.quotation.dto;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Excel 公式试算请求：用临时列配置(不读模板/不落库)按某报价单逐行算。 */
public class ExcelDryRunRequest {
    public UUID templateId;                     // 求值上下文模板(供模板公式/SqlView;CARD_FORMULA 不强依赖)
    public List<Map<String, Object>> columns;   // 临时列配置(含 CARD_FORMULA 的 formula/refs)
}
