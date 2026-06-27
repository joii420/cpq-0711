package com.cpq.basicdata.v6.versioning;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.*;

import java.math.BigDecimal;
import java.util.*;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Task 3（实现计划 2026-06-27-v6import-setbased-writer）：golden 等价测试 —— HARD GATE。
 *
 * <p>同一组输入，分别在两套<strong>完全隔离的数据</strong>上跑「逐组循环」与「批量入口」，
 * 再断言两表的<strong>业务列多集逐位相同</strong>（忽略 {@code id/created_at/updated_at}，BigDecimal 经
 * {@code stripTrailingZeros} 归一）。任一断言失败 = 批量写入器与逐组语义不等价（writer 真 bug），
 * <strong>不得</strong>放松断言来「修绿」。</p>
 *
 * <p>Part A 单表 {@code unit_price}（writeVersionedGroup 循环 vs writeVersionedGroups 批量）；
 * Part B 主从 {@code element_bom}/{@code element_bom_item}
 * （writeVersionedMasterDetail 循环 vs writeVersionedMasterDetails 批量，childVersionColumn != null）。</p>
 *
 * <p>隔离手段：因 {@code unit_price}/{@code element_bom} 的 {@code system_type} 受 CHECK 约束
 * （仅 QUOTE/PRICING），且 {@code price_type} 受 CHECK、{@code pricing_price} NOT NULL，故 loop / batch
 * 两路用<strong>不同 {@code customer_no} 值</strong>（自由文本列）隔离，而非伪造 system_type。</p>
 */
@QuarkusTest
class VersionedBatchEquivTest {

    @Inject VersionedV6Writer writer;
    @Inject EntityManager em;

    // ============================================================
    // Part A —— 单表 unit_price 等价
    // ============================================================

    /** loop / batch 两路隔离用的 customer_no（不能用 system_type：受 CHECK IN ('QUOTE','PRICING')）。 */
    static final String CUST_LOOP = "__EQUIV_LOOP";
    static final String CUST_BATCH = "__EQUIV_BATCH";
    /** 含 pricing_price（NOT NULL）：必须随行写入，否则插入违反约束。 */
    static final List<String> CONTENT = List.of("seq_no", "pricing_price", "base_value", "currency");

    /** 构造 3 个组、每组 1 行的输入；customer_no 充当 loop/batch 隔离维度。 */
    LinkedHashMap<Map<String, Object>, List<Map<String, Object>>> sample(String customerNo) {
        LinkedHashMap<Map<String, Object>, List<Map<String, Object>>> g = new LinkedHashMap<>();
        for (String code : List.of("C1", "C2", "C3")) {
            Map<String, Object> gk = new LinkedHashMap<>();
            gk.put("system_type", "QUOTE");        // CHECK IN ('QUOTE','PRICING')
            gk.put("price_type", "ELEMENT");       // CHECK IN ('ELEMENT','MATERIAL',...)
            gk.put("cost_type", "T");
            gk.put("code", code);
            gk.put("customer_no", customerNo);
            gk.put("finished_material_no", "F1");
            List<Map<String, Object>> rows = new ArrayList<>();
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("seq_no", 1);
            r.put("pricing_price", new BigDecimal("3.00")); // NOT NULL
            r.put("base_value", new BigDecimal("1.50"));
            r.put("currency", "CNY");
            rows.add(r);
            g.put(gk, rows);
        }
        return g;
    }

    /** 读回某 customer_no 下行的业务列多集（忽略 id/时间戳；含 is_current 历史行）。 */
    @SuppressWarnings("unchecked")
    Map<String, Integer> snapshot(String customerNo) {
        List<Object[]> rows = em.createNativeQuery(
                "SELECT code, version_no, is_current, seq_no, pricing_price, base_value, currency "
                + "FROM unit_price WHERE customer_no = ?1 ORDER BY code, version_no")
            .setParameter(1, customerNo).getResultList();
        Map<String, Integer> tally = new HashMap<>();
        for (Object[] r : rows) {
            String key = r[0] + "|" + r[1] + "|" + r[2] + "|" + r[3]
                + "|" + dec(r[4]) + "|" + dec(r[5]) + "|" + r[6];
            tally.merge(key, 1, Integer::sum);
        }
        return tally;
    }

