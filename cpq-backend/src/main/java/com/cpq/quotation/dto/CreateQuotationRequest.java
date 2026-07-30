package com.cpq.quotation.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.UUID;

public class CreateQuotationRequest {

    @NotNull
    public UUID customerId;

    @NotNull
    @Size(max = 500, message = "name 长度不能超过 500 个字符")
    public String name;

    public UUID contactId;
    @Size(max = 200) public String contactName;
    @Size(max = 50)  public String contactPhone;
    @Size(max = 200) public String contactEmail;
    @Size(max = 500) public String projectName;
    @Size(max = 200) public String opportunityId;
    @Size(max = 30)  public String quoteType;
    @Size(max = 20)  public String priority;
    @Size(max = 50)  public String stage;
    public LocalDate expectedCloseDate;

    /**
     * 客户报价模板 ID(对应 docs/API.md 双模板体系):
     * 由前端按 (customerId + categoryId) 匹配后传入,创建时直接写入 quotation.customer_template_id。
     * 留空则后续在报价单 Step2 中由用户手工选择。
     */
    public UUID customerTemplateId;

    /**
     * task-0729: 建单时的产品分类，前端传什么存什么，后端不做二次推导
     * （不反查客户绑定，避免与 D4「客户改绑分类不追溯已有报价单」冲突）。
     * 字段名故意叫 categoryId（对齐 QuotationDTO.categoryId / 前端回填协议）。
     */
    public UUID categoryId;

    /**
     * 核价模板 ID（双模板体系第二条，V72 起改为指向 template 表里 template_kind='COSTING' 的模板）。
     * 由前端在「创建报价单」抽屉按 (categoryId + customerId) 匹配「模板配置」中已发布的核价模板。
     * 后端会校验：模板存在、status='PUBLISHED'、template_kind='COSTING'，写入 quotation.costing_card_template_id。
     * 留空则后续在「核价单」视图里手工选择。
     *
     * 注：与早期版本不同——不再向 costing_template（Excel 列结构）建立关系，也不再创建空 costing_sheet 行。
     * Excel 视图配置走「Excel 模板配置」菜单，与核价产品卡面模板是两套独立体系。
     */
    public UUID costingTemplateId;
}
