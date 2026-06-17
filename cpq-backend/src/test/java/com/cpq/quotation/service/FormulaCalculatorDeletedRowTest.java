package com.cpq.quotation.service;

import com.cpq.quotation.rowkey.DeletedRowKeys;
import com.cpq.quotation.rowkey.DeletedRowKeys.Tombstone;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Task4 — driver 默认行永久删除：后端过滤落点单测。
 *
 * <p>纯 JUnit（new FormulaCalculator()，无 Quarkus），与 FormulaCalculatorMultiSubtotalTest 同构造方式。
 *
 * <p>测试约定：
 * <ul>
 *   <li>fixture 含 ≥3 行 driver，墓碑命中其中一行（第 2 行，effKey+fp 精确计算）。</li>
 *   <li>断言：① 输出行数 = 原行数−1；② 被删行的 rowKey 不在输出里；③ 剩余行 rowKey/值不变不错位。</li>
 *   <li>额外场景：撞键（#序号消歧）+ 删除其中一个撞键行；行数−1 且未命中行保留。</li>
 * </ul>
 */
class FormulaCalculatorDeletedRowTest {

    private static final ObjectMapper M = new ObjectMapper();
    private final FormulaCalculator calc = new FormulaCalculator();

    private JsonNode j(String s) {
        try { return M.readTree(s); } catch (Exception e) { throw new RuntimeException(e); }
    }

    // -----------------------------------------------------------------------
    // 公共 fixture：3 行 driver，rowKeyFields=["material_no"]，公式 金额=单价×数量
    // -----------------------------------------------------------------------

    /** fields 定义 */
    private static final String FIELDS = "["
        + "{\"name\":\"单价\",\"fieldType\":\"BASIC_DATA\",\"basicDataPath\":\"v.up\"},"
        + "{\"name\":\"数量\",\"fieldType\":\"BASIC_DATA\",\"basicDataPath\":\"v.qty\"},"
        + "{\"name\":\"金额\",\"fieldType\":\"FORMULA\",\"isSubtotal\":true}"
        + "]";

    private static final String FORMULAS = "["
        + "{\"name\":\"金额\",\"expression\":["
        + "{\"type\":\"field\",\"value\":\"单价\"},{\"type\":\"operator\",\"value\":\"*\"},{\"type\":\"field\",\"value\":\"数量\"}"
        + "]}"
        + "]";

    private static final String RKF = "[\"material_no\"]";

    /** 3 行，material_no 各不相同（无撞键） */
    private static final String BASEROWS_3 = "["
        + "{\"driverRow\":{\"material_no\":\"M1\"},\"basicDataValues\":{\"{v.up}\":10,\"{v.qty}\":2}},"
        + "{\"driverRow\":{\"material_no\":\"M2\"},\"basicDataValues\":{\"{v.up}\":4,\"{v.qty}\":5}},"
        + "{\"driverRow\":{\"material_no\":\"M3\"},\"basicDataValues\":{\"{v.up}\":3,\"{v.qty}\":7}}"
        + "]";

    // -----------------------------------------------------------------------
    // 场景 A：正常无撞键，删除中间行（M2）
    // -----------------------------------------------------------------------

    /**
     * 构造 M2 行的 effKey + fp。
     *
     * <p>effKey：uniquifyRowKeys(["M1","M2","M3"])[1] = "M2"（无撞键，不加#序号）。
     * <p>fp：rowFingerprint(["material_no"], driverRow={material_no:"M2"})
     *       = canon("M2") + canon("M2")（rowKeyFieldNames 按序 + 全键升序）
     *       = "M2" + "M2" = "M2M2"。
     */
    private Tombstone buildM2Tombstone() {
        JsonNode m2Driver = j("{\"material_no\":\"M2\"}");
        List<String> rkfNames = List.of("material_no");
        String fp = DeletedRowKeys.rowFingerprint(rkfNames, m2Driver);
        return new Tombstone("M2", fp);
    }

    @Test
    void deleteMidRow_outputCountMinusOne() {
        Tombstone t = buildM2Tombstone();
        ArrayNode result = calc.calculate(
            j(FIELDS), j(FORMULAS), null, j(RKF),
            j(BASEROWS_3), j("[]"), Map.of(), Map.of(), Map.of(), Map.of(),
            List.of(t), List.of("material_no"));

        // ① 输出行数 = 3 − 1 = 2
        assertEquals(2, result.size(), "删除 M2 后应剩 2 行，实际=" + result.size());
    }

    @Test
    void deleteMidRow_deletedRowKeyAbsent() {
        Tombstone t = buildM2Tombstone();
        ArrayNode result = calc.calculate(
            j(FIELDS), j(FORMULAS), null, j(RKF),
            j(BASEROWS_3), j("[]"), Map.of(), Map.of(), Map.of(), Map.of(),
            List.of(t), List.of("material_no"));

        // ② 被删行 M2 的 rowKey 不在输出中
        boolean m2Present = false;
        for (JsonNode r : result) {
            if ("M2".equals(r.path("rowKey").asText(""))) { m2Present = true; break; }
        }
        assertFalse(m2Present, "M2 行应被删除，但仍在输出中");
    }