    void runLoop(LinkedHashMap<Map<String, Object>, List<Map<String, Object>>> g) {
        runLoop(g, null);
    }

    /** trigger-aware：trigger==null → 退化为 contentColumns 触发(默认)；非 null → 触发列子集(P08 capacity 路径)。 */
    void runLoop(LinkedHashMap<Map<String, Object>, List<Map<String, Object>>> g, List<String> trigger) {
        for (Map.Entry<Map<String, Object>, List<Map<String, Object>>> e : g.entrySet())
            writer.writeVersionedGroup(new VersionedGroupSpec(
                "unit_price", "version_no", e.getKey(), CONTENT, e.getValue(), trigger));
    }

    void runBatch(LinkedHashMap<Map<String, Object>, List<Map<String, Object>>> g) {
        runBatch(g, null);
    }

    void runBatch(LinkedHashMap<Map<String, Object>, List<Map<String, Object>>> g, List<String> trigger) {
        writer.writeVersionedGroups("unit_price", "version_no", CONTENT, trigger, g);
    }

    int currentRowCount(String customerNo) {
        return ((Number) em.createNativeQuery(
                "SELECT count(*) FROM unit_price WHERE customer_no = ?1 AND is_current = TRUE")
            .setParameter(1, customerNo).getSingleResult()).intValue();
    }

    int totalRowCount(String customerNo) {
        return ((Number) em.createNativeQuery(
                "SELECT count(*) FROM unit_price WHERE customer_no = ?1")
            .setParameter(1, customerNo).getSingleResult()).intValue();
    }

    @Test @Transactional
    void firstWrite_loop_equals_batch() {
        runLoop(sample(CUST_LOOP));
        runBatch(sample(CUST_BATCH));
        assertEquals(snapshot(CUST_LOOP), snapshot(CUST_BATCH), "首写升版后两路表状态应逐位相同");
    }

    @Test @Transactional
    void reimport_identical_isIdempotent_andEqual() {
        runLoop(sample(CUST_LOOP)); runLoop(sample(CUST_LOOP));     // 重复导入(完全相同→复用版本不写)
        runBatch(sample(CUST_BATCH)); runBatch(sample(CUST_BATCH));
        assertEquals(snapshot(CUST_LOOP), snapshot(CUST_BATCH), "重复导入后两路表状态应逐位相同");
        assertEquals(3, currentRowCount(CUST_LOOP), "loop 路应只 3 个当前版本(幂等)");
        assertEquals(3, currentRowCount(CUST_BATCH), "batch 路应只 3 个当前版本(幂等)");
    }

    @Test @Transactional
    void contentChange_bumpsVersion_equal() {
        runLoop(sample(CUST_LOOP)); runBatch(sample(CUST_BATCH));
        // 改 base_value（content 变→升版），含历史 is_current=false 行
        var g2loop = sample(CUST_LOOP);
        g2loop.values().forEach(rs -> rs.get(0).put("base_value", new BigDecimal("9.90")));
        var g2batch = sample(CUST_BATCH);
        g2batch.values().forEach(rs -> rs.get(0).put("base_value", new BigDecimal("9.90")));
        runLoop(g2loop); runBatch(g2batch);
        assertEquals(snapshot(CUST_LOOP), snapshot(CUST_BATCH),
            "升版后(含历史 is_current=false 行)两路应逐位相同");
    }

