package com.cpq.dataset.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** api.md §8 主数据下拉候选（只读查现有共享主数据表，D-16「主数据不拆」）。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class DsLookupResponse {

    public List<Item> items;

    public DsLookupResponse(List<Item> items) {
        this.items = items;
    }

    public static final class Item {
        public String code;
        public String name;

        public Item(String code, String name) {
            this.code = code;
            this.name = name;
        }
    }
}
