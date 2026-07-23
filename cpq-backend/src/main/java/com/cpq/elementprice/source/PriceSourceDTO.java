package com.cpq.elementprice.source;

import java.time.OffsetDateTime;
import java.util.UUID;

/** 价格源 DTO（task-0722 · B4，契约见 api.md §1）。*/
public class PriceSourceDTO {
    public UUID id;
    public String sourceName;
    public String sourceUrl;
    public String sourceType;
    public String description;
    public String status;
    public OffsetDateTime createdAt;
    public OffsetDateTime updatedAt;

    public static PriceSourceDTO from(ElementPriceSource e) {
        PriceSourceDTO d = new PriceSourceDTO();
        d.id = e.id;
        d.sourceName = e.sourceName;
        d.sourceUrl = e.sourceUrl;
        d.sourceType = e.sourceType;
        d.description = e.description;
        d.status = e.status;
        d.createdAt = e.createdAt;
        d.updatedAt = e.updatedAt;
        return d;
    }
}
