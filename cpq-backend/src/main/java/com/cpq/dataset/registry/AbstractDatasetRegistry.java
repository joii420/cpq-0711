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
    private final Map<String, SheetDef> byKey = new LinkedHashMap<>();

    protected AbstractDatasetRegistry(String datasetKey, String datasetLabel, String tablePrefix,
                                      String axisColumn, String axisLabel, String materialTable) {
        this.datasetKey = datasetKey;
        this.datasetLabel = datasetLabel;
        this.tablePrefix = tablePrefix;
        this.axisColumn = axisColumn;
        this.axisLabel = axisLabel;
        this.materialTable = materialTable;
    }

    /** 登记一个 sheet；同名 sheetKey / sheetName / tableName 重复即抛（防复制粘贴漏改）。 */
    protected void reg(SheetDef def) {
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
    @Override public List<SheetDef> sheets() { return List.copyOf(byKey.values()); }
}
