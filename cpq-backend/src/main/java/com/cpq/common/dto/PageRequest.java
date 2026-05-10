package com.cpq.common.dto;

import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.QueryParam;

public class PageRequest {

    @QueryParam("page")
    @DefaultValue("0")
    public int page;

    @QueryParam("size")
    @DefaultValue("20")
    public int size;

    @QueryParam("sort")
    @DefaultValue("createdAt,desc")
    public String sort;

    public int getOffset() {
        return page * size;
    }

    public String getSortField() {
        String[] parts = sort.split(",");
        return parts[0];
    }

    public String getSortDirection() {
        String[] parts = sort.split(",");
        return parts.length > 1 ? parts[1] : "desc";
    }
}
