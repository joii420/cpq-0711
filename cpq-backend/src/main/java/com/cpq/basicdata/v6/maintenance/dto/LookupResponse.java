package com.cpq.basicdata.v6.maintenance.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** §7 主表候选下拉（process / element / material）。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class LookupResponse {

    public List<LookupItem> items;

    public LookupResponse(List<LookupItem> items) {
        this.items = items;
    }

    public static final class LookupItem {
        public String code;
        public String name;

        public LookupItem(String code, String name) {
            this.code = code; this.name = name;
        }
    }
}
