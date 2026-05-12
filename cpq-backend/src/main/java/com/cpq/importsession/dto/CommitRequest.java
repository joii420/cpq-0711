package com.cpq.importsession.dto;

import java.util.UUID;

/**
 * POST /sessions/{id}/commit 请求体。
 * Step 3「创建报价单」表单数据，commit 时同步建报价单使用。
 */
public class CommitRequest {

    /** 报价单名称（必填） */
    public String name;

    /** 报价单分类 ID（可选） */
    public UUID categoryId;

    /** 客户报价模板 ID（对应 template 表 template_kind='QUOTATION'，可选） */
    public UUID customerTemplateId;

    /** 核价模板 ID（对应 template 表 template_kind='COSTING'，可选） */
    public UUID costingTemplateId;
}
