package com.cpq.task260902;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>L2 集成 · D 组 免版本表</b> —— 覆盖 AC-21 ~ AC-23（R-2 按主键 UPSERT）。
 *
 * <p>三条 AC 的共同判据形态是「<b>count 恒定 + 值被覆盖</b>」。
 * 只断言 count 会漏掉「没覆盖成功」，只断言值会漏掉「其实是新增了一行」——两条都要。
 */
@QuarkusTest
@DisplayName("task-260902 · D 组 免版本表（AC-21~AC-23）")
class DatasetUnversionedAcTest extends DatasetAcTestBase {

    private static final String T_BASIC_MATERIAL = "ds_cost_basic_material";
    private static final String T_PLATING = "ds_quote_plating_scheme";
    /** AC-23 的方案编号，原文字面值 {@code A0001}；共享库上必须加前缀。 */
    private static final String SCHEME_NO = P + "A0001";

    // ═══════════════ AC-21 覆盖更新 ═══════════════

    @Test
    @DisplayName("TU-01 / AC-21：免版本表按主键覆盖 —— 品名改掉后重导，值变了但 count 仍为 1")
    void tu01_upsertOverwrites() {
        assertStatus(importCostBasic(null, "tu01-1.xlsx"), 200, "AC-21 前置");

        String pk = "production_no = '" + AXIS_BASIC_MATERIAL + "'";
        long n1 = countRows(T_BASIC_MATERIAL, pk);
        assertEquals(1L, n1,
                "AC-21 前置：" + T_BASIC_MATERIAL + " 里 " + AXIS_BASIC_MATERIAL + " 应有 1 行，实际 " + n1
                        + " ⇒ 后续覆盖断言会空跑");
        assertEquals("主料1", String.valueOf(scalar(
                        "SELECT material_name FROM " + T_BASIC_MATERIAL + " WHERE " + pk)),
                "AC-21 前置：品名应为「主料1」（模板原值）");

        // 改品名后重导
        Consumer<DatasetFixtureBuilder> renamed = b -> b.setText("物料", 2, "品名", "主料1-改");
        assertStatus(importCostBasic(renamed, "tu01-2.xlsx"), 200, "AC-21");

        assertEquals("主料1-改", String.valueOf(scalar(
                        "SELECT material_name FROM " + T_BASIC_MATERIAL + " WHERE " + pk)),
                "AC-21：重导后品名应被覆盖为「主料1-改」");
        assertEquals(1L, countRows(T_BASIC_MATERIAL, pk),
                "AC-21：应为覆盖而非新增，count 仍应为 1");
    }

    // ═══════════════ AC-22 新增 ═══════════════

    @Test
    @DisplayName("TU-02 / AC-22：物料 sheet 追加一行全新生产料号 → 该行存在，count +1")
    void tu02_upsertInserts() {
        assertStatus(importCostBasic(null, "tu02-1.xlsx"), 200, "AC-22 前置");
        long before = countRows(T_BASIC_MATERIAL, "production_no LIKE '" + P + "%'");
        assertTrue(before > 0, "AC-22 前置：前缀行数为 0 ⇒ 「+1」断言会空跑");

        String newNo = P + "001";
        Consumer<DatasetFixtureBuilder> added = b -> b.appendCopyOf("物料", 2, row -> {
            row.getCell(0).setCellValue(newNo);       // 生产料号
            row.getCell(1).setCellValue("新增测试料号"); // 品名（红底必填）
        });
        assertStatus(importCostBasic(added, "tu02-2.xlsx"), 200, "AC-22");

        assertEquals(1L, countRows(T_BASIC_MATERIAL, "production_no = '" + newNo + "'"),
                "AC-22：新料号 " + newNo + " 应存在且只有 1 行");
        assertEquals(before + 1, countRows(T_BASIC_MATERIAL, "production_no LIKE '" + P + "%'"),
                "AC-22：count 应比导入前多 1");
    }

