package com.cpq.common.exception;

import java.util.List;

/**
 * task-0721 B5/B6：BOM 树加叶子时，候选料号命中 ≥2 个<b>不同类型</b>页签的冲突异常。
 * HTTP 409 + 结构化冲突类型列表（供前端展示裁决，api.md §3 "data.conflictTabs"）。
 *
 * <p>粒度纪律：只有命中不同类型（材质元素/零件/外购件）的页签才构成冲突；同一类型下
 * 命中多个页签（如两个「材质元素」Tab）不算冲突——类型无歧义，正常判定，不抛本异常。
 */
public class TreeConflictException extends BusinessException {

    private final List<String> conflictTabs;

    public TreeConflictException(String message, List<String> conflictTabs) {
        super(409, message);
        this.conflictTabs = conflictTabs;
    }

    public List<String> getConflictTabs() {
        return conflictTabs;
    }
}
