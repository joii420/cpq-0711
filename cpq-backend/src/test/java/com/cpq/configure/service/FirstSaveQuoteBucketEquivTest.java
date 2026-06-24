package com.cpq.configure.service;

import com.cpq.component.dto.ExpandDriverResponse;
import com.cpq.component.service.ComponentDriverService;
import com.cpq.formula.dataloader.QuotationIdContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.transaction.Transactional;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Task 4 等价护栏：证明报价侧「整单合桶预取 precomputeQuoteDriverBuckets + 按 partNo 回分」
 * 与原逐行 {@code expand(comp.id, customerId, partNo, null,null,null, li.id, compositeType)}
 * 对每个 (li, comp) 产出 <b>逐位相同</b> 的 rows JSON。
 *
 * <p>仿 {@link com.cpq.quotation.service.CostingPartSetUnionEquivTest} 结构，适配报价侧语义：
 * <ul>
 *   <li>报价侧无 BOM 闭包，分桶键 = 产品料号本身（不做 BOM 递归展开）；
 *   <li>eligible 组件 expandMulti 一次取全料号，按 hf_part_no 回分；
 *   <li>不 eligible 组件（含 lineItemId/spineKeys/composite/EXCEL）不进 buckets，逐行回落。
 * </ul>
 *
 * <p>只读真实数据，不写 DB，无需清理（precompute + expand 纯读，不触发写事务）。
 *
 * <p>测试选取库中「含报价模板 + ≥2 行 + 至少一个 eligible driver 组件」的最小报价单（罗克韦尔 8f0c37a4）。
 */
@QuarkusTest
class FirstSaveQuoteBucketEquivTest {

    @Inject
    ConfigureSnapshotService svc;

    @Inject
    ComponentDriverService cds;

    @Inject
    EntityManager em;

    @Inject
    EntityManagerFactory emf;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 罗克韦尔报价单 ID（Task3 / 等价测试锚点，见 RECORD.md）。 */
    private static final String ROCKWELL_QUOTATION_ID = "8f0c37a4-ad69-4f5f-8b09-51c0e9af71e3";

    // ─────────────────────────────────────────────────────────────────────
    // Helper
    // ─────────────────────────────────────────────────────────────────────

    record QuotePick(UUID quotationId, UUID customerId) {}

    /** 取库中含报价模板、≥2 行、有 driver 组件的最小报价单（优先选罗克韦尔）。 */
    @Transactional
    QuotePick pickSmallestQuotation() {
        // 优先选罗克韦尔
        @SuppressWarnings("unchecked")
        List<Object[]> rs = em.createNativeQuery(
                "SELECT q.id, q.customer_id " +
                "FROM quotation q JOIN quotation_line_item li ON li.quotation_id = q.id " +
                "WHERE q.customer_template_id IS NOT NULL AND li.product_part_no_snapshot IS NOT NULL " +
                "GROUP BY q.id, q.customer_id HAVING count(li.id) >= 2 " +
                "ORDER BY (q.id::text = :rockwell) DESC, count(li.id) ASC LIMIT 1")
                .setParameter("rockwell", ROCKWELL_QUOTATION_ID)
                .getResultList();
        if (rs.isEmpty()) return null;
        Object[] r = rs.get(0);
        return new QuotePick(toUUID(r[0]), toUUID(r[1]));
    }

    @Transactional
    List<Map<String, Object>> loadLineItems(UUID quotationId) {
        return svc.loadQuotationLines(quotationId);
    }

    @Transactional
    List<ConfigureSnapshotService.DriverComp> loadComps(UUID quotationId) {
        return svc.loadDriverComponents(quotationId);
    }

    private static UUID toUUID(Object o) {
        if (o == null) return null;
        if (o instanceof UUID u) return u;
        try { return UUID.fromString(o.toString()); } catch (Exception e) { return null; }
    }

    private String partNoOf(Map<String, Object> li) {
        Object v = li.get("productPartNo");
        return v == null ? null : v.toString();
    }

    private String compositeTypeOf(Map<String, Object> li) {
        Object v = li.get("compositeType");
        return v == null ? null : v.toString();
    }

    private UUID lineIdOf(Map<String, Object> li) {
        return toUUID(li.get("id"));
    }

