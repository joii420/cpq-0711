package com.cpq.dataset.fingerprint;

import com.cpq.dataset.registry.ColumnDef;
import com.cpq.dataset.registry.SheetDef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * task-260902 · B-4：Registry → 指纹 的桥接（{@link DatasetFingerprints}）单测。
 * <p>纯 JUnit，不起 Quarkus、不连库。
 */
class DatasetFingerprintsTest {

    private static SheetDef sheet() {
        return SheetDef.versioned("T", "测试表", "ds_cost_basic_material_bom", 1, "production_no", "生产料号",
                List.of(
                        ColumnDef.col("production_no", "生产料号", "AXIS", "STRING", "varchar(128)", true, false),
                        ColumnDef.col("item_seq", "项次", "VALUE", "NUMBER", "integer", true, false),
                        ColumnDef.col("component_no", "组成料号", "SUBDIM", "STRING", "varchar(128)", true, true),
                        ColumnDef.col("component_qty", "组成用量", "VALUE", "DECIMAL", "numeric(26,12)", false, true),
                        ColumnDef.nameCol("component_name", "组成料号名称", "component_no",
                                "material_master", "material_no", "material_name")));
    }

    private static Map<String, Object> row(Object seq, Object no, Object qty) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("production_no", "3120014539");
        m.put("item_seq", seq);
        m.put("component_no", no);
        m.put("component_qty", qty);
        m.put("component_name", "会变的名称");     // NAME 列，不该影响指纹
        return m;
    }

    @Test
    @DisplayName("只取 compared 且 persisted 的列，顺序 = Registry 声明顺序")
    void comparedColumnsOnly() {
        List<FpColumn> cols = DatasetFingerprints.columnsOf(sheet());
        assertEquals(List.of("component_no", "component_qty"),
                cols.stream().map(FpColumn::name).toList(),
                "轴列/项次(非比对项)/NAME 列都不得进指纹");
        assertEquals(12, cols.get(1).scale(), "numeric(26,12) 的 scale 必须被解析出来");
    }

    @Test
    @DisplayName("AC-17 非比对项（项次）变化不改指纹；NAME 列变化也不改指纹")
    void nonComparedAndNameColumnsIgnored() {
        SheetDef s = sheet();
        String a = DatasetFingerprints.compute(s, row(10, "S-001", "1"));
        String b = DatasetFingerprints.compute(s, row(99, "S-001", "1"));
        assertEquals(a, b, "项次 10→99 不得改变指纹");

        Map<String, Object> r = row(10, "S-001", "1");
        r.put("component_name", "另一个名称");
        assertEquals(a, DatasetFingerprints.compute(s, r), "NAME 列不进指纹");
    }

    @Test
    @DisplayName("AC-14 比对项变化必须改指纹")
    void comparedChangeAltersFingerprint() {
        SheetDef s = sheet();
        assertNotEquals(DatasetFingerprints.compute(s, row(10, "S-001", "1")),
                        DatasetFingerprints.compute(s, row(10, "S-001", "2")));
        assertNotEquals(DatasetFingerprints.compute(s, row(10, "S-001", "1")),
                        DatasetFingerprints.compute(s, row(10, "S-002", "1")));
    }

    @Test
    @DisplayName("AC-18 数值等价：1 / 1.0 / 1.000000 / 库中回读的 1.000000000000 同指纹")
    void numericEquivalenceIncludingDbRoundTrip() {
        SheetDef s = sheet();
        String base = DatasetFingerprints.compute(s, row(10, "S-001", "1"));
        for (Object v : List.of("1.0", "1.00", "1.000000", "1.000000000000",
                                new java.math.BigDecimal("1.000000000000"))) {
            assertEquals(base, DatasetFingerprints.compute(s, row(10, "S-001", v)),
                    "值 " + v + " 应与 1 同指纹（否则维护端读回后保存会虚假升版）");
        }
    }

    @Test
    @DisplayName("scale 归一：numeric(26,12) 列上 1.0000000000004 与 1 同指纹")
    void scaleNormalizationFromPgType() {
        SheetDef s = sheet();
        assertEquals(DatasetFingerprints.compute(s, row(10, "S-001", "1")),
                     DatasetFingerprints.compute(s, row(10, "S-001", "1.0000000000004")));
    }

    @Test
    @DisplayName("AC-16 多重集：行序对调判等，值不同判不等")
    void multisetOverRows() {
        SheetDef s = sheet();
        List<Map<String, Object>> r1 = List.of(row(10, "S-001", "1"), row(20, "S-002", "2"));
        List<Map<String, Object>> r2 = List.of(row(20, "S-002", "2"), row(10, "S-001", "1"));
        List<Map<String, Object>> r3 = List.of(row(10, "S-001", "1"), row(20, "S-002", "3"));
        assertTrue(RowFingerprints.sameMultiset(
                DatasetFingerprints.computeAll(s, r1), DatasetFingerprints.computeAll(s, r2)));
        assertFalse(RowFingerprints.sameMultiset(
                DatasetFingerprints.computeAll(s, r1), DatasetFingerprints.computeAll(s, r3)));
    }
}