    @Test
    void deleteMidRow_remainingRowsCorrect() {
        Tombstone t = buildM2Tombstone();
        ArrayNode result = calc.calculate(
            j(FIELDS), j(FORMULAS), null, j(RKF),
            j(BASEROWS_3), j("[]"), Map.of(), Map.of(), Map.of(), Map.of(),
            List.of(t), List.of("material_no"));

        // ③ 剩余行 rowKey/值不变不错位
        // M1: 金额 = 10*2 = 20；M3: 金额 = 3*7 = 21
        JsonNode m1 = null, m3 = null;
        for (JsonNode r : result) {
            String rk = r.path("rowKey").asText("");
            if ("M1".equals(rk)) m1 = r;
            if ("M3".equals(rk)) m3 = r;
        }
        assertNotNull(m1, "M1 行应保留");
        assertNotNull(m3, "M3 行应保留");
        assertEquals(20.0, m1.path("values").path("金额").asDouble(), 0.001, "M1 金额=10*2=20");
        assertEquals(21.0, m3.path("values").path("金额").asDouble(), 0.001, "M3 金额=3*7=21");
    }

    // -----------------------------------------------------------------------
    // 场景 B：无墓碑（deleted=null）→ 全行保留（核价侧零破坏验证）
    // -----------------------------------------------------------------------

    @Test
    void noTombstone_allRowsKept() {
        ArrayNode result = calc.calculate(
            j(FIELDS), j(FORMULAS), null, j(RKF),
            j(BASEROWS_3), j("[]"), Map.of(), Map.of(), Map.of(), Map.of(),
            null, null);

        assertEquals(3, result.size(), "无墓碑时应返回全部 3 行");
    }

    @Test
    void emptyTombstoneList_allRowsKept() {
        ArrayNode result = calc.calculate(
            j(FIELDS), j(FORMULAS), null, j(RKF),
            j(BASEROWS_3), j("[]"), Map.of(), Map.of(), Map.of(), Map.of(),
            List.of(), List.of("material_no"));

        assertEquals(3, result.size(), "空墓碑列表时应返回全部 3 行");
    }

    // -----------------------------------------------------------------------
    // 场景 C：撞键（#序号消歧），删除 M2#0（第一个 M2），另一个 M2#1 保留
    // -----------------------------------------------------------------------

    /** 2 行 M2（撞键）+ 1 行 M3 */
    private static final String BASEROWS_COLLIDE = "["
        + "{\"driverRow\":{\"material_no\":\"M2\",\"spec\":\"A\"},\"basicDataValues\":{\"{v.up}\":4,\"{v.qty}\":5}},"
        + "{\"driverRow\":{\"material_no\":\"M2\",\"spec\":\"B\"},\"basicDataValues\":{\"{v.up}\":6,\"{v.qty}\":3}},"
        + "{\"driverRow\":{\"material_no\":\"M3\"},\"basicDataValues\":{\"{v.up}\":3,\"{v.qty}\":7}}"
        + "]";

    /**
     * M2#0：effKey="M2#0"，fp 取 driverRow={material_no:"M2",spec:"A"}。
     * rowKeyFieldNames=["material_no"]，rowFingerprint = canon("M2") + 全键升序(material_no,spec)的值
     * = "M2" + "M2" + "A" = "M2M2A"。
     */
    private Tombstone buildM2_0Tombstone() {
        JsonNode driver0 = j("{\"material_no\":\"M2\",\"spec\":\"A\"}");
        String fp = DeletedRowKeys.rowFingerprint(List.of("material_no"), driver0);
        return new Tombstone("M2#0", fp);
    }

    @Test
    void collidedKeys_deleteFirst_secondKept() {
        Tombstone t = buildM2_0Tombstone();
        ArrayNode result = calc.calculate(
            j(FIELDS), j(FORMULAS), null, j(RKF),
            j(BASEROWS_COLLIDE), j("[]"), Map.of(), Map.of(), Map.of(), Map.of(),
            List.of(t), List.of("material_no"));

        // 行数 = 3 − 1 = 2
        assertEquals(2, result.size(), "删除撞键第一行后应剩 2 行，实际=" + result.size());

        // M2#0 不在输出，M2#1 保留
        boolean m2_0Present = false, m2_1Present = false;
        for (JsonNode r : result) {
            String rk = r.path("rowKey").asText("");
            if ("M2#0".equals(rk)) m2_0Present = true;
            if ("M2#1".equals(rk)) m2_1Present = true;
        }
        assertFalse(m2_0Present, "M2#0 行应被删除");
        assertTrue(m2_1Present, "M2#1 行应保留");
    }

    @Test
    void collidedKeys_deleteFirst_valuesCorrect() {
        Tombstone t = buildM2_0Tombstone();
        ArrayNode result = calc.calculate(
            j(FIELDS), j(FORMULAS), null, j(RKF),
            j(BASEROWS_COLLIDE), j("[]"), Map.of(), Map.of(), Map.of(), Map.of(),
            List.of(t), List.of("material_no"));

        // M2#1: 金额 = 6*3 = 18；M3: 金额 = 3*7 = 21
        JsonNode m2_1 = null, m3 = null;
        for (JsonNode r : result) {
            String rk = r.path("rowKey").asText("");
            if ("M2#1".equals(rk)) m2_1 = r;
            if ("M3".equals(rk)) m3 = r;
        }
        assertNotNull(m2_1, "M2#1 行应保留");
        assertNotNull(m3, "M3 行应保留");
        assertEquals(18.0, m2_1.path("values").path("金额").asDouble(), 0.001, "M2#1 金额=6*3=18");
        assertEquals(21.0, m3.path("values").path("金额").asDouble(), 0.001,  "M3 金额=3*7=21");
    }
}