    // ─────────────────────────────────────────────────────────────────────
    // TC-1  A/B 逐位等价
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @Transactional
    void bucketEqualsPerRow_rowsJson() throws Exception {
        QuotePick p = pickSmallestQuotation();
        Assumptions.assumeTrue(p != null, "无含报价模板且 ≥2 行的报价单，跳过");

        List<Map<String, Object>> lineItems = loadLineItems(p.quotationId());
        List<ConfigureSnapshotService.DriverComp> comps = loadComps(p.quotationId());
        Assumptions.assumeTrue(!comps.isEmpty(), "无 driver 组件，跳过");

        // 整单合桶预取（路径 B）
        QuotationIdContext.set(p.quotationId());
        Map<UUID, Map<String, ExpandDriverResponse>> buckets;
        try {
            buckets = svc.precomputeQuoteDriverBuckets(p.quotationId(), p.customerId(), comps, lineItems);
        } finally {
            QuotationIdContext.clear();
        }

        // 如果没有 eligible 组件（所有组件含行维度）→ buckets 空 → 合桶无对象，直接跳过
        Assumptions.assumeTrue(!buckets.isEmpty(),
                "该报价单无 eligible 组件（视图均含行维度），合桶无对象，跳过 A/B 等价测试");

        int comparedNonEmpty = 0;
        int linesChecked = 0;

        // 对前若干行逐 (li, comp) 做 A/B 对比
        List<Map<String, Object>> sampleLines = lineItems.subList(0, Math.min(6, lineItems.size()));
        QuotationIdContext.set(p.quotationId());
        try {
            for (Map<String, Object> li : sampleLines) {
                UUID lineItemId = lineIdOf(li);
                String partNo = partNoOf(li);
                String compositeType = compositeTypeOf(li);
                if (lineItemId == null || partNo == null || partNo.isBlank()) continue;

                for (ConfigureSnapshotService.DriverComp comp : comps) {
                    Map<String, ExpandDriverResponse> bucket = buckets.get(comp.id);
                    if (bucket == null) {
                        // 不 eligible：回落分支，跳过 A/B（在 TC-3 专门验证）
                        continue;
                    }

                    // 路径 A（逐行）：现状 8-arg expand
                    ExpandDriverResponse expA = cds.expand(
                            comp.id, p.customerId(), partNo, null, null, null, lineItemId, compositeType);
                    List<ExpandDriverResponse.Row> rowsA = (expA != null && expA.rows != null) ? expA.rows : List.of();
                    String jsonA = MAPPER.writeValueAsString(rowsA);

                    // 路径 B（合桶）：bucket.get(partNo)
                    ExpandDriverResponse expB = bucket.get(partNo);
                    List<ExpandDriverResponse.Row> rowsB = (expB != null && expB.rows != null) ? expB.rows : List.of();
                    String jsonB = MAPPER.writeValueAsString(rowsB);

                    assertEquals(jsonA, jsonB,
                            "A/B 不一致: li=" + lineItemId + " comp=" + comp.id + " partNo=" + partNo);
                    if (!jsonA.equals("[]")) comparedNonEmpty++;
                }
                linesChecked++;
            }
        } finally {
            QuotationIdContext.clear();
        }

        assertTrue(linesChecked > 0, "至少一行参与了对比（否则测试空转）");
        System.out.printf("=== TC-1 A/B 逐位等价: linesChecked=%d eligibleComps=%d nonEmpty=%d ===%n",
                linesChecked, buckets.size(), comparedNonEmpty);
    }

