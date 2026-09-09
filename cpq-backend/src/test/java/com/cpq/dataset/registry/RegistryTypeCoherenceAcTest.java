package com.cpq.dataset.registry;

import com.cpq.dataset.fingerprint.DatasetFingerprints;
import com.cpq.dataset.fingerprint.RowFingerprints;
import com.cpq.dataset.support.DatasetValues;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D-47：<b>值一个字节没变，却每次核价通过都空转升一版</b>。
 *
 * <h3>缺陷实证（主线亲验）</h3>
 * <pre>
 *   ds_quote_incoming_fixed_fee  v1 → v2 后 12 个物理业务列逐字节相同（md5 8a68bacb5e77cb68）
 *   却  v1 指纹 ff443cb0a75d… ≠ v2 指纹 a5e958db7d3c…   ⇒ 预览判 UPGRADED / tgtV=n+1
 *   与 AC-14「零变更 ⇒ UNCHANGED，version_no 不变、updated_at 不许动」直接冲突
 * </pre>
 *
 * <h3>根因</h3>
 * {@code QuoteRegistry} 把 {@code follow_material_price} 声明成 {@code type="STRING"}，
 * 而它的<b>物理列是 {@code boolean}</b>。{@code ValueNormalizer.isBooleanType} 只认字面
 * {@code "BOOLEAN"} ⇒ 这一列绕过 {@code normalizeBoolean} 的「宽进严出」：
 * <ul>
 *   <li><b>导入侧</b>：{@code ParsedRow} 给的是 Excel 原文（「是」），STRING 分支原样保留 ⇒ 指纹里是「是」；</li>
 *   <li><b>读回侧</b>：DB 列是 boolean，JDBC 返 {@code Boolean} → {@code String.valueOf} ⇒ 指纹里是 {@code "true"}。</li>
 * </ul>
 * 「是」≠「true」⇒ 值相同、指纹分叉。
 *
 * <h3>本用例是「双侧还原」的靶子</h3>
 * <pre>
 *   QuoteRegistry 那一列 type=BOOLEAN（修复在） → 全绿
 *   改回 type=STRING（原缺陷）                  → excelSpellingSurvivesDbRoundTrip 红 + coherence 红
 * </pre>
 * 🚨 <b>非空守卫</b>：{@link #excelSpellingSurvivesDbRoundTrip()} 先断言这一列<b>确实参与指纹</b>
 * （{@code compared=true}）—— 否则「两侧指纹相等」会因为它根本不进指纹而恒真。
 *
 * <p>🚫 纯内存用例，不起 Quarkus、不碰库。真库端到端见
 * {@code com.cpq.dataset.registry.DatasetFingerprintRoundTripQuarkusTest}。
 */
class RegistryTypeCoherenceAcTest {

    private static final QuoteRegistry QUOTE = new QuoteRegistry();

    private static List<DatasetRegistry> registries() {
        return List.of(QUOTE, new CostBasicRegistry(), new CostDetailRegistry());
    }

    private static SheetDef sheet(DatasetRegistry reg, String sheetKey) {
        return reg.sheets().stream().filter(s -> sheetKey.equals(s.sheetKey)).findFirst().orElseThrow();
    }

    private static ColumnDef column(SheetDef s, String name) {
        return s.persistedColumns().stream().filter(c -> name.equals(c.name)).findFirst().orElseThrow();
    }

    // ── T1：结构性防复发网 —— 三套 Registry 全量列的 type ↔ pgType 自洽 ──────────────
    @Test
    @DisplayName("T1 三套 Registry 的每一列：声明类型(type) 与建表类型(pgType) 必须同族（D-47 防复发）")
    void everyColumnTypeMatchesItsPhysicalType() {
        int scanned = 0;
        List<String> problems = new ArrayList<>();
        for (DatasetRegistry reg : registries()) {
            for (SheetDef s : reg.sheets()) {
                for (ColumnDef c : s.persistedColumns()) {
                    scanned++;
                    String p = DatasetSchemaSelfCheck.typeCoherenceProblem(c);
                    if (p != null) problems.add(s.tableName + "." + c.name + " " + p);
                }
            }
        }
        // 非空守卫：扫到 0 列的话「无差异」恒真。实测 2026-09-08 = 333 列（三套 Registry 的 persistedColumns 之和）
        assertTrue(scanned > 300, "扫描列数应覆盖三套 Registry 全量列，实际=" + scanned);
        assertEquals(List.of(), problems, "存在 type/pgType 错配的列（每一处都是一个静默的虚假升版源）");
    }

    @Test
    @DisplayName("T1b typeCoherenceProblem 本身能报错（证明 T1 的绿不是因为判据恒返 null）")
    void coherenceRuleActuallyDetects() {
        // 正是 D-47 的原形：物理 boolean + 声明 STRING
        assertNotNull(DatasetSchemaSelfCheck.typeCoherenceProblem(
                ColumnDef.col("x", "x", "VALUE", "STRING", "boolean", false, true)));
        // 反向：物理 varchar + 声明 BOOLEAN 同样要报
        assertNotNull(DatasetSchemaSelfCheck.typeCoherenceProblem(
                ColumnDef.col("x", "x", "VALUE", "BOOLEAN", "varchar(128)", false, true)));
        // 物理数值 + 声明 STRING 要报
        assertNotNull(DatasetSchemaSelfCheck.typeCoherenceProblem(
                ColumnDef.col("x", "x", "VALUE", "STRING", "numeric(26,12)", false, true)));
        // 未识别的物理类型要报（新增 date/uuid/jsonb 时必须先想清楚指纹分支）
        assertNotNull(DatasetSchemaSelfCheck.typeCoherenceProblem(
                ColumnDef.col("x", "x", "VALUE", "STRING", "date", false, true)));
        // 合法组合一律放行
        assertNull(DatasetSchemaSelfCheck.typeCoherenceProblem(
                ColumnDef.col("x", "x", "VALUE", "BOOLEAN", "boolean", false, true)));
        assertNull(DatasetSchemaSelfCheck.typeCoherenceProblem(
                ColumnDef.col("x", "x", "VALUE", "ENUM", "varchar(128)", false, true)));
        assertNull(DatasetSchemaSelfCheck.typeCoherenceProblem(
                ColumnDef.col("x", "x", "VALUE", "DECIMAL", "numeric(26,12)", false, true)));
        assertNull(DatasetSchemaSelfCheck.typeCoherenceProblem(
                ColumnDef.col("x", "x", "VALUE", "NUMBER", "integer", false, true)));
    }

    // ── T2：D-47 本体 —— Excel 写法经 DB 往返后指纹必须不变 ────────────────────────
    @Test
    @DisplayName("T2 来料固定加工费：Excel 各种布尔写法 → 落库 → 读回，行指纹必须逐字相同（D-47 靶子）")
    void excelSpellingSurvivesDbRoundTrip() {
        SheetDef s = sheet(QUOTE, "INCOMING_FIXED_FEE");
        ColumnDef flag = column(s, "follow_material_price");

        // 🚨 非空守卫①：这一列必须真的参与指纹，否则「两侧相等」恒真
        assertTrue(flag.compared, "follow_material_price 必须是对比项，否则本用例恒绿");
        assertTrue(s.comparedColumns().stream().anyMatch(c -> "follow_material_price".equals(c.name)),
                "follow_material_price 必须出现在 comparedColumns() 里");
        // 🚨 非空守卫②：物理列确实是 boolean（否则整个 D-47 的前提不成立）
        assertEquals("boolean", flag.pgType);
        assertTrue(DatasetValues.isBoolean(flag), "DatasetValues 必须把它认成布尔列");

        // Excel 里出现过 / 可能出现的全部写法。normalizeBoolean 的「宽进」列表 + 中文否定 + 大小写。
        List<String> spellings = List.of("是", "否", "true", "false", "TRUE", "False",
                                         "1", "0", "Y", "N", "yes", "no", "t");
        List<String> divergent = new ArrayList<>();
        for (String excel : spellings) {
            // 导入侧：ParsedRow.asRowMap() 给的是 Excel 原串
            String fpFromExcel = DatasetFingerprints.compute(s, rowWith(excel));
            // 落库侧：DatasetValues.coerce 把它转成 JDBC 绑定值（Boolean）
            Object dbValue = DatasetValues.coerce(flag, excel);
            assertTrue(dbValue instanceof Boolean, "coerce 后应为 Boolean，实际=" + dbValue);
            // 读回侧：JDBC 把 boolean 列读成 Boolean，回填/预览就是拿它重算指纹
            String fpFromDb = DatasetFingerprints.compute(s, rowWith(dbValue));
            if (!fpFromExcel.equals(fpFromDb)) {
                divergent.add(excel + " → excelFp=" + fpFromExcel.substring(0, 12)
                        + " dbFp=" + fpFromDb.substring(0, 12));
            }
        }
        assertEquals(List.of(), divergent,
                "以下 Excel 写法在「落库 → 读回」后指纹分叉 —— 每一个都会让该组每次核价通过空转升一版");
    }

    @Test
    @DisplayName("T2b 空值三态（null / 空串 / 全角空格）落库读回同样不分叉")
    void blankSpellingsAlsoStable() {
        SheetDef s = sheet(QUOTE, "INCOMING_FIXED_FEE");
        ColumnDef flag = column(s, "follow_material_price");
        String base = DatasetFingerprints.compute(s, rowWith(null));
        for (Object blank : new Object[]{null, "", "   ", "　"}) {
            assertEquals(base, DatasetFingerprints.compute(s, rowWith(blank)), "空值写法=" + blank);
            assertNull(DatasetValues.coerce(flag, blank), "空值必须落 NULL");
        }
        // NULL 读回仍是 NULL ⇒ 与导入侧同指纹
        assertEquals(base, DatasetFingerprints.compute(s, rowWith((Object) null)));
    }

    @Test
    @DisplayName("T2c 改动边界：同 sheet 的非布尔列一列都没受影响（DECIMAL / ENUM / STRING 往返仍相等）")
    void nonBooleanColumnsUnaffected() {
        SheetDef s = sheet(QUOTE, "INCOMING_FIXED_FEE");
        // 该 sheet 的对比列里，除 follow_material_price 外全部保持原类型族
        Map<String, String> expect = new LinkedHashMap<>();
        expect.put("input_material_no", "STRING");
        expect.put("base_value", "DECIMAL");
        expect.put("ratio_pct", "DECIMAL");
        expect.put("currency", "ENUM");
        expect.put("pricing_unit", "ENUM");
        expect.put("material_increase_ratio", "DECIMAL");
        expect.put("material_increase_value", "DECIMAL");
        expect.put("increase_currency", "ENUM");
        expect.put("increase_unit", "ENUM");
        for (Map.Entry<String, String> e : expect.entrySet()) {
            assertEquals(e.getValue(), column(s, e.getKey()).type, "列 " + e.getKey() + " 的类型不应被本次改动碰到");
        }
        assertEquals("BOOLEAN", column(s, "follow_material_price").type);

        // 数值列往返：Excel 串 "1.500" → BigDecimal → 同指纹（原有语义，回归网）
        Map<String, Object> excel = fullRow();
        excel.put("base_value", "1.500");
        Map<String, Object> db = fullRow();
        db.put("base_value", new java.math.BigDecimal("1.500000000000"));
        assertEquals(DatasetFingerprints.compute(s, excel), DatasetFingerprints.compute(s, db));
    }

    @Test
    @DisplayName("T3 全 13 张报价带版本表：布尔物理列有且只有 follow_material_price 一处（改动面确证）")
    void onlyOneBooleanColumnInQuoteRegistry() {
        List<String> bools = new ArrayList<>();
        for (DatasetRegistry reg : registries()) {
            for (SheetDef s : reg.sheets()) {
                for (ColumnDef c : s.persistedColumns()) {
                    if (c.pgType != null && c.pgType.trim().toLowerCase().startsWith("boolean")) {
                        bools.add(s.tableName + "." + c.name + "=" + c.type);
                    }
                }
            }
        }
        assertEquals(List.of("ds_quote_incoming_fixed_fee.follow_material_price=BOOLEAN"), bools,
                "三套 Registry 里的布尔物理列清单 —— 多出一列就说明改动面比登记的大");
    }

    // ── 夹具 ─────────────────────────────────────────────────────────────────
    /** 来料固定加工费的一整行，{@code follow_material_price} 由入参决定。 */
    private static Map<String, Object> rowWith(Object flagValue) {
        Map<String, Object> row = fullRow();
        row.put("follow_material_price", flagValue);
        return row;
    }

    private static Map<String, Object> fullRow() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("material_no", "T260907D47-FF01");
        row.put("item_seq", "1");
        row.put("input_material_no", "T260907D47-RM01");
        row.put("base_value", "12.5");
        row.put("ratio_pct", "3.25");
        row.put("currency", "CNY");
        row.put("pricing_unit", "KG");
        row.put("material_increase_ratio", "1.5");
        row.put("material_increase_value", "0.75");
        row.put("increase_currency", "CNY");
        row.put("increase_unit", "KG");
        return row;
    }

    @Test
    @DisplayName("T4 指纹算法未被本次改动碰到（RowFingerprints 分隔符与 hex 口径不变）")
    void fingerprintAlgorithmUnchanged() {
        assertEquals(0x1F, RowFingerprints.SEPARATOR);
        assertEquals(64, RowFingerprints.sha256Hex("x").length());
    }
}
