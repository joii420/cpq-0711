package com.cpq.configure.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 元素主表 DTO（task-0709 / BL-0040）。
 * element_no=不可改业务主键；element_code=符号(被引用即锁)；element_name=中文(随时可改)。
 */
public class ElementDTO {
    public UUID id;
    public String elementNo;        // 业务主键
    public String elementCode;      // 符号
    public String elementName;      // 中文
    public String status;           // ACTIVE/INACTIVE
    public long referencedCount;    // 被引用材质元素行数 = COUNT(material_recipe_element WHERE element_no=本)
    public boolean codeLocked;      // referencedCount>0 → true（前端据此禁用符号输入框）
    public OffsetDateTime createdAt;
    public OffsetDateTime updatedAt;
}
