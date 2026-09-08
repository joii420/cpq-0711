package com.cpq.dataset.registry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 三套 Registry 的共同骨架：登记 + 一致性自检（sortOrder / sheetKey / 轴列 / 主键 唯一且齐备）。 */
public abstract class AbstractDatasetRegistry implements DatasetRegistry {

    private final String datasetKey;
    private final String datasetLabel;
    private final String tablePrefix;
    private final String axisColumn;
    private final String axisLabel;
    private final String materialTable;
    /** task-260907 · B-1：本数据集是否带客户维度（报价侧 true，核价两套 false）。 */
    private final boolean customerScoped;
    private final Map<String, SheetDef> byKey = new LinkedHashMap<>();

    /** 兼容既有三参形态：不带客户维度（核价两套走这条）。 */
    protected AbstractDatasetRegistry(String datasetKey, String datasetLabel, String tablePrefix,
                                      String axisColumn, String axisLabel, String materialTable) {
        this(datasetKey, datasetLabel, tablePrefix, axisColumn, axisLabel, materialTable, false);
    }

    protected AbstractDatasetRegistry(String datasetKey, String datasetLabel, String tablePrefix,
                                      String axisColumn, String axisLabel, String materialTable,
                                      boolean customerScoped) {
        this.datasetKey = datasetKey;
        this.datasetLabel = datasetLabel;
        this.tablePrefix = tablePrefix;
        this.axisColumn = axisColumn;
        this.axisLabel = axisLabel;
        this.materialTable = materialTable;
        this.customerScoped = customerScoped;
    }

    /** 登记一个 sheet；同名 sheetKey / sheetName / tableName 重复即抛（防复制粘贴漏改）。 */
    protected void reg(SheetDef def) {
        // task-260907 · B-1/B-2/B-6：登记期注入客户维度开关（必须早于下面的轴列/主键校验）。
        //  · scoped —— 报价侧且该 sheet 自己没声明 customer_no 业务列（排除「客户料号」表：
        //    它的 customer_no 来自 Excel、是真 ColumnDef，再叠系统列会在启动自检里出现重复列）。
        //  · keyed  —— 只有物料表的业务唯一索引扩成 (customer_no, 料号)（B-6）；
        //    电镀方案表 uq(scheme_no, scheme_version, item_seq) 与客户无关，B-6 明令不动。
        // 🚨 task-260907 收尾：启动期硬断言 —— 报价侧【带版本】的 sheet 不许自带 customer_no 业务列。
        //
        // 为什么需要它（今天零 sheet 命中，所以这不是修 bug，是把一个巧合钉成契约）：
        //   下面那行 scoped 的语义是「本 sheet 没自己声明 customer_no ⇒ 由系统列补上客户维度」。
        //   报价侧 16 个 sheet 里唯一让 scoped=false 的是 CUSTOMER_PART，而它【恰好是免版本的】
        //   ——走 upsert，自然键 (customer_no, customer_product_no) 本来就含客户 ⇒ 今天不可达。
        //
        // 一旦有人加一个「带版本 + 自带 customer_no 业务列」的报价 sheet：
        //   scoped=false ⇒ SheetDef.axisColumns() 退回单列 [material_no]
        //              ⇒ VersionedGroupWriter:295 axisPredicate 拼出单列谓词
        //              ⇒ :220 的整组 DELETE 按单列轴执行
        //              ⇒ 客户 A 导入料号 X 会把客户 B 的料号 X 整组删掉。
        //   🚨 失败形态：不报错、不撞键、不留痕（13 张带版本表只有 PRIMARY KEY(id)，无业务唯一索引）。
        //   🚨 DatasetSchemaSelfCheck 抓不到：它比的是【列集】，而那个 customer_no 是真 ColumnDef，
        //      列对得上、自检照过。⇒ 除本断言外没有任何守卫。
        //
        // ⚠️ 只拦「带版本」那一支：免版本表（CUSTOMER_PART）走 conflictKeyColumns()/ON CONFLICT，
        //    自带 customer_no 是正确形态，拦了它启动就挂。
        if (customerScoped && def.versioned && def.column(SheetDef.CUSTOMER_COLUMN) != null) {
            throw new IllegalStateException(
                datasetKey + " 的带版本 sheet [" + def.sheetKey + " / " + def.tableName
                + "] 自带 " + SheetDef.CUSTOMER_COLUMN + " 业务列，这是不允许的："
                + "带版本表的客户维度必须走 SheetDef 的静态系统列（customerScoped=true），"
                + "自带业务列会让 customerScoped 被置为 false，"
                + "于是 SheetDef.axisColumns() 退回单列轴 ⇒ VersionedGroupWriter 的整组删除"
                + "（该类 :220 DELETE，谓词来自 :295 axisPredicate）只按料号删，"
                + "跨客户静默删除对方数据且不报错、不撞键、不留痕。"
                + "修法：把该列从 ColumnDef 清单里去掉，客户号改由系统列注入；"
                + "若这张表确实需要 Excel 里的客户列，请改成免版本表并用 conflictKeyColumns() 表达唯一键。");
        }

        boolean scoped = customerScoped && def.column(SheetDef.CUSTOMER_COLUMN) == null;
        def.applyCustomerScope(scoped, scoped && !def.versioned && def.tableName.equals(materialTable));

        if (byKey.putIfAbsent(def.sheetKey, def) != null) {
            throw new IllegalStateException(datasetKey + " 重复 sheetKey: " + def.sheetKey);
        }
        for (SheetDef s : byKey.values()) {
            if (s != def && s.sheetName.equals(def.sheetName)) {
                throw new IllegalStateException(datasetKey + " 重复 sheetName: " + def.sheetName);
            }
            if (s != def && s.tableName.equals(def.tableName)) {
                throw new IllegalStateException(datasetKey + " 重复 tableName: " + def.tableName);
            }
        }
        if (!def.tableName.startsWith(tablePrefix)) {
            throw new IllegalStateException(def.tableName + " 不以 " + tablePrefix + " 开头");
        }
        if (def.versioned) {
            if (!axisColumn.equals(def.axisColumn) || def.column(axisColumn) == null) {
                throw new IllegalStateException(def.tableName + " 轴列缺失或与数据集不一致: " + def.axisColumn);
            }
            if (!def.column(axisColumn).required) {
                throw new IllegalStateException(def.tableName + " 轴列必须 required=true（R-1 凌驾底色）");
            }
        } else {
            if (def.primaryKeyColumns.isEmpty()) {
                throw new IllegalStateException(def.tableName + " 免版本表必须声明主键（R-2）");
            }
            for (String pk : def.primaryKeyColumns) {
                ColumnDef c = def.column(pk);
                if (c == null || !c.persisted) throw new IllegalStateException(def.tableName + " 主键列不存在: " + pk);
                if (!c.required) throw new IllegalStateException(def.tableName + " 主键列必须 required=true: " + pk);
            }
        }
        List<String> names = new ArrayList<>();
        for (ColumnDef c : def.columns) {
            if (names.contains(c.name)) throw new IllegalStateException(def.tableName + " 重复列名: " + c.name);
            names.add(c.name);
        }
    }

    @Override public String datasetKey()   { return datasetKey; }
    @Override public String datasetLabel() { return datasetLabel; }
    @Override public String tablePrefix()  { return tablePrefix; }
    @Override public String axisColumn()   { return axisColumn; }
    @Override public String axisLabel()    { return axisLabel; }
    @Override public String materialTable(){ return materialTable; }
    @Override public boolean customerScoped() { return customerScoped; }
    @Override public List<SheetDef> sheets() { return List.copyOf(byKey.values()); }
}
