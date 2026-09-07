package com.cpq.dataset.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * api.md §1 —— 客户候选（{@code GET /dataset/{dataset}/customers}）响应载荷。
 *
 * <p>task-260907-产品管理客户过滤 · B-1，服务 AC-1 / AC-2 / AC-14。
 *
 * <p>🚨 候选口径是<b>并集</b>：{@code customer} 表全集 ∪ 报价业务表中未建档的客户号
 * （见 {@code DatasetMaintenanceService#listCustomers}）。两个半集各自解决一个问题，
 * 缺一不可 —— 详见 api.md §1「候选口径」表。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class DsCustomerCandidates {

    public List<Item> items;

    public DsCustomerCandidates() {}

    public DsCustomerCandidates(List<Item> items) {
        this.items = items;
    }

    /**
     * 一个候选客户。
     *
     * <p>🚨 <b>本内层类刻意不套 {@code @JsonInclude(NON_NULL)}</b>——{@code customerName} 为
     * {@code null} 时必须在 JSON 里以 {@code "customerName":null} 的形式<b>出现</b>（api.md §1
     * 响应示例逐字如此），而不是把键整个省略掉。省略键与显式 null 对多数前端消费方无差别，
     * 但契约既然写死了示例就必须逐字对齐，避免「响应与文档不一致」被误判成 bug。
     */
    public static final class Item {
        /** 客户编号，取值口径 = {@code customer.code}（如 {@code CUST-0001}），唯一不重复。 */
        public String customerNo;
        /**
         * 客户名称。🚨 <b>未建档客户恒为 {@code null}</b>（不是空字符串）——
         * 前端必须能渲染 null，🚫 不得因它为空而过滤掉该候选项（AC-14③）。
         */
        public String customerName;
        /** {@code true} = 在 {@code customer} 表建过档；{@code false} = 只在报价业务表里出现过。 */
        public boolean registered;

        public Item() {}

        public Item(String customerNo, String customerName, boolean registered) {
            this.customerNo = customerNo;
            this.customerName = customerName;
            this.registered = registered;
        }
    }
}
