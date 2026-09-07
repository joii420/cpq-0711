package com.cpq.builder.compiler;

/**
 * 别名生成（task-260819 B-6/B-40，AC-11 / AC-110）。
 *
 * <p>视图列名（{@code viewColumn}）是 {@code (方言, Sheet简称, 列名)} 的**纯函数**——同一组输入
 * 任何时候调用结果都逐字相同（AC-11②：删除别的列不改变本列的视图列名）。
 *
 * <p>🔒 <b>别名规则按侧不统一，且必须保持不统一（AC-110）</b>：
 * <ul>
 *   <li>{@code QUOTE} → {@code _<Sheet简称>_<列显示名>}（D-13）</li>
 *   <li>{@code COST_BASIC} / {@code COST_DETAIL} → 裸英文 {@code dbColumn}，无前缀</li>
 * </ul>
 * 🚫 <b>不要"顺手统一"</b>：报价侧渲染层拿中文字段名当公式 token（N-10 / {@code BL-0090} 未解），
 * 统一别名规则等于改掉全部存量报价公式里的标识符，会当场打断存量公式。
 *
 * <p>价格策略原子组的两列（元素单价/货币）例外，不带前缀，直接用列显示名本身（D-09，AC-1③）。
 */
public final class AliasGenerator {

    private AliasGenerator() {}

    /** QUOTE 方言下的业务列别名：{@code _<shortName>_<displayName>}。 */
    public static String quoteViewColumn(String shortName, String displayName) {
        return "_" + shortName + "_" + displayName;
    }

    /** 按方言生成业务列别名（核价两套：英文 dbColumn，无前缀，见 AC-110）。 */
    public static String viewColumn(CompileDialect dialect, String shortName, String displayName, String dbColumn) {
        if (dialect.isCosting()) {
            return dbColumn;
        }
        return quoteViewColumn(shortName, displayName);
    }

    /** 价格策略原子组的裸列名（不带前缀，三个方言下同形——都是纯英文标识符）。 */
    public static String bareColumn(String displayName) {
        return displayName;
    }
}
