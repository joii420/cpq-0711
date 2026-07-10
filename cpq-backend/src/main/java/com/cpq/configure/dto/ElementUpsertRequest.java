package com.cpq.configure.dto;

/**
 * 元素新建/编辑请求（task-0709）。
 * - create：elementNo/elementCode/elementName 必填；status 默认 ACTIVE。
 * - update：elementNo 由路径定位、请求体里的忽略；elementCode 被引用即锁；elementName/status 随时可改。
 */
public class ElementUpsertRequest {
    public String elementNo;
    public String elementCode;
    public String elementName;
    public String status;   // 可选：ACTIVE/INACTIVE（update 可改，含重新启用）
}