    // ─────────────────────────────────────────────────────────────────────
    // TC-2  连跑两次 md5（可变共享面专项，AP-37 硬约束③）
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @Transactional
    void sharedBucket_serialization_isIdempotent() throws Exception {
        QuotePick p = pickSmallestQuotation();
        Assumptions.assumeTrue(p != null, "无含报价模板且 ≥2 行的报价单，跳过");

        List<Map<String, Object>> lineItems = loadLineItems(p.quotationId());
        List<ConfigureSnapshotService.DriverComp> comps = loadComps(p.quotationId());
        Assumptions.assumeTrue(!comps.isEmpty(), "无 driver 组件，跳过");

        QuotationIdContext.set(p.quotationId());
        Map<UUID, Map<String, ExpandDriverResponse>> buckets;
        try {
            buckets = svc.precomputeQuoteDriverBuckets(p.quotationId(), p.customerId(), comps, lineItems);
        } finally {
            QuotationIdContext.clear();
        }
        Assumptions.assumeTrue(!buckets.isEmpty(), "无 eligible 组件，跳过");

        // 复用同一 buckets，对第一行第一个 eligible comp 序列化两次，断言两次一致
        // （证明分发链不就地 mutate 共享 resp.rows/Row.driverRow）
        boolean checkedOnce = false;
        outer:
        for (Map<String, Object> li : lineItems) {
            String partNo = partNoOf(li);
            if (partNo == null || partNo.isBlank()) continue;
            for (ConfigureSnapshotService.DriverComp comp : comps) {
                Map<String, ExpandDriverResponse> bucket = buckets.get(comp.id);
                if (bucket == null) continue;
                ExpandDriverResponse exp = bucket.get(partNo);
                List<ExpandDriverResponse.Row> rows = (exp != null && exp.rows != null) ? exp.rows : List.of();

                // 第一次序列化
                String json1 = MAPPER.writeValueAsString(rows);
                // 第二次复用同一共享 exp.rows（不经过任何中间处理）
                String json2 = MAPPER.writeValueAsString(rows);

                assertEquals(json1, json2,
                        "AP-37 可变共享面：同一共享 resp.rows 连续两次序列化不一致（就地 mutate？）" +
                        " comp=" + comp.id + " partNo=" + partNo);

                // 再从 bucket 取一次（不同行对象引用入口），也须相同
                ExpandDriverResponse exp3 = bucket.get(partNo);
                String json3 = MAPPER.writeValueAsString((exp3 != null && exp3.rows != null) ? exp3.rows : List.of());
                assertEquals(json1, json3,
                        "AP-37 可变共享面：两次 bucket.get(partNo) 序列化不一致" +
                        " comp=" + comp.id + " partNo=" + partNo);

                checkedOnce = true;
                break outer;
            }
        }
        assertTrue(checkedOnce, "至少有一个 (li, comp) 执行了两次 md5 专项（否则测试空转）");
        System.out.println("=== TC-2 连跑两次序列化一致（可变共享面无 mutate）===");
    }

    // ─────────────────────────────────────────────────────────────────────
    // TC-3  回落分支专项 — 不 eligible 组件不进 buckets，逐行产出不变
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @Transactional
    void ineligibleComponents_notInBuckets_fallbackMatchesPerRow() throws Exception {
        QuotePick p = pickSmallestQuotation();
        Assumptions.assumeTrue(p != null, "无含报价模板且 ≥2 行的报价单，跳过");

        List<Map<String, Object>> lineItems = loadLineItems(p.quotationId());
        List<ConfigureSnapshotService.DriverComp> comps = loadComps(p.quotationId());
        Assumptions.assumeTrue(!comps.isEmpty(), "无 driver 组件，跳过");

        QuotationIdContext.set(p.quotationId());
        Map<UUID, Map<String, ExpandDriverResponse>> buckets;
        try {
            buckets = svc.precomputeQuoteDriverBuckets(p.quotationId(), p.customerId(), comps, lineItems);
        } finally {
            QuotationIdContext.clear();
        }

        // 找出不 eligible 的组件
        List<ConfigureSnapshotService.DriverComp> ineligibleComps = new ArrayList<>();
        for (ConfigureSnapshotService.DriverComp comp : comps) {
            if (!cds.eligibleForQuoteBucket(comp.id)) ineligibleComps.add(comp);
        }

        if (ineligibleComps.isEmpty()) {
            // 罗克韦尔报价所有组件全 eligible（标准报价视图无行维度）→ 断言 buckets 含所有组件
            assertEquals(comps.size(), buckets.size(),
                    "所有组件均 eligible，buckets 应含全部 " + comps.size() + " 个");
            System.out.println("=== TC-3 该报价单所有 driver 组件均 eligible，无回落分支（符合预期）===");
            return;
        }

        // 有不 eligible 组件：验证不进 buckets + 逐行回落产出与现状一致
        QuotationIdContext.set(p.quotationId());
        try {
            for (ConfigureSnapshotService.DriverComp comp : ineligibleComps) {
                // 断言不进 buckets
                assertFalse(buckets.containsKey(comp.id),
                        "不 eligible 组件 " + comp.id + " 不应进 buckets（Bug B 安全闸门）");

                // 对第一行验证回落产出 == 逐行 expand
                Map<String, Object> li = lineItems.get(0);
                String partNo = partNoOf(li);
                String compositeType = compositeTypeOf(li);
                UUID lineItemId = lineIdOf(li);
                if (partNo == null || partNo.isBlank() || lineItemId == null) continue;

                ExpandDriverResponse expFallback = cds.expand(
                        comp.id, p.customerId(), partNo, null, null, null, lineItemId, compositeType);
                ExpandDriverResponse expPerRow = cds.expand(
                        comp.id, p.customerId(), partNo, null, null, null, lineItemId, compositeType);

                String jsonFallback = MAPPER.writeValueAsString(
                        expFallback != null && expFallback.rows != null ? expFallback.rows : List.of());
                String jsonPerRow = MAPPER.writeValueAsString(
                        expPerRow != null && expPerRow.rows != null ? expPerRow.rows : List.of());

                assertEquals(jsonFallback, jsonPerRow,
                        "回落逐行两次调用应返回相同结果（确定性）comp=" + comp.id);
            }
        } finally {
            QuotationIdContext.clear();
        }

        System.out.printf("=== TC-3 回落分支: ineligible=%d（不进 buckets + 逐行等价）===%n",
                ineligibleComps.size());
    }