    // ═══════════════ AC-23 复合主键 ═══════════════

    @Test
    @DisplayName("TU-03 / AC-23：电镀方案复合主键（方案编号+版本+项次）重复导入 → count 恒 1，镀层厚度被覆盖")
    void tu03_compositeKeyUpsert() {
        String thicknessColumn = "镀层厚度（μm）";
        String pk = "scheme_no = '" + SCHEME_NO + "' AND scheme_version = '2000' AND item_seq = 1";

        Consumer<DatasetFixtureBuilder> first = b -> {
            prefixScheme(b);
            b.setNumber("电镀方案", 2, thicknessColumn, 0.4d);
        };
        assertStatus(importQuote(first, "tu03-1.xlsx"), 200, "AC-23 第一次");

        long n1 = countRows(T_PLATING, pk);
        assertEquals(1L, n1, "AC-23：第一次导入后该复合主键应有 1 行，实际 " + n1);
        assertNumericEquals("0.4", scalar("SELECT coating_thickness FROM " + T_PLATING + " WHERE " + pk),
                "AC-23：第一次的镀层厚度应为 0.4");

        // 第二次：同一复合主键，换厚度
        Consumer<DatasetFixtureBuilder> second = b -> {
            prefixScheme(b);
            b.setNumber("电镀方案", 2, thicknessColumn, 0.9d);
        };
        assertStatus(importQuote(second, "tu03-2.xlsx"), 200, "AC-23 第二次");

        assertEquals(1L, countRows(T_PLATING, pk),
                "AC-23：同一 (方案编号, 版本, 项次) 重复导入，count 必须恒为 1（覆盖而非新增）");
        assertNumericEquals("0.9", scalar("SELECT coating_thickness FROM " + T_PLATING + " WHERE " + pk),
                "AC-23：第二次的镀层厚度应覆盖第一次");
    }

    /** 电镀方案的方案编号加前缀（它是免版本表的主键首列，清理面靠它）。 */
    private void prefixScheme(DatasetFixtureBuilder b) {
        for (int rowNo : b.dataRowNumbers("电镀方案", false)) {
            String v = b.readAsString("电镀方案", rowNo, "方案编号");
            if (v != null && !v.isBlank() && !v.startsWith(P)) {
                b.setText("电镀方案", rowNo, "方案编号", P + v);
            }
        }
    }

    // ═══════════════ 助手 ═══════════════

    private Response importCostBasic(Consumer<DatasetFixtureBuilder> mutate, String name) {
        DatasetFixtureBuilder b = Fixtures.costBasic(probe());
        try {
            if (mutate != null) {
                mutate.accept(b);
            }
            return DatasetApi.importFile(adminSession(), DatasetApi.COST_BASIC, b.toBytes(), name);
        } finally {
            b.close();
        }
    }

    private Response importQuote(Consumer<DatasetFixtureBuilder> mutate, String name) {
        DatasetFixtureBuilder b = Fixtures.quote(probe());
        try {
            if (mutate != null) {
                mutate.accept(b);
            }
            return DatasetApi.importFile(adminSession(), DatasetApi.QUOTE, b.toBytes(), name);
        } finally {
            b.close();
        }
    }

    private Object scalar(String sql) {
        List<Object> l = col(sql);
        assertTrue(!l.isEmpty(), "查询无结果 ⇒ 断言空跑：" + sql);
        return l.get(0);
    }

    private void assertStatus(Response r, int expected, String ac) {
        if (r.statusCode() == 401 && expected != 401) {
            throw new AssertionError(ac + "：收到 401。⚠️ 先怀疑 test.md §0.2 的既有环境缺陷。响应：" + r.asString());
        }
        assertEquals(expected, r.statusCode(), ac + "：HTTP 状态码不符。响应体：" + r.asString());
        assertNotNull(r.asString(), ac + "：空响应体");
    }
}
