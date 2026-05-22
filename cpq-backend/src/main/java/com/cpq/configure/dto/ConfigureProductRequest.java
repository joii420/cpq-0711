package com.cpq.configure.dto;

import java.util.List;

public class ConfigureProductRequest {
    public String productType;                              // 'SIMPLE' | 'COMPOSITE'
    public List<PartRequest> parts;                         // SIMPLE 时 size=1; COMPOSITE 时 size>=2
    public List<CompositeProcessRequest> compositeProcesses; // 仅 COMPOSITE 才用
    /**
     * Bug B 解法 B: 前端 ConfigureProductDrawer 生成的 tempId (crypto.randomUUID)。
     * insertLineItem 优先用此 UUID 作 line_item.id（而不是后端自生成），
     * 使前端在提交前就知道 id，无需二次映射。
     * SIMPLE 时代表唯一 line item; COMPOSITE 时代表父 line item。
     * null / 空 = 老路径，后端 UUID.randomUUID() 兜底。
     */
    public String tempId;                                   // 前端 tempId (UUID 字符串，optional)
}
