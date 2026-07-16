package com.cpq.seltemplate.dto;

import com.cpq.seltemplate.entity.SelTemplate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class SelTemplateDTO {
    public UUID id;
    // task-0712 update-071501: 换轴为产品分类维度（原行业码字段已删除）；不返 productCategoryName(D5)
    public UUID productCategoryId;
    public String name, status;
    public Integer version;
    public OffsetDateTime createdAt, updatedAt;
    public List<Item> items = new ArrayList<>();

    public static class Item {
        public String paramTypeCode;
        public boolean enabled;
        public Integer sortOrder;
        public List<String> allowedValues = new ArrayList<>();  // 空=不限
    }

    public static SelTemplateDTO from(SelTemplate t) {
        SelTemplateDTO d = new SelTemplateDTO();
        d.id = t.id; d.productCategoryId = t.productCategoryId; d.name = t.name;
        d.status = t.status; d.version = t.version;
        d.createdAt = t.createdAt; d.updatedAt = t.updatedAt;
        return d;
    }
}
