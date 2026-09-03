package com.cpq.dataset.registry;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 单列的建表 + 渲染 + 校验元数据（task-260902 · backtask B-3）。
 *
 * <p><b>唯一真源</b>：本类的每一个实例都与 {@code V401~V404} 迁移里的一列 <b>一一对应</b>，
 * 两侧同出自 {@code dev-docs/task-260902-.../字段矩阵.md}。运行期由
 * {@link DatasetSchemaSelfCheck} 在启动时逐表比对 {@code information_schema.columns}，
 * <b>对不上直接启动失败</b> —— 这是为了硬拦 {@code PricingSheetRegistry} 类注释自陈的"双写漂移"
 * （改 handler 忘了改 Registry → 虚假升版 / 匹配错组，且完全静默）。
 *
 * <p>JSON 形态对齐 {@code api.md §2}（{@code name/label/role/type/editable/required/compared/dropdown}）。
 * 带 {@link JsonIgnore} 的字段是服务端内部元数据（建表类型、长度上限、主数据校验开关、NAME 取数来源），
 * <b>不进契约</b>，前端不消费。
 *
 * <p>role 语义（api.md §2）：
 * <ul>
 *   <li>{@code AXIS}   — 轴列（销售料号 / 生产料号）。前端不渲染，抽屉上下文锁定；一律必填（R-1 凌驾底色）。</li>
 *   <li>{@code SUBDIM} — 子维度编码列（工序编号 / 元素代码 / 材质料号 / 免版本表主键列），走下拉。</li>
 *   <li>{@code VALUE}  — 普通值列。</li>
 *   <li>{@code NAME}   — 只读名称列，<b>不是数据库字段</b>（字段矩阵里的白底 ❌ 不建），由后端 JOIN 主数据带出。</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class ColumnDef {

    // ── 契约字段（api.md §2）──────────────────────────────────────────────
    public String name;
    public String label;
    public String role;        // AXIS / SUBDIM / VALUE / NAME
    public String type;        // STRING / NUMBER / DECIMAL / ENUM
    public boolean editable;
    public boolean required;
    public boolean compared;
    public Integer scale;      // numeric(p,s) 的 s；非 numeric 为 null
    public Dropdown dropdown;

    // ── 服务端内部元数据（不进契约）────────────────────────────────────────
    /** false = 白底 NAME 列，不建 DB 字段（AC-3）。 */
    @JsonIgnore public boolean persisted = true;
    /** 建表类型原文，如 {@code varchar(128)} / {@code numeric(26,12)} / {@code integer}；NAME 列为 null。 */
    @JsonIgnore public String pgType;
    /** varchar(n) 的 n —— Phase 1 长度校验用（AC-40，禁止静默截断）。 */
    @JsonIgnore public Integer maxLength;
    /** true = 需要做主数据存在性校验（需求文档 R-7 明列的四类：元素 / 工序 / 材质 / 客户）。 */
    @JsonIgnore public boolean masterCheck;
    /** role=NAME 时非空：这一列的值从哪张主数据表 JOIN 出来。 */
    @JsonIgnore public NameSource source;

    private ColumnDef() {}

    // ── 工厂 ────────────────────────────────────────────────────────────
    /** 建字段列的通用工厂。 */
    public static ColumnDef col(String name, String label, String role, String type,
                                String pgType, boolean required, boolean compared) {
        ColumnDef c = new ColumnDef();
        c.name = name; c.label = label; c.role = role; c.type = type;
        c.pgType = pgType; c.required = required; c.compared = compared;
        c.editable = !"AXIS".equals(role);
        c.scale = parseScale(pgType);
        c.maxLength = parseMaxLength(pgType);
        return c;
    }

    /**
     * 白底名称列：<b>不建 DB 字段</b>，由后端按 {@code codeColumn} 的值 JOIN
     * {@code srcTable.srcCodeColumn} 取 {@code srcValueColumn} 带出。
     *
     * @param srcValueColumn 允许为 null —— 主数据表里没有对应的展示列（如材质料号的「尺寸」），后端返 null。
     */
    public static ColumnDef nameCol(String name, String label, String codeColumn,
                                    String srcTable, String srcCodeColumn, String srcValueColumn) {
        ColumnDef c = new ColumnDef();
        c.name = name; c.label = label; c.role = "NAME"; c.type = "STRING";
        c.editable = false; c.persisted = false;
        c.source = new NameSource(codeColumn, srcTable, srcCodeColumn, srcValueColumn);
        return c;
    }

    /** 挂主数据下拉；同时开启 Phase 1 的编码存在性校验（R-7）。 */
    public ColumnDef master(String masterType, String nameColumn) {
        this.dropdown = Dropdown.master(masterType, nameColumn);
        this.masterCheck = true;
        return this;
    }

    /** 挂主数据下拉但<b>不</b>做存在性校验（编码域是多态的，如「投入料号」可能是材质也可能是零件）。 */
    public ColumnDef masterNoCheck(String masterType, String nameColumn) {
        this.dropdown = Dropdown.master(masterType, nameColumn);
        this.masterCheck = false;
        return this;
    }

    /** 固定候选枚举（无字典表，未知值可输入回退 —— 与现有 PricingSheetRegistry 同口径）。 */
    public ColumnDef options(List<String> opts) {
        this.dropdown = Dropdown.enumOf(opts);
        return this;
    }

    private static Integer parseScale(String pgType) {
        if (pgType == null || !pgType.startsWith("numeric(")) return null;
        String inner = pgType.substring("numeric(".length(), pgType.length() - 1);
        return Integer.valueOf(inner.split(",")[1].trim());
    }

    private static Integer parseMaxLength(String pgType) {
        if (pgType == null || !pgType.startsWith("varchar(")) return null;
        return Integer.valueOf(pgType.substring("varchar(".length(), pgType.length() - 1).trim());
    }

    // ── 内嵌类型 ─────────────────────────────────────────────────────────
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static final class Dropdown {
        public String kind;            // MASTER / ENUM / FREE
        public String masterType;      // MASTER 时：material / process / element / recipe / customer（api.md §8）
        public String nameColumn;      // MASTER 时联动的只读 NAME 列
        public List<String> options;   // ENUM 时的候选值

        static Dropdown master(String masterType, String nameColumn) {
            Dropdown d = new Dropdown();
            d.kind = "MASTER"; d.masterType = masterType; d.nameColumn = nameColumn;
            return d;
        }

        static Dropdown enumOf(List<String> options) {
            Dropdown d = new Dropdown();
            d.kind = "ENUM"; d.options = List.copyOf(options);
            return d;
        }
    }

    /**
     * NAME 列的取数来源。
     *
     * @param codeColumn      本表里持有编码的 DB 列（如 {@code operation_no}）
     * @param table           主数据表（如 {@code process_master}）
     * @param codeColumnInSrc 主数据表里与 {@code codeColumn} 比对的列（如 {@code process_no}）
     * @param valueColumn     主数据表里要带出的展示列（如 {@code process_name}）；null = 主数据无此列
     */
    public record NameSource(String codeColumn, String table, String codeColumnInSrc, String valueColumn) {}
}
