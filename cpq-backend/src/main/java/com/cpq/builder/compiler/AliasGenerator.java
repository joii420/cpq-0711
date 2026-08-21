package com.cpq.builder.compiler;

/**
 * 别名生成（task-260819 B-6，AC-11）。
 *
 * <p>视图列名（{@code viewColumn}）是 {@code (Sheet简称, 列名)} 的**纯函数**——同一对输入
 * 任何时候调用结果都逐字相同（AC-11②：删除别的列不改变本列的视图列名）。QUOTE 方言下
 * 形如 {@code _<Sheet简称>_<列名>}（D-13）；价格策略原子组的两列（元素单价/货币）例外，
 * 不带前缀，直接用列显示名本身（D-09，AC-1③）。
 *
 * <p>COSTING 方言（AC-37）：业务列别名为英文 DB 列名本身，不带前缀——与 QUOTE 完全不同的
 * 命名空间，所以生成函数按 {@link CompileDialect} 分支，仍然是纯函数（无副作用/无查库）。
 */
public final class AliasGenerator {

    private AliasGenerator() {}

    /** QUOTE 方言下的业务列别名：{@code _<shortName>_<displayName>}。 */
    public static String quoteViewColumn(String shortName, String displayName) {
        return "_" + shortName + "_" + displayName;
    }

    /** 按方言生成业务列别名（COSTING：英文 dbColumn，无前缀，见 AC-37）。 */
    public static String viewColumn(CompileDialect dialect, String shortName, String displayName, String dbColumn) {
        if (dialect == CompileDialect.COSTING) {
            return dbColumn;
        }
        return quoteViewColumn(shortName, displayName);
    }

    /** 价格策略原子组的裸列名（不带前缀，两个方言下同形——都是纯英文标识符）。 */
    public static String bareColumn(String displayName) {
        return displayName;
    }
}
