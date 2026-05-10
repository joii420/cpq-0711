package com.cpq.template.dto;

import java.util.UUID;

public class CreateTemplateRequest {

    public String name;
    public String category;
    public UUID customerId;
    public UUID categoryId;
    public String description;
    public String usageNote;
    public String productAttributes; // JSON string
    public String subtotalFormula;   // JSON string
    /** V71：模板类型 'QUOTATION' / 'COSTING'，缺省 'QUOTATION'。
     *  COSTING 类型 customerId 可留空 → 表示所有客户可用。 */
    public String templateKind;
}