    /**
     * 分支(b) 覆盖：triggerSame && !contentSame → 原地更新（删当前+同版本重插，<strong>不升版</strong>）。
     * 前 3 个用例 trigger=null → triggerCols==contentCols → 分支(b)不可达；本用例传触发列子集
     * {@code [seq_no]}，只改非触发列 base_value → 触发列相同、内容不同 → 命中分支(b)。
     * 这正是 P08 capacity handler 依赖的路径，必须证明 loop==batch。
     */
    @Test @Transactional
    void inPlaceUpdate_triggerSubset_loop_equals_batch() {
        List<String> trigger = List.of("seq_no");           // contentColumns 的真子集
        runLoop(sample(CUST_LOOP), trigger);                // v1 当前
        runBatch(sample(CUST_BATCH), trigger);
        // 只改非触发列 base_value（seq_no 不变）→ triggerSame=true, contentSame=false → 原地更新
        var g2loop = sample(CUST_LOOP);
        g2loop.values().forEach(rs -> rs.get(0).put("base_value", new BigDecimal("7.77")));
        var g2batch = sample(CUST_BATCH);
        g2batch.values().forEach(rs -> rs.get(0).put("base_value", new BigDecimal("7.77")));
        runLoop(g2loop, trigger); runBatch(g2batch, trigger);

        assertEquals(snapshot(CUST_LOOP), snapshot(CUST_BATCH),
            "原地更新(分支 b)后两路表状态应逐位相同");
        // 原地更新 = 同版本复用、旧当前行删除而非翻转 → 每 code 仅 1 行、无 is_current=false 历史行
        assertEquals(3, currentRowCount(CUST_LOOP), "loop 路应 3 个当前版本");
        assertEquals(3, currentRowCount(CUST_BATCH), "batch 路应 3 个当前版本");
        assertEquals(3, totalRowCount(CUST_LOOP), "loop 路原地更新不应留历史行(总行数=3)");
        assertEquals(3, totalRowCount(CUST_BATCH), "batch 路原地更新不应留历史行(总行数=3)");
    }

    @AfterEach @Transactional
    void cleanupUnitPrice() {
        em.createNativeQuery("DELETE FROM unit_price WHERE customer_no IN (?1, ?2)")
          .setParameter(1, CUST_LOOP).setParameter(2, CUST_BATCH).executeUpdate();
    }

    // ============================================================
    // Part B —— 主从 element_bom / element_bom_item 等价
    //  writeVersionedMasterDetail(loop) vs writeVersionedMasterDetails(batch)
    //  childVersionColumn = "characteristic"（!= null，多版本保留）
    // ============================================================

    static final String BOM_CUST_LOOP = "__EBOM_LOOP";
    static final String BOM_CUST_BATCH = "__EBOM_BATCH";
    static final List<String> CHILD_CONTENT = List.of("seq_no", "component_no", "content");
    /** 多组 → 让批量入口的常量前缀 = {system_type, customer_no}，material_no 充当 varying group 维度。 */
    static final List<String> BOM_MATERIALS = List.of("MB-EQUIV-1", "MB-EQUIV-2");

