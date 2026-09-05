package com.cpq.product.dataset;

import java.util.List;

/**
 * 「客户产品」客户过滤器的**候选来源**
 * （{@code GET /api/cpq/dataset/{dataset}/customer-parts/customers}）的响应载荷。
 *
 * <p>本类是 {@code task-260903-产品维护能力增强} 的 <b>B-2</b>，服务 <b>AC-5</b>。
 *
 * <p>🚨 <b>候选必须来自 {@code ds_quote_customer_part} 里实际出现过的 {@code customer_no}
 * （{@code GROUP BY}），而不是 {@code customer} 主数据表。</b>
 * 父任务实证：{@code Q13CUST0617}（2 行）与 {@code C1}（1 行）<b>未在 {@code customer} 表建档</b>。
 * 若候选只从 {@code customer} 表取，这 3 行产品<b>在页面上看得见、却永远筛不出来</b> ——
 * 这正是 AC-5 的全部意义。
 *
 * <p>🚫 <b>只读</b>：本端点不含任何写操作。
 *
 * <p>🔧 {@code task-260902} 的 {@code com.cpq.dataset} 包合并后，本类应随
 * {@link DsCustomerParts} / {@link DatasetCustomerPartResource} / {@link DatasetCustomerPartService}
 * 一并迁入该包统一维护。
 */
public class DsCustomerOptions {

    /** 一个候选客户。 */
    public static class Item {
        /** 客户编号 —— 取自 {@code ds_quote_customer_part.customer_no}，即过滤器要回传的值。 */
        public String customerNo;
        /**
         * 客户名称，由 {@code LEFT JOIN customer c ON c.code = t.customer_no} 带出。
         *
         * <p>🚨 {@code customer} 表<b>没有 {@code customer_no} 列</b>，客户编号列名是 {@code code}。
         * <p>⚠️ 必须 {@code LEFT JOIN}：{@code INNER} 会把未建档客户静默丢掉
         * （实测本表 17 行 LEFT 得 17 / INNER 只剩 14）。
         * <p>JOIN 不到时本字段为 {@code null}，前端按原型渲染 {@code 客户编号（未建档）}。
         */
        public String customerName;
        /** 该客户在 {@code ds_quote_customer_part} 里的产品行数（供下拉显示「正泰 11」）。 */
        public long count;

        public Item() {}

        public Item(String customerNo, String customerName, long count) {
            this.customerNo = customerNo;
            this.customerName = customerName;
            this.count = count;
        }
    }

    /**
     * 候选客户列表。
     *
     * <p>排序：<b>已建档（{@code customerName != null}）在前</b>，组内按 {@code customerNo} 升序；
     * 未建档的排在末尾同样按 {@code customerNo} 升序。AC-5 只断言 5 个候选<b>全部出现</b>，
     * 未对顺序作断言；此处取一个确定顺序，避免翻页/复测时候选项跳动。
     */
    public List<Item> items;

    public DsCustomerOptions() {}

    public DsCustomerOptions(List<Item> items) {
        this.items = items;
    }
}