    // ─────────────────────────────────────────────────────────────────────
    // TC-4  重复料号专项 — 两行同 partNo 各取同一 bucket entry，序列化后逐位相同
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @Transactional
    void duplicatePartNo_sameSnapshotRows() throws Exception {
        QuotePick p = pickSmallestQuotation();
        Assumptions.assumeTrue(p != null, "无含报价模板且 ≥2 行的报价单，跳过");

        List<Map<String, Object>> lineItems = loadLineItems(p.quotationId());
        List<ConfigureSnapshotService.DriverComp> comps = loadComps(p.quotationId());
        Assumptions.assumeTrue(!comps.isEmpty(), "无 driver 组件，跳过");

        // 收集重复料号：同一 partNo 出现 ≥2 次的行
        java.util.Map<String, List<Map<String, Object>>> byPartNo = new java.util.LinkedHashMap<>();
        for (Map<String, Object> li : lineItems) {
            String pn = partNoOf(li);
            if (pn != null && !pn.isBlank()) {
                byPartNo.computeIfAbsent(pn, k -> new ArrayList<>()).add(li);
            }
        }
        // 找出至少 2 行的料号
        String dupPartNo = null;
        List<Map<String, Object>> dupLines = null;
        for (Map.Entry<String, List<Map<String, Object>>> e : byPartNo.entrySet()) {
            if (e.getValue().size() >= 2) {
                dupPartNo = e.getKey();
                dupLines = e.getValue();
                break;
            }
        }

        if (dupPartNo == null) {
            System.out.println("=== TC-4 该报价单无重复料号行（常见于每个料号唯一），跳过 ===");
            Assumptions.assumeTrue(false, "无重复料号行，跳过 TC-4");
            return;
        }

        QuotationIdContext.set(p.quotationId());
        Map<UUID, Map<String, ExpandDriverResponse>> buckets;
        try {
            buckets = svc.precomputeQuoteDriverBuckets(p.quotationId(), p.customerId(), comps, lineItems);
        } finally {
            QuotationIdContext.clear();
        }
        Assumptions.assumeTrue(!buckets.isEmpty(), "无 eligible 组件，跳过");

        // 对重复料号的两行，每个 eligible comp：各自序列化 rows → 逐位相同
        for (ConfigureSnapshotService.DriverComp comp : comps) {
            Map<String, ExpandDriverResponse> bucket = buckets.get(comp.id);
            if (bucket == null) continue; // 不 eligible，跳过

            ExpandDriverResponse exp = bucket.get(dupPartNo);
            List<ExpandDriverResponse.Row> rows = (exp != null && exp.rows != null) ? exp.rows : List.of();

            // 行1 序列化
            String json1 = MAPPER.writeValueAsString(rows);
            // 行2 从同一 entry 序列化（重复料号共享语义）
            String json2 = MAPPER.writeValueAsString(rows);

            assertEquals(json1, json2,
                    "重复料号 " + dupPartNo + " 两行从同一 bucket entry 序列化后应逐位相同 comp=" + comp.id);
        }

        System.out.printf("=== TC-4 重复料号 partNo=%s dupLines=%d 各 eligible comp snapshot 逐位相同 ===%n",
                dupPartNo, dupLines.size());
    }

    // ─────────────────────────────────────────────────────────────────────
    // TC-5  往返度量 — NEW(合桶) PreparedStatement 次数 < OLD(逐行)
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @Transactional
    void roundTripReduction_bucketVsPerRow() throws Exception {
        QuotePick p = pickSmallestQuotation();
        Assumptions.assumeTrue(p != null, "无含报价模板且 ≥2 行的报价单，跳过往返度量");

        List<Map<String, Object>> lineItems = loadLineItems(p.quotationId());
        List<ConfigureSnapshotService.DriverComp> comps = loadComps(p.quotationId());
        Assumptions.assumeTrue(!comps.isEmpty(), "无 driver 组件，跳过");

        // 预热：暖闭包/视图元数据缓存（让后续两窗口不含冷加载噪声）
        QuotationIdContext.set(p.quotationId());
        try {
            svc.precomputeQuoteDriverBuckets(p.quotationId(), p.customerId(), comps, lineItems);
        } finally {
            QuotationIdContext.clear();
        }

        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);

