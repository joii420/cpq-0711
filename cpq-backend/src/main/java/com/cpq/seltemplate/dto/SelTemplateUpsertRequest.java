package com.cpq.seltemplate.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.ArrayList;
import java.util.List;

public class SelTemplateUpsertRequest {
    @NotBlank(message = "行业不能为空")
    public String industryCode;
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
