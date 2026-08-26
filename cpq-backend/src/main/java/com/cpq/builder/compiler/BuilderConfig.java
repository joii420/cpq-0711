package com.cpq.builder.compiler;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/**
 * {@code builder_config} JSONB 的 Java 对应（task-260819，api.md §2.1）。
 *
 * <p>字段寻址约定（api.md 未逐字规定，本轮实现约定，写在这里供前端对接核对）：
 * {@code columns[].sourceNodeKey} / {@code sourceColumn} 恒指向"值实际所在的那个语义节点的
 * 物理列"——直接列如此，经 LOOKUP/SUB/GRAIN 到达的列也如此（sourceNodeKey=目标节点的
 * node_key，sourceColumn=目标节点自己的 db_column）；唯一例外是"多源 COALESCE 查名"
 * （D-35 coalesce_group）——此时 sourceNodeKey/sourceColumn 指向 fallback_order=0 的那个目标
 * 节点及其列，编译器据此反查 {@code coalesce_group} 自动展开整组 LEFT JOIN + COALESCE。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class BuilderConfig {
    public Integer builderVersion;
    public String tabType;
    public String variantKey;
    public Map<String, Object> switches;
    public List<ColumnConfig> columns;
    public PriceStrategyConfig priceStrategy;
    /**
     * task-260819 B-22（D-59）：编译方言（"QUOTE"/"COSTING"），仅 {@code POST /compile} 消费
     * （{@code BuilderService#doCompile}），不是 {@code builder_config} JSONB 的持久化字段——
     * api.md §2.1 的落库示例没有它。缺省/无法识别时按 QUOTE 处理（与改动前行为一致，零回归）。
     */
    public String dialect;

    public boolean includeChildParts() {
        if (switches == null) return false;
        Object v = switches.get("includeChildParts");
        return Boolean.TRUE.equals(v);
    }

    public static class ColumnConfig {
        public String sourceNodeKey;
        public String sourceColumn;
        public String fieldName;
        /** 系统生成，输入侧忽略——编译器永远自己重新算一遍（AC-11②的"纹丝不动"由此保证）。 */
        public String viewColumn;
        public String fieldType;
        public Boolean isAmount;
        public Boolean inSubtotal;

        /**
         * 编译期回填（不是请求输入）：编译器解出的实际数据类型 / 角色 —— 供保存流程（B-13）
         * 推导 field_type（TEXT→INPUT_TEXT，其余→INPUT_NUMBER）与 row_key_fields/part_no_field
         * 等组件级属性，不需要再反查一次语义图。
         */
        public String resolvedDataType;
        public List<String> resolvedRoles;

        public ColumnConfig() {}

        public ColumnConfig(String sourceNodeKey, String sourceColumn, String fieldName) {
            this.sourceNodeKey = sourceNodeKey;
            this.sourceColumn = sourceColumn;
            this.fieldName = fieldName;
        }
    }

    /** 价格策略原子组绑定（D-09，task-260819 B-9）。 */
    public static class PriceStrategyConfig {
        /** 形态 A（默认）：元素键取自本 Sheet 自身的编码列——不填则编译器按 PRICE 边的左键列自动定位。 */
        public String elementCodeSourceNodeKey;
        public String elementCodeSourceColumn;
        /** 形态 B（AC-23）：元素键改绑到一个无取数来源的手填字段；非空时优先于上面两个字段。 */
        public String elementCodeManualField;
        /** 手填形态下，元素单价 / 货币字段的显示名（不填默认"元素单价"/"货币"）。 */
        public String elementPriceFieldName;
        public String elementCurrencyFieldName;
    }
}