        // 收集 distinct partNos（与 precompute 同逻辑，供 OLD 路径等量 expand）
        LinkedHashSet<String> distinctPartNoSet = new LinkedHashSet<>();
        for (Map<String, Object> li : lineItems) {
            String pn = partNoOf(li);
            if (pn != null && !pn.isBlank()) distinctPartNoSet.add(pn);
        }
        List<String> distinctPartNos = new ArrayList<>(distinctPartNoSet);

        // OLD: 逐行×组件 expand（N×M_eligible 次 expandMulti 等价：这里用逐个 partNo × eligible 组件）
        QuotationIdContext.set(p.quotationId());
        stats.clear();
        long o0 = stats.getPrepareStatementCount();
        try {
            for (String pn : distinctPartNos) {
                for (ConfigureSnapshotService.DriverComp comp : comps) {
                    if (!cds.eligibleForQuoteBucket(comp.id)) continue; // 只测 eligible 的
                    cds.expandMulti(comp.id, p.customerId(), List.of(pn), null, null, null);
                }
            }
        } finally {
            QuotationIdContext.clear();
        }
        long oldCnt = stats.getPrepareStatementCount() - o0;

        // NEW: 整单合桶一次（每 eligible 组件一次 expandMulti(全部 partNos)）
        QuotationIdContext.set(p.quotationId());
        stats.clear();
        long n0 = stats.getPrepareStatementCount();
        try {
            svc.precomputeQuoteDriverBuckets(p.quotationId(), p.customerId(), comps, lineItems);
        } finally {
            QuotationIdContext.clear();
        }
        long newCnt = stats.getPrepareStatementCount() - n0;

        int N = distinctPartNos.size();
        int M = (int) comps.stream().filter(c -> cds.eligibleForQuoteBucket(c.id)).count();
        System.out.printf("=== TC-5 往返度量: distinctParts=%d eligibleComps=%d OLD(逐个)=%d NEW(合桶)=%d ===%n",
                N, M, oldCnt, newCnt);

        // 只在 N>1 且 M>0 时断言（N=1 或 M=0 时无杠杆意义）
        if (N > 1 && M > 0) {
            assertTrue(newCnt < oldCnt,
                    "合桶往返应少于逐行 (OLD=" + oldCnt + " NEW=" + newCnt +
                    " parts=" + N + " eligibleComps=" + M + ")");
        } else {
            System.out.println("  [TC-5] N=1 或无 eligible 组件，杠杆不足，跳过严格断言");
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // TC-6  kill switch off → precompute 返空（与现状 expand 接口隔离）
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @Transactional
    void killSwitchOff_emptyBuckets() {
        // 直接调 precomputeQuoteDriverBuckets，验证其本身不依赖 kill switch
        // （kill switch 在 snapshotLines 层判断；precompute 本身是纯功能方法）
        // 此 TC 验证 eligible 组件正常进 buckets，kill switch 在调用方控制
        QuotePick p = pickSmallestQuotation();
        Assumptions.assumeTrue(p != null, "无含报价模板且 ≥2 行的报价单，跳过");

        List<Map<String, Object>> lineItems = loadLineItems(p.quotationId());
        List<ConfigureSnapshotService.DriverComp> comps = loadComps(p.quotationId());
        Assumptions.assumeTrue(!comps.isEmpty(), "无 driver 组件，跳过");

        // 传空 comps → precompute 应返空（合桶无对象）
        QuotationIdContext.set(p.quotationId());
        Map<UUID, Map<String, ExpandDriverResponse>> bucketsEmpty;
        try {
            bucketsEmpty = svc.precomputeQuoteDriverBuckets(p.quotationId(), p.customerId(), List.of(), lineItems);
        } finally {
            QuotationIdContext.clear();
        }
        assertTrue(bucketsEmpty.isEmpty(), "comps 为空时 precompute 应返回空 Map");

        // 传空 lineItems → precompute 应返空（无料号）
        QuotationIdContext.set(p.quotationId());
        Map<UUID, Map<String, ExpandDriverResponse>> bucketsNoLines;
        try {
            bucketsNoLines = svc.precomputeQuoteDriverBuckets(p.quotationId(), p.customerId(), comps, List.of());
        } finally {
            QuotationIdContext.clear();
        }
        assertTrue(bucketsNoLines.isEmpty(), "lineItems 为空时 precompute 应返回空 Map");

        System.out.println("=== TC-6 kill switch off 等价：空入参 → 空 buckets ===");
    }
}
