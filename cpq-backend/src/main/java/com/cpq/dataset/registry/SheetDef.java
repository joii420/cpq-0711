package com.cpq.dataset.registry;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;

/**
 * 一个 Excel sheet ↔ 一张主表 的元数据（task-260902 · backtask B-3 · 需求文档 R-1）。
 *
 * <p>🚨 {@link #sheetName} <b>必须与 Excel sheet 名逐字相等</b> —— 导入 Phase 1 靠它匹配 sheet
 * （不匹配就报 {@code sheet「X」不属于{数据集中文名}数据集}，AC-34）。
 * 同理 {@code ColumnDef.label} 必须与 Excel 表头中文列名逐字相等（表头校验 + 错误报告文案）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class SheetDef {

    /** 每张主表统一追加的系统列（R-1），顺序与迁移一致。 */
    public static final List<String> SYSTEM_COLUMNS =
            List.of("source", "created_at", "created_by", "updated_at", "updated_by");
    /** 带版本表额外追加的两列（R-1）。 */
    public static final List<String> VERSION_COLUMNS = List.of("version_no", "row_fingerprint");
    /** _history 相对主表额外追加的三列（R-5）。 */
    public static final List<String> ARCHIVE_COLUMNS =
            List.of("archived_at", "archived_by", "archive_reason");
    /**
     * task-260907 · B-1：<b>报价侧</b>客户维度列。
     *
     * <p>🚨 <b>刻意做成「静态系统列」而不是 {@link ColumnDef}</b>：
     * {@code DatasetSheetParser} 要求 {@link #persistedColumns()} 的每一列都在 Excel 表头里出现，
     * 而 {@code customer_no} 的值来自<b>导入时选客户的下拉</b>，不来自 Excel
     * （16 个 sheet 里只有「客户料号」自带客户编号列）。声明成 {@code ColumnDef} 会让其余 15 张
     * sheet 表头校验全报「缺列」⇒ 整份 Excel 拒收。
     * 走静态系统列则「进启动自检、不进表头校验」，正是所需形状。
     *
     * <p>⚠️ 只对报价侧生效（{@link #customerScoped}）—— 本类三套数据集共用，无条件追加会让
     * 核价两套的启动自检也要求这一列，核价侧当场起不来。
     */
    public static final List<String> CUSTOMER_COLUMNS = List.of("customer_no");
    /** {@link #CUSTOMER_COLUMNS} 唯一那一列的列名，供写入器/SQL 拼接引用。 */
    public static final String CUSTOMER_COLUMN = "customer_no";

    /**
     * task-260907 第二段 · B-2 —— 「来源报价单 id」系统列名（AC-8）。
     *
     * <p>🚨 <b>它刻意不是 {@link ColumnDef}</b>（D-28，主线实查倒逼）：
     * <ul>
     *   <li>{@code DatasetSheetParser:73} 遍历 {@code persistedColumns()}，每个 {@code label}
     *       都必须出现在 Excel 表头，缺一个则整张 sheet 进 {@code missingHeaders} 拒收
     *       ⇒ 声明成 {@code ColumnDef} 会让<b>所有存量报价 Excel 当场导不进去</b>；</li>
     *   <li>它的值来自「哪张报价单回填的」，本来就不来自 Excel。</li>
     * </ul>
     * <p>⚠️ 它同样<b>不进 {@code row_fingerprint}</b>：{@code DatasetFingerprints.columnsOf(sheet)}
     * 只取 {@link #comparedColumns()}（全是 {@code ColumnDef}），天然不会纳入 ——
     * 否则「同样的数据换一张单回填」会被判 UPGRADED，产生虚假升版。
     *
     * <p>⚠️ <b>只对报价侧生效</b>，由 {@link DatasetRegistry#quoteRecordEnabled()} 开关控制。
     * 核价两套若跟着要求这一列，{@code DatasetSchemaSelfCheck} 会因「缺列」<b>让核价侧启动失败</b>。
     */
    public static final String SOURCE_QUOTATION_COLUMN = "source_quotation_id";

    /**
     * task-260907 第二段 · B-2 —— {@code _record} 相对主表额外追加的五列（A0-1 双锚 + 快照上下文）。
     *
     * <p>{@code origin_id}（拍快照时主表那一行的 id，主锚）/ {@code base_row_fingerprint}
     * （拍快照时主表那一行的整行指纹，跨版兜底锚）/ {@code quotation_id}（来源报价单）/
     * {@code base_version_no}（拍快照时主表该组的 {@code version_no}，主表当时无该组则 0）/
     * {@code extend_column}（主表对不齐的字段；D-5：不回填、不参与升版与比对）。
     *
     * <p>🚫 {@code _record} <b>不带</b> {@code version_no} / {@code row_fingerprint} ——
     * 它是报价单快照，不是版本化主数据；升版判定一律由 {@code VersionedGroupWriter} 在主表上做（AC-9）。
     */
    public static final List<String> RECORD_COLUMNS =
            List.of("quotation_id", "origin_id", "base_row_fingerprint", "base_version_no", "extend_column");

    /**
     * task-260907 第二段 —— {@code _record} 的客户维度列（{@code varchar(20) NOT NULL}，取值 =
     * {@code customer.code}）。
     *
     * <p>列定义由上游 {@code task-260907-报价侧加客户维度} 的 {@code A0-1} 裁决，本段照抄不改（D-27）。
     * <p>⚠️ 单列成常量、并在 {@link #expectedRecordColumns} 里走 {@code LinkedHashSet} 去重，
     * 是为了让「上游把 {@code customer_no} 加进主表业务列之后」本方法<b>不会把它数两遍</b> ——
     * 那会让自检报「缺列/多列」这类完全误导的错。
     */
    public static final String RECORD_CUSTOMER_COLUMN = "customer_no";

    public final String sheetKey;      // 大写下划线，如 MATERIAL_BOM
    public final String sheetName;     // Excel sheet 名，逐字相等
    public final String axisColumn;    // 带版本表的轴列；免版本表为 null
    public final String axisLabel;
    public final int sortOrder;
    public final boolean versioned;
    public final List<ColumnDef> columns;

    @JsonIgnore public final String tableName;
    /** 免版本表的主键（R-2）；带版本表为空。 */
    @JsonIgnore public final List<String> primaryKeyColumns;

    /**
     * task-260907 · B-1/B-2：本表是否带 {@code customer_no} 静态系统列（报价侧专属）。
     * <p>由 {@link AbstractDatasetRegistry#reg} 在登记时统一注入：
     * {@code 数据集是报价侧 && 该 sheet 自己没有声明 customer_no 业务列}。
     * 后一半是为了排除「客户料号」表 —— 它的 {@code customer_no} 来自 Excel、是真 {@link ColumnDef}，
     * 再叠一层系统列会在自检里出现重复列名。
     */
    @JsonIgnore private boolean customerScoped;
    /**
     * task-260907 · B-6：{@code customer_no} 是否参与本表的<b>业务唯一索引</b>（免版本表的 ON CONFLICT 目标）。
     * <p>只有各数据集的<b>物料表</b>为 true（{@code uq_ds_quote_material} 由 B-6 扩成
     * {@code (customer_no, material_no)}）。{@code ds_quote_plating_scheme} 的
     * {@code uq(scheme_no, scheme_version, item_seq)} 与客户无关，B-6 明令不动 ⇒ 它为 false。
     */
    @JsonIgnore private boolean customerKeyed;

    private SheetDef(String sheetKey, String sheetName, String tableName, boolean versioned, int sortOrder,
                     String axisColumn, String axisLabel, List<String> pk, List<ColumnDef> columns) {
        this.sheetKey = sheetKey;
        this.sheetName = sheetName;
        this.tableName = tableName;
        this.versioned = versioned;
        this.sortOrder = sortOrder;
        this.axisColumn = axisColumn;
        this.axisLabel = axisLabel;
        this.primaryKeyColumns = List.copyOf(pk);
        this.columns = List.copyOf(columns);
    }

    public static SheetDef versioned(String sheetKey, String sheetName, String tableName, int sortOrder,
                                     String axisColumn, String axisLabel, List<ColumnDef> columns) {
        return new SheetDef(sheetKey, sheetName, tableName, true, sortOrder, axisColumn, axisLabel,
                List.of(), columns);
    }

    public static SheetDef unversioned(String sheetKey, String sheetName, String tableName, int sortOrder,
                                       List<String> primaryKeyColumns, List<ColumnDef> columns) {
        return new SheetDef(sheetKey, sheetName, tableName, false, sortOrder, null, null,
                primaryKeyColumns, columns);
    }

    /**
     * 由 {@link AbstractDatasetRegistry#reg} 在登记期调用一次，注入客户维度开关。
     * <p>包内可见 + 幂等赋值：{@link SheetDef} 对外仍是不可变的元数据。
     */
    void applyCustomerScope(boolean scoped, boolean keyed) {
        this.customerScoped = scoped;
        this.customerKeyed = keyed;
    }

    /** 本表是否带 {@code customer_no} 静态系统列（报价侧 = true，核价两套 = false）。 */
    @JsonIgnore
    public boolean customerScoped() {
        return customerScoped;
    }

    /**
     * task-260907 · B-2：<b>复合轴</b>的列清单，顺序 = 写入器拼 SQL 谓词的顺序。
     * <p>报价侧带版本表 = {@code [customer_no, material_no]}；核价两套 = {@code [production_no]}。
     * 免版本表返回空（它们走 {@link #conflictKeyColumns()}）。
     *
     * <p>🚨 单列时<b>逐字退化成原来的形态</b> —— 核价两套的写入 SQL 与改动前一模一样（AC-6）。
     *
     * <h3>📍 这里就是整组删除的轴的<b>唯一</b>来源（2026-09-07 立碑）</h3>
     * 本方法看起来只是个元数据 getter，实际是承重的：
     * <pre>
     *   VersionedGroupWriter:295  axisPredicate() → sqlIdents(sheet.axisColumns())
     *                      :203  upPred = axisPredicate(...)
     *                      :220  DELETE FROM &lt;表&gt; WHERE &lt;upPred&gt;      ← 整组删除
     *                      :152  axisSelect / axisArity（读现状 + 回读轴键）
     * </pre>
     * 全工程引用 <b>4 处</b>（均在 {@code VersionedGroupWriter}）。
     *
     * <p>⚠️ <b>曾被误判为「引用数 = 0 的死代码」</b> —— 起因是按 {@code axisKeyColumns} 这个
     * <b>不存在的名字</b>去 grep，空结果被当成了「没人用」。据此差点删掉它，那会让整组删除失去轴。
     * ⇒ 教训：grep 空结果先确认<b>符号名拼对了</b>，再谈「无引用」。
     *
     * <p>🔒 想改本方法的返回值前，先读 {@code AbstractDatasetRegistry#reg} 里那条启动期断言 ——
     * 它保护的正是「{@code customerScoped} 被意外置 false ⇒ 本方法退回单列 ⇒ 跨客户静默删数据」。
     */
    @JsonIgnore
    public List<String> axisColumns() {
        if (axisColumn == null) return List.of();
        return customerScoped ? List.of(CUSTOMER_COLUMN, axisColumn) : List.of(axisColumn);
    }

    /**
     * 免版本表的 {@code ON CONFLICT} 目标列（= 库里那条业务唯一索引的列）。
     *
     * <p>🚫 <b>刻意不把 {@code customer_no} 塞进 {@link #primaryKeyColumns}</b>：那个字段还被
     * {@code DatasetImportValidator#validateDuplicateKeys} 用来做「同一份 Excel 内主键重复」的校验，
     * 而它按 {@code row.get(列名)} 取值 —— {@code customer_no} 不在 Excel 行里，取到空就会
     * <b>整表跳过查重</b>（AC-63 静默失效）。所以唯一索引的口径单独用本方法表达。
     */
    @JsonIgnore
    public List<String> conflictKeyColumns() {
        if (primaryKeyColumns.isEmpty()) return primaryKeyColumns;
        if (!customerKeyed) return primaryKeyColumns;
        List<String> out = new ArrayList<>(primaryKeyColumns.size() + 1);
        out.add(CUSTOMER_COLUMN);
        out.addAll(primaryKeyColumns);
        return List.copyOf(out);
    }

    /** {@code _history} 表名；免版本表返回 null（AC-4：这 6 张表没有 _history）。 */
    @JsonIgnore
    public String historyTable() {
        return versioned ? tableName + "_history" : null;
    }

    /**
     * task-260907 第二段 · B-1 —— {@code _record} 表名；免版本表返回 null。
     *
     * <p>🚫 免版本三表（{@code ds_quote_material} / {@code ds_quote_customer_part} /
     * {@code ds_quote_plating_scheme}）不建 {@code _record} —— 用户原话「这三张无版本的表定义的
     * 就是不需要回填的，是基础资料，不是料件组成版本资料」（D-8）。
     * 判据是 {@link #versioned}，🚫 不许写死表名（表会增）。
     */
    @JsonIgnore
    public String recordTable() {
        return versioned ? tableName + "_record" : null;
    }

    /** 建了 DB 字段的业务列（排除白底 NAME 列），顺序 = 建表字段顺序。 */
    @JsonIgnore
    public List<ColumnDef> persistedColumns() {
        List<ColumnDef> out = new ArrayList<>();
        for (ColumnDef c : columns) if (c.persisted) out.add(c);
        return out;
    }

    /**
     * 参与行指纹的列，<b>按 Registry 声明顺序 = 建表字段顺序</b>（R-3）。
     * 顺序即协议：换顺序 = 换指纹 = 全库虚假升版。
     */
    @JsonIgnore
    public List<ColumnDef> comparedColumns() {
        List<ColumnDef> out = new ArrayList<>();
        for (ColumnDef c : columns) if (c.persisted && c.compared) out.add(c);
        return out;
    }

    /** 白底名称列（不建字段，后端 JOIN 带出）。 */
    @JsonIgnore
    public List<ColumnDef> nameColumns() {
        List<ColumnDef> out = new ArrayList<>();
        for (ColumnDef c : columns) if (!c.persisted) out.add(c);
        return out;
    }

    @JsonIgnore
    public ColumnDef column(String name) {
        for (ColumnDef c : columns) if (c.name.equals(name)) return c;
        return null;
    }

    /** 主表在库里应有的<b>全部</b>列名（含 id / 版本列 / 系统列）—— 启动自检比对基准。 */
    @JsonIgnore
    public List<String> expectedTableColumns() {
        return expectedTableColumns(false);
    }

    /**
     * 主表期望列。{@code withSourceQuotation=true} 时追加 {@link #SOURCE_QUOTATION_COLUMN}（AC-8）。
     *
     * <p>该开关由 {@link DatasetRegistry#quoteRecordEnabled()} 提供，<b>只有报价侧为 true</b> ——
     * 核价两套跟着要求这列会让它们启动失败（那两套的主表没有这列）。
     */
    @JsonIgnore
    public List<String> expectedTableColumns(boolean withSourceQuotation) {
        List<String> out = new ArrayList<>();
        out.add("id");
        for (ColumnDef c : persistedColumns()) out.add(c.name);
        if (versioned) out.addAll(VERSION_COLUMNS);
        if (versioned && withSourceQuotation) out.add(SOURCE_QUOTATION_COLUMN);
        out.addAll(SYSTEM_COLUMNS);
        if (customerScoped) out.addAll(CUSTOMER_COLUMNS);   // task-260907 · B-1
        return out;
    }

    /** {@code _history} 表应有的全部列名（主表 id → origin_id，本表自带新 id）+ 归档三列（R-5 / AC-5）。 */
    @JsonIgnore
    public List<String> expectedHistoryColumns() {
        return expectedHistoryColumns(false);
    }

    /**
     * {@code _history} 期望列。{@code withSourceQuotation=true} 时追加
     * {@link #SOURCE_QUOTATION_COLUMN}（B-3）。
     *
     * <p>为什么该列要进 {@code _history} 镜像（闸门 A0「无岔路」留痕）：
     * {@code VersionedGroupWriter.archive()} 是逐列复制 {@code persistedColumns()} + 归档三列的
     * {@code INSERT…SELECT}，主表加列而镜像不加，反而要给自检写特例；且「这个历史版本由哪张单产生」
     * 本身就是追溯价值所在。
     */
    @JsonIgnore
    public List<String> expectedHistoryColumns(boolean withSourceQuotation) {
        if (!versioned) return List.of();
        List<String> out = new ArrayList<>();
        out.add("id");
        out.add("origin_id");
        for (ColumnDef c : persistedColumns()) out.add(c.name);
        out.addAll(VERSION_COLUMNS);
        if (withSourceQuotation) out.add(SOURCE_QUOTATION_COLUMN);
        out.addAll(SYSTEM_COLUMNS);
        out.addAll(ARCHIVE_COLUMNS);
        if (customerScoped) out.addAll(CUSTOMER_COLUMNS);   // task-260907 · B-1（镜像表与主表列定义逐字一致）
        return out;
    }

    /**
     * task-260907 第二段 · B-2 —— {@code _record} 表应有的全部列名（AC-1② / AC-1⑤）。
     *
     * <p>= {@code id}（本表自增）+ {@link #RECORD_COLUMNS} + 主表全部业务列
     * + {@code extraColumns}（报价侧两张 BOM 表的 {@code element_price}，S-3）+ 系统列。
     *
     * <p>🚫 <b>没有</b> {@code version_no} / {@code row_fingerprint} / {@link #SOURCE_QUOTATION_COLUMN}：
     * 前两者属主表的版本化语义（AC-9 要求一律由 {@code VersionedGroupWriter} 负责），
     * 后者是「主表这一版由哪张单写的」——而 {@code _record} 自己就有 {@code quotation_id}。
     *
     * <p>{@code customer_no}（{@link #RECORD_CUSTOMER_COLUMN}）恒在：上游 A0-1 已裁决为
     * {@code varchar(20) NOT NULL}，{@code _record} 由本段自己写入、不经
     * {@code VersionedGroupWriter}，故「本表有、主表暂时没有」是允许的过渡态。
     *
     * @param extraColumns 该 sheet 的 {@code _record} 相对主表的附加业务列，见
     *                     {@link DatasetRegistry#recordExtraColumns(SheetDef)}；null 视同空
     */
    @JsonIgnore
    public List<String> expectedRecordColumns(java.util.Collection<String> extraColumns) {
        if (!versioned) return List.of();
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        out.add("id");
        out.addAll(RECORD_COLUMNS);
        for (ColumnDef c : persistedColumns()) out.add(c.name);
        if (extraColumns != null) out.addAll(extraColumns);
        out.add(RECORD_CUSTOMER_COLUMN);
        out.addAll(SYSTEM_COLUMNS);
        return new ArrayList<>(out);
    }
}
