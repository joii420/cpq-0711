package com.cpq.seltemplate.dto;

import com.cpq.seltemplate.entity.SelTemplate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class SelTemplateDTO {
    public UUID id;
    public String industryCode, name, status;
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
        d.id = t.id; d.industryCode = t.industryCode; d.name = t.name;
        d.status = t.status; d.version = t.version;
        d.createdAt = t.createdAt; d.updatedAt = t.updatedAt;
        return d;
    }
}
