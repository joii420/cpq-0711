package com.cpq.dataset.fingerprint;

import com.cpq.dataset.registry.ColumnDef;
import com.cpq.dataset.registry.SheetDef;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Registry ↔ 指纹算法的桥（task-260902 · B-4）。
 *
 * <p>{@link RowFingerprints} 刻意保持无依赖（可脱离 CDI 单测 AC-16/17/18 的等价性用例），
 * 本类负责把 {@link SheetDef#comparedColumns()} 转成 {@link FpColumn}，是二者之间<b>唯一</b>的转换点。
 *
 * <p>🚨 <b>列顺序即协议</b>：指纹按 Registry 声明顺序（= 建表字段顺序）拼接。
 * 调换 Registry 里两列的先后 = 全库指纹变化 = 下一次导入全部虚假升版。改 Registry 顺序前先想清楚这件事。
 */
public final class DatasetFingerprints {

    private DatasetFingerprints() {}

    /** 该 sheet 参与指纹的列（compared 且 persisted），顺序 = 声明顺序。 */
    public static List<FpColumn> columnsOf(SheetDef sheet) {
        List<ColumnDef> compared = sheet.comparedColumns();
        List<FpColumn> out = new ArrayList<>(compared.size());
        for (ColumnDef c : compared) out.add(new FpColumn(c.name, c.type, c.scale));
        return out;
    }

    /** 计算一行的指纹（64 位小写 hex）。{@code row} 的 key = DB 列名。 */
    public static String compute(SheetDef sheet, Map<String, Object> row) {
        return RowFingerprints.compute(columnsOf(sheet), row);
    }

    /** 批量：一组行的指纹列表，顺序与入参一致。列定义只解析一次。 */
    public static List<String> computeAll(SheetDef sheet, List<Map<String, Object>> rows) {
        List<FpColumn> cols = columnsOf(sheet);          // ← 循环外解析一次
        List<String> out = new ArrayList<>(rows.size());
        for (Map<String, Object> r : rows) out.add(RowFingerprints.compute(cols, r));
        return out;
    }
}
