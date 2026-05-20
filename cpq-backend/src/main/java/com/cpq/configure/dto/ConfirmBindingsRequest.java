package com.cpq.configure.dto;

import java.util.List;
import java.util.UUID;

/**
 * POST /material-recipes/confirm-bindings 批量确认请求.
 *
 * <p>每个 item 是一条 (partNo → recipeId) 绑定决策.
 */
public class ConfirmBindingsRequest {
    public List<Item> items;

    public static class Item {
        public String partNo;
        public UUID recipeId;
    }
}
