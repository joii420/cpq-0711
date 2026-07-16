package com.cpq.seltemplate.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class SelTemplateUpsertRequest {
    // task-0712 update-071501: 换轴为产品分类维度（原行业码字段已删除，语义同名替换）
    @NotNull(message = "产品分类不能为空")
    public UUID productCategoryId;
    @NotBlank(message = "模板名不能为空")
    public String name;
    public String status;
    public List<Item> items = new ArrayList<>();

    public static class Item {
        public String paramTypeCode;
        public boolean enabled;
        public List<String> allowedValues = new ArrayList<>();  // 空=不限
    }
}