    private Map<String, Object> bomGk(String customerNo, String materialNo) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("system_type", "QUOTE");        // CHECK IN ('QUOTE','PRICING','BOTH')
        m.put("customer_no", customerNo);
        m.put("material_no", materialNo);
        return m;
    }

    private Map<String, Object> bomItem(int seq, String comp, String content) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("seq_no", seq);
        m.put("component_no", comp);
        m.put("content", new BigDecimal(content));
        return m;
    }

    /** 每个 material 两行子件；content 由参数注入以便制造「子行变更升版」场景。 */
    private List<Map<String, Object>> bomChildRows(String contentA, String contentB) {
        return List.of(bomItem(1, "Ag", contentA), bomItem(2, "Ni", contentB));
    }

    void runBomLoop(String customerNo, String contentA, String contentB) {
        for (String mat : BOM_MATERIALS) {
            Map<String, Object> gk = bomGk(customerNo, mat);
            writer.writeVersionedMasterDetail(
                "element_bom", "characteristic", gk, Map.of("bom_type", "MATERIAL"),
                "element_bom_item", "characteristic", gk,
                CHILD_CONTENT, bomChildRows(contentA, contentB));
        }
    }

    void runBomBatch(String customerNo, String contentA, String contentB) {
        List<VersionedV6Writer.MasterDetailItem> items = new ArrayList<>();
        for (String mat : BOM_MATERIALS) {
            Map<String, Object> gk = bomGk(customerNo, mat);
            items.add(new VersionedV6Writer.MasterDetailItem(
                gk, new LinkedHashMap<>(gk), bomChildRows(contentA, contentB)));
        }
        writer.writeVersionedMasterDetails(
            "element_bom", "characteristic", Map.of("bom_type", "MATERIAL"),
            "element_bom_item", "characteristic", CHILD_CONTENT, items);
    }

    /** 主表 + 子表业务列多集合并快照（忽略 id/时间戳；含 is_current 历史行）。 */
    @SuppressWarnings("unchecked")
    Map<String, Integer> bomSnapshot(String customerNo) {
        Map<String, Integer> tally = new HashMap<>();
        List<Object[]> masters = em.createNativeQuery(
                "SELECT material_no, characteristic, is_current, bom_type FROM element_bom "
                + "WHERE customer_no = ?1 ORDER BY material_no, characteristic")
            .setParameter(1, customerNo).getResultList();
        for (Object[] r : masters)
            tally.merge("M|" + r[0] + "|" + r[1] + "|" + r[2] + "|" + r[3], 1, Integer::sum);
        List<Object[]> children = em.createNativeQuery(
                "SELECT material_no, characteristic, is_current, seq_no, component_no, content "
                + "FROM element_bom_item WHERE customer_no = ?1 ORDER BY material_no, characteristic, seq_no")
            .setParameter(1, customerNo).getResultList();
        for (Object[] r : children)
            tally.merge("I|" + r[0] + "|" + r[1] + "|" + r[2] + "|" + r[3]
                + "|" + r[4] + "|" + dec(r[5]), 1, Integer::sum);
        return tally;
    }

    @Test @Transactional
    void masterDetail_firstWrite_loop_equals_batch() {
        runBomLoop(BOM_CUST_LOOP, "75", "25");
        runBomBatch(BOM_CUST_BATCH, "75", "25");
        assertEquals(bomSnapshot(BOM_CUST_LOOP), bomSnapshot(BOM_CUST_BATCH),
            "主从首写升版后两路(主表+子表)应逐位相同");
    }

    @Test @Transactional
    void masterDetail_childChange_bumpsVersion_equal() {
        runBomLoop(BOM_CUST_LOOP, "75", "25");
        runBomBatch(BOM_CUST_BATCH, "75", "25");
        // 改子件 content → 升版；旧版本子行 is_current=false 多版本保留
        runBomLoop(BOM_CUST_LOOP, "70", "30");
        runBomBatch(BOM_CUST_BATCH, "70", "30");
        assertEquals(bomSnapshot(BOM_CUST_LOOP), bomSnapshot(BOM_CUST_BATCH),
            "子行变更升版后(含历史 is_current=false 行)两路(主表+子表)应逐位相同");
    }

    @AfterEach @Transactional
    void cleanupBom() {
        for (String t : List.of("element_bom_item", "element_bom")) {
            em.createNativeQuery("DELETE FROM " + t + " WHERE customer_no IN (?1, ?2)")
              .setParameter(1, BOM_CUST_LOOP).setParameter(2, BOM_CUST_BATCH).executeUpdate();
        }
    }

    // ============================================================
    // helpers
    // ============================================================

    /** BigDecimal 归一：stripTrailingZeros，使 1.50 == 1.5 == 1.500000；null → "". */
    private static String dec(Object v) {
        if (v == null) return "";
        return new BigDecimal(v.toString()).stripTrailingZeros().toPlainString();
    }
}
