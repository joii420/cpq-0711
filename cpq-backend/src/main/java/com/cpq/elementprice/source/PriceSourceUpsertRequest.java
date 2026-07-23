package com.cpq.elementprice.source;

/** 价格源新建/编辑请求（task-0722 · B4，契约见 api.md §1.2/§1.3）。*/
public class PriceSourceUpsertRequest {
    public String sourceName;
    public String sourceUrl;
    public String description;
    public String status;   // ACTIVE(默认) / DISABLED；sourceType 不接受前端传值，后端固定写 MANUAL
}
