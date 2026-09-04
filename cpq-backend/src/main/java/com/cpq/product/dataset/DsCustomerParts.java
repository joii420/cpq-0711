package com.cpq.product.dataset;

import java.util.List;

/**
 * 「客户产品」列表（{@code GET /api/cpq/dataset/{dataset}/customer-parts}）的响应载荷。
 *
 * <p><b>本类是 {@code task-260903-产品管理页重做} 按用户裁决自建的</b>（2026-09-03）：
 * {@code task-260902} 的 8 个 {@code {dataset}} 端点中没有任何一个读得到免版本表
 * {@code ds_quote_customer_part} —— 免版本表按主源 R-2「不进抽屉 tab」，客户料号这张表
 * 在新体系里<b>没有查看入口</b>。对方为同类问题（电镀方案）已实现
 * {@code GET /{dataset}/plating-schemes} + {@code DsPlatingSchemes}，本类照其形状实现。
 *
 * <p>🔧 <b>后续维护约定</b>：{@code task-260902} 的 {@code com.cpq.dataset} 包合并进 master 后，
 * 本类与 {@link DatasetCustomerPartResource} / {@link DatasetCustomerPartService}
 * <b>应整体迁入该包统一维护</b>（路径与响应形状已按主源风格对齐，迁移时调用方零改动）。
 * 当前放在 {@code com.cpq.product.dataset} 只是因为那个包尚未合并、看不到也不能写。
 *
 * <p>🚫 <b>只读，无配套写端点</b>。本页纯查看；写了就产生第二条升版路径，
 * 是《报价侧交接说明》点名禁止的事。
 */
public class DsCustomerParts {

    /**
     * 列元数据。
     *
     * <p>🚫 <b>刻意不直接下发主源的 {@code ColumnDef}</b> —— 那会带上
     * {@code editable} / {@code required} / {@code compared} 三个字段，而本页是<b>只读页</b>，
     * 下发 {@code editable=true} 会误导前端渲染出编辑态。这里只投影 {@code name/label/type}。
     */
    public static class Column {
        public String name;
        public String label;
        /** {@code STRING} / {@code NUMBER} / {@code DECIMAL}；本表 6 列全为 {@code STRING}。 */
        public String type;

        public Column() {}

        public Column(String name, String label, String type) {
            this.name = name;
            this.label = label;
            this.type = type;
        }
    }

    /** 一行客户料号。字段名与 {@code productHubTypes.ts} 的 {@code CustomerPartItem} 逐字对齐。 */
    public static class Item {
        public String customerNo;
        /**
         * 由后端 {@code LEFT JOIN customer c ON c.code = t.customer_no} 得出。
         *
         * <p>🚨 {@code customer} 表<b>没有 {@code customer_no} 列</b>，客户编号列名是 {@code code}。
         * <p>⚠️ 必须 {@code LEFT JOIN}：实测样例 17 行里有 3 行 JOIN 不到
         * （{@code Q13CUST0617}×2、{@code C1}×1 未在客户档案登记）。用 {@code INNER JOIN}
         * 会<b>静默丢掉这 3 行</b>，总数从 17 变 14 —— AC-2 断言的正是 17。
         * JOIN 不到时本字段回 {@code null}，前端渲染 {@code —}（AC-2），这是现网真实状态不是缺陷。
         */
        public String customerName;
        public String customerPartName;
        public String customerProductNo;
        public String customerDrawingNo;
        public String materialNo;
    }

    /** 总行数（服务端 {@code COUNT(*)}，与分页无关）。 */
    public long total;
    /** 列元数据，<b>按数据集下发，前端不写死</b>。 */
    public List<Column> columns;
    /** 当前页的行。 */
    public List<Item> items;

    public DsCustomerParts() {}

    public DsCustomerParts(long total, List<Column> columns, List<Item> items) {
        this.total = total;
        this.columns = columns;
        this.items = items;
    }
}
