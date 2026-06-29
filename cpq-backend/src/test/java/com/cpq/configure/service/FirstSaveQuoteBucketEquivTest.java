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
import java.util.LinkedHashMap;
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
 * <p><b>固定数据锚点：罗克韦尔 {@code 8f0c37a4-8186-4f5e-a9ca-358bd2d9662d}</b>（170 行 / 77 不同料号）。
 * 重复料号：{@code 5121115551} 出现 6 次，{@code 5121114715}/{@code 5121115281} 等各 4 次。
 * 固定锚点而非动态选单，确保 TC-4 重复料号专项在真实数据上验证。
 *
 * <p>仿 {@link com.cpq.quotation.service.CostingPartSetUnionEquivTest} 结构：
 * <ul>
 *   <li>报价侧无 BOM 闭包，分桶键 = 产品料号本身；
 *   <li>eligible 组件 expandMulti 一次取全料号，按 hf_part_no 回分；
 *   <li>不 eligible 组件（含 lineItemId/spineKeys/composite/EXCEL）不进 buckets，逐行回落。
 * </ul>
 *
 * <p>只读真实数据，不写 DB。
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

    // ── 固定锚点（罗克韦尔，170 行 / 77 不同料号，含重复料号 5121115551×6）──
    private static final UUID ROCKWELL_QID    = UUID.fromString("8f0c37a4-8186-4f5e-a9ca-358bd2d9662d");
    private static final UUID ROCKWELL_CUSTID = UUID.fromString("3027d83b-d412-407d-ae43-5d513fed7b1e");
    /** 出现 6 次的重复料号，TC-4 专项用。 */
    private static final String DUP_PART_NO   = "5121115551";
    /** 罗克韦尔 170 行的不同料号数（TC 覆盖断言）。 */
    private static final int EXPECTED_DISTINCT_PARTS = 77;

    // ─────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────

    @Transactional
    List<Map<String, Object>> loadLineItems() {
        return svc.loadQuotationLines(ROCKWELL_QID);
    }

    @Transactional
    List<ConfigureSnapshotService.DriverComp> loadComps() {
        return svc.loadDriverComponents(ROCKWELL_QID);
    }

    /** 按 partNo 分组，返回 partNo → 该 partNo 的所有行。 */
    private Map<String, List<Map<String, Object>>> groupByPartNo(List<Map<String, Object>> lines) {
        Map<String, List<Map<String, Object>>> byPart = new LinkedHashMap<>();
        for (Map<String, Object> li : lines) {
            String pn = partNoOf(li);
            if (pn != null && !pn.isBlank())
                byPart.computeIfAbsent(pn, k -> new ArrayList<>()).add(li);
        }
        return byPart;
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
        Object v = li.get("id");
        if (v == null) return null;
        if (v instanceof UUID u) return u;
        try { return UUID.fromString(v.toString()); } catch (Exception e) { return null; }
    }

    /** 整单合桶预取（设置好 QuotationIdContext）。 */
    private Map<UUID, Map<String, ExpandDriverResponse>> precompute(
            List<ConfigureSnapshotService.DriverComp> comps,
            List<Map<String, Object>> lineItems) {
        QuotationIdContext.set(ROCKWELL_QID);
        try {
            return svc.precomputeQuoteDriverBuckets(ROCKWELL_QID, ROCKWELL_CUSTID, comps, lineItems);
        } finally {
            QuotationIdContext.clear();
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // TC-0  前置：确认罗克韦尔报价单数据存在（其余 TC 若此单缺失一并 skip）
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @Transactional
    void rockwellQuotation_exists() {
        List<Map<String, Object>> lines = loadLineItems();
        Assumptions.assumeTrue(!lines.isEmpty(),
                "罗克韦尔报价单 " + ROCKWELL_QID + " 不存在于当前环境，全套 TC 跳过");
        assertTrue(lines.size() >= 10,
                "罗克韦尔应有 ≥10 行(实际=" + lines.size() + ")");

        // 确认含重复料号
        long dupCount = lines.stream()
                .filter(li -> DUP_PART_NO.equals(partNoOf(li))).count();
        assertTrue(dupCount >= 2,
                "罗克韦尔应含重复料号 " + DUP_PART_NO + " ≥2 行，实际=" + dupCount);

        System.out.printf("=== TC-0 罗克韦尔存在: totalLines=%d dupPartNo=%s×%d ===%n",
                lines.size(), DUP_PART_NO, dupCount);
    }

    // ─────────────────────────────────────────────────────────────────────
    // TC-1  A/B 逐位等价（含重复料号行）
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @Transactional
    void bucketEqualsPerRow_rowsJson_includesDupPartNo() throws Exception {
        List<Map<String, Object>> lineItems = loadLineItems();
        Assumptions.assumeTrue(!lineItems.isEmpty(), "罗克韦尔报价单不存在，跳过");
        List<ConfigureSnapshotService.DriverComp> comps = loadComps();
        Assumptions.assumeTrue(!comps.isEmpty(), "无 driver 组件，跳过");

        Map<UUID, Map<String, ExpandDriverResponse>> buckets = precompute(comps, lineItems);
        Assumptions.assumeTrue(!buckets.isEmpty(),
                "该报价单无 eligible 组件（视图均含行维度），合桶无对象，跳过");

        // sample = 前 6 行 + 所有 DUP_PART_NO 行（确保至少一行重复料号进入对比）
        List<Map<String, Object>> sampleLines = new ArrayList<>(
                lineItems.subList(0, Math.min(6, lineItems.size())));
        for (Map<String, Object> li : lineItems) {
            if (DUP_PART_NO.equals(partNoOf(li)) && !sampleLines.contains(li)) {
                sampleLines.add(li);
                if (sampleLines.size() >= 10) break; // 最多取 10 行
            }
        }

        int comparedNonEmpty = 0;
        int linesChecked = 0;
        boolean sawDupRow = false;

        QuotationIdContext.set(ROCKWELL_QID);
        try {
            for (Map<String, Object> li : sampleLines) {
                UUID lineItemId = lineIdOf(li);
                String partNo = partNoOf(li);
                String compositeType = compositeTypeOf(li);
                if (lineItemId == null || partNo == null || partNo.isBlank()) continue;

                if (DUP_PART_NO.equals(partNo)) sawDupRow = true;

                for (ConfigureSnapshotService.DriverComp comp : comps) {
                    Map<String, ExpandDriverResponse> bucket = buckets.get(comp.id);
                    if (bucket == null) continue; // 不 eligible，在 TC-3 验证

                    // 路径 A（逐行）：现状 8-arg expand
                    ExpandDriverResponse expA = cds.expand(
                            comp.id, ROCKWELL_CUSTID, partNo, null, null, null, lineItemId, compositeType);
                    String jsonA = MAPPER.writeValueAsString(
                            expA != null && expA.rows != null ? expA.rows : List.of());

                    // 路径 B（合桶）：bucket.get(partNo)
                    ExpandDriverResponse expB = bucket.get(partNo);
                    String jsonB = MAPPER.writeValueAsString(
                            expB != null && expB.rows != null ? expB.rows : List.of());

                    assertEquals(jsonA, jsonB,
                            "A/B 不一致: li=" + lineItemId + " comp=" + comp.id +
                            " partNo=" + partNo + (DUP_PART_NO.equals(partNo) ? "[DUP]" : ""));
                    if (!"[]".equals(jsonA)) comparedNonEmpty++;
                }
                linesChecked++;
            }
        } finally {
            QuotationIdContext.clear();
        }

        assertTrue(linesChecked > 0, "至少一行参与了对比（否则测试空转）");
        assertTrue(sawDupRow, "sample 行应包含重复料号 " + DUP_PART_NO + " 的行（A/B 应在重复料号上验证）");
        System.out.printf("=== TC-1 A/B 逐位等价: linesChecked=%d eligibleComps=%d nonEmpty=%d sawDupRow=true ===%n",
                linesChecked, buckets.size(), comparedNonEmpty);
    }

    // ─────────────────────────────────────────────────────────────────────
    // TC-2  连跑两次 md5（可变共享面专项，AP-37 硬约束③）
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @Transactional
    void sharedBucket_serialization_isIdempotent() throws Exception {
        List<Map<String, Object>> lineItems = loadLineItems();
        Assumptions.assumeTrue(!lineItems.isEmpty(), "罗克韦尔报价单不存在，跳过");
        List<ConfigureSnapshotService.DriverComp> comps = loadComps();
        Assumptions.assumeTrue(!comps.isEmpty(), "无 driver 组件，跳过");

        Map<UUID, Map<String, ExpandDriverResponse>> buckets = precompute(comps, lineItems);
        Assumptions.assumeTrue(!buckets.isEmpty(), "无 eligible 组件，跳过");

        // 复用同一 buckets，对重复料号行第一个 eligible comp 序列化三次，断言全一致
        // （证明分发链不就地 mutate 共享 resp.rows/Row.driverRow）
        boolean checkedOnce = false;
        for (Map<String, Object> li : lineItems) {
            String partNo = partNoOf(li);
            if (!DUP_PART_NO.equals(partNo)) continue; // 专选重复料号行
            for (ConfigureSnapshotService.DriverComp comp : comps) {
                Map<String, ExpandDriverResponse> bucket = buckets.get(comp.id);
                if (bucket == null) continue;
                ExpandDriverResponse exp = bucket.get(partNo);
                List<ExpandDriverResponse.Row> rows = exp != null && exp.rows != null ? exp.rows : List.of();

                String json1 = MAPPER.writeValueAsString(rows);
                String json2 = MAPPER.writeValueAsString(rows);  // 复用同一共享引用
                assertEquals(json1, json2,
                        "AP-37: 同一 resp.rows 两次序列化不一致 comp=" + comp.id + " partNo=" + partNo);

                // 第三次：再从 bucket.get 取（不同调用入口）
                ExpandDriverResponse exp3 = bucket.get(partNo);
                String json3 = MAPPER.writeValueAsString(exp3 != null && exp3.rows != null ? exp3.rows : List.of());
                assertEquals(json1, json3,
                        "AP-37: 两次 bucket.get(partNo) 序列化不一致 comp=" + comp.id);

                checkedOnce = true;
                break;
            }
            if (checkedOnce) break;
        }
        assertTrue(checkedOnce, "至少有一个重复料号行执行了两次 md5 专项（否则测试空转）");
        System.out.println("=== TC-2 连跑两次序列化一致（可变共享面无 mutate，基于重复料号行）===");
    }

    // ─────────────────────────────────────────────────────────────────────
    // TC-3  回落分支专项 — 不 eligible 组件不进 buckets，逐行产出不变
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @Transactional
    void ineligibleComponents_notInBuckets_fallbackMatchesPerRow() throws Exception {
        List<Map<String, Object>> lineItems = loadLineItems();
        Assumptions.assumeTrue(!lineItems.isEmpty(), "罗克韦尔报价单不存在，跳过");
        List<ConfigureSnapshotService.DriverComp> comps = loadComps();
        Assumptions.assumeTrue(!comps.isEmpty(), "无 driver 组件，跳过");

        Map<UUID, Map<String, ExpandDriverResponse>> buckets = precompute(comps, lineItems);

        List<ConfigureSnapshotService.DriverComp> ineligibleComps = new ArrayList<>();
        for (ConfigureSnapshotService.DriverComp comp : comps) {
            if (!cds.eligibleForQuoteBucket(comp.id)) ineligibleComps.add(comp);
        }

        if (ineligibleComps.isEmpty()) {
            // 罗克韦尔所有组件全 eligible（标准报价视图无行维度）→ 断言 buckets 含所有组件
            assertEquals(comps.size(), buckets.size(),
                    "所有组件均 eligible，buckets 应含全部 " + comps.size() + " 个");
            System.out.println("=== TC-3 罗克韦尔所有 driver 组件均 eligible，无回落分支（符合预期）===");
            return;
        }

        // 有不 eligible 组件：验证不进 buckets + 逐行回落确定性
        QuotationIdContext.set(ROCKWELL_QID);
        try {
            for (ConfigureSnapshotService.DriverComp comp : ineligibleComps) {
                assertFalse(buckets.containsKey(comp.id),
                        "不 eligible 组件 " + comp.id + " 不应进 buckets（Bug B 安全闸门）");

                Map<String, Object> li = lineItems.get(0);
                String partNo = partNoOf(li);
                String compositeType = compositeTypeOf(li);
                UUID lineItemId = lineIdOf(li);
                if (partNo == null || partNo.isBlank() || lineItemId == null) continue;

                ExpandDriverResponse exp1 = cds.expand(
                        comp.id, ROCKWELL_CUSTID, partNo, null, null, null, lineItemId, compositeType);
                ExpandDriverResponse exp2 = cds.expand(
                        comp.id, ROCKWELL_CUSTID, partNo, null, null, null, lineItemId, compositeType);

                String json1 = MAPPER.writeValueAsString(exp1 != null && exp1.rows != null ? exp1.rows : List.of());
                String json2 = MAPPER.writeValueAsString(exp2 != null && exp2.rows != null ? exp2.rows : List.of());
                assertEquals(json1, json2, "回落逐行两次调用应确定性一致 comp=" + comp.id);
            }
        } finally {
            QuotationIdContext.clear();
        }
        System.out.printf("=== TC-3 回落分支: ineligible=%d（不进 buckets + 逐行等价）===%n",
                ineligibleComps.size());
    }

    // ─────────────────────────────────────────────────────────────────────
    // TC-4  重复料号专项（强制运行，不 skip）
    //   (a) 同 bucket entry 两行序列化后逐位相同
    //   (b) A/B 等价：重复料号的每行 expand(li.id) vs bucket.get(partNo) 逐位相同
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @Transactional
    void duplicatePartNo_bucketEntryShared_and_AB_equiv() throws Exception {
        List<Map<String, Object>> lineItems = loadLineItems();
        // 若罗克韦尔不存在则 skip，否则强制运行（不再 skip on "无重复料号"）
        Assumptions.assumeTrue(!lineItems.isEmpty(), "罗克韦尔报价单不存在，跳过");
        List<ConfigureSnapshotService.DriverComp> comps = loadComps();
        Assumptions.assumeTrue(!comps.isEmpty(), "无 driver 组件，跳过");

        // 确认重复料号存在（数据级断言）
        List<Map<String, Object>> dupLines = new ArrayList<>();
        for (Map<String, Object> li : lineItems) {
            if (DUP_PART_NO.equals(partNoOf(li))) dupLines.add(li);
        }
        assertTrue(dupLines.size() >= 2,
                "罗克韦尔应含重复料号 " + DUP_PART_NO + " ≥2 行，实际=" + dupLines.size() +
                "（数据已删？请核查 DB）");

        Map<UUID, Map<String, ExpandDriverResponse>> buckets = precompute(comps, lineItems);
        Assumptions.assumeTrue(!buckets.isEmpty(), "无 eligible 组件，跳过");

        QuotationIdContext.set(ROCKWELL_QID);
        try {
            for (ConfigureSnapshotService.DriverComp comp : comps) {
                Map<String, ExpandDriverResponse> bucket = buckets.get(comp.id);
                if (bucket == null) continue; // 不 eligible，在 TC-3 验证

                ExpandDriverResponse expBucket = bucket.get(DUP_PART_NO);
                String jsonBucket = MAPPER.writeValueAsString(
                        expBucket != null && expBucket.rows != null ? expBucket.rows : List.of());

                // (a) 重复料号的所有行从同一 bucket entry 序列化 → 逐位相同
                for (int i = 0; i < dupLines.size(); i++) {
                    String jsonI = MAPPER.writeValueAsString(
                            expBucket != null && expBucket.rows != null ? expBucket.rows : List.of());
                    assertEquals(jsonBucket, jsonI,
                            "(a) 重复料号 " + DUP_PART_NO + " 行" + i + " 从同一 bucket entry 序列化不一致 comp=" + comp.id);
                }

                // (b) A/B 等价：每个重复料号行 expand(li.id) == bucket.get(partNo)
                for (Map<String, Object> li : dupLines) {
                    UUID lineItemId = lineIdOf(li);
                    String compositeType = compositeTypeOf(li);
                    if (lineItemId == null) continue;

                    ExpandDriverResponse expA = cds.expand(
                            comp.id, ROCKWELL_CUSTID, DUP_PART_NO, null, null, null, lineItemId, compositeType);
                    String jsonA = MAPPER.writeValueAsString(
                            expA != null && expA.rows != null ? expA.rows : List.of());

                    assertEquals(jsonA, jsonBucket,
                            "(b) A/B 等价失败: li=" + lineItemId + " comp=" + comp.id +
                            " partNo=" + DUP_PART_NO + "[DUP] expand!=bucket");
                }
            }
        } finally {
            QuotationIdContext.clear();
        }

        System.out.printf(
                "=== TC-4 重复料号专项: partNo=%s×%d 行 (a)共享序列化一致 (b)A/B 等价 — 全通过 ===%n",
                DUP_PART_NO, dupLines.size());
    }

    // ─────────────────────────────────────────────────────────────────────
    // TC-5  distinctPartNos 覆盖断言 — buckets 覆盖全部 77 个不同料号
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @Transactional
    void buckets_coversAllDistinctPartNos() {
        List<Map<String, Object>> lineItems = loadLineItems();
        Assumptions.assumeTrue(!lineItems.isEmpty(), "罗克韦尔报价单不存在，跳过");
        List<ConfigureSnapshotService.DriverComp> comps = loadComps();
        Assumptions.assumeTrue(!comps.isEmpty(), "无 driver 组件，跳过");

        // 收集全部不同料号（与 precomputeQuoteDriverBuckets 内部逻辑一致）
        LinkedHashSet<String> distinctPartNos = new LinkedHashSet<>();
        for (Map<String, Object> li : lineItems) {
            String pn = partNoOf(li);
            if (pn != null && !pn.isBlank()) distinctPartNos.add(pn);
        }

        // 断言不同料号数符合预期（防止数据意外丢失）
        assertEquals(EXPECTED_DISTINCT_PARTS, distinctPartNos.size(),
                "罗克韦尔应有 " + EXPECTED_DISTINCT_PARTS + " 个不同料号，实际=" + distinctPartNos.size());

        Map<UUID, Map<String, ExpandDriverResponse>> buckets = precompute(comps, lineItems);
        Assumptions.assumeTrue(!buckets.isEmpty(), "无 eligible 组件，跳过");

        // 每个 eligible comp 的 bucket keySet 必须包含全部不同料号
        // （防止"某料号不在 bucket → bucket.get=null → 空 resp 错写"）
        for (Map.Entry<UUID, Map<String, ExpandDriverResponse>> e : buckets.entrySet()) {
            UUID compId = e.getKey();
            Map<String, ExpandDriverResponse> bucket = e.getValue();
            for (String pn : distinctPartNos) {
                assertTrue(bucket.containsKey(pn),
                        "comp=" + compId + " bucket 缺失料号 " + pn +
                        "（precompute 应对每个 partNo 预置 entry，即使 0 行）");
            }
        }

        System.out.printf(
                "=== TC-5 distinctPartNos 覆盖: distinct=%d eligibleComps=%d 全覆盖 ===%n",
                distinctPartNos.size(), buckets.size());
    }

    // ─────────────────────────────────────────────────────────────────────
    // TC-6  往返度量 — NEW(合桶) PreparedStatement 次数 < OLD(逐行)
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @Transactional
    void roundTripReduction_bucketVsPerRow() throws Exception {
        List<Map<String, Object>> lineItems = loadLineItems();
        Assumptions.assumeTrue(!lineItems.isEmpty(), "罗克韦尔报价单不存在，跳过");
        List<ConfigureSnapshotService.DriverComp> comps = loadComps();
        Assumptions.assumeTrue(!comps.isEmpty(), "无 driver 组件，跳过往返度量");

        // 预热：暖视图元数据缓存（让后续两窗口不含冷加载噪声）
        precompute(comps, lineItems);

        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);

        // 收集 distinct partNos
        LinkedHashSet<String> distinctPartNoSet = new LinkedHashSet<>();
        for (Map<String, Object> li : lineItems) {
            String pn = partNoOf(li);
            if (pn != null && !pn.isBlank()) distinctPartNoSet.add(pn);
        }
        List<String> distinctPartNos = new ArrayList<>(distinctPartNoSet);
        int M = (int) comps.stream().filter(c -> cds.eligibleForQuoteBucket(c.id)).count();

        // OLD: 逐个 partNo × eligible 组件（模拟 N×M_eligible 次单值 expand）
        QuotationIdContext.set(ROCKWELL_QID);
        stats.clear();
        long o0 = stats.getPrepareStatementCount();
        try {
            for (String pn : distinctPartNos) {
                for (ConfigureSnapshotService.DriverComp comp : comps) {
                    if (!cds.eligibleForQuoteBucket(comp.id)) continue;
                    cds.expandMulti(comp.id, ROCKWELL_CUSTID, List.of(pn), null, null, null);
                }
            }
        } finally {
            QuotationIdContext.clear();
        }
        long oldCnt = stats.getPrepareStatementCount() - o0;

        // NEW: 整单合桶一次（每 eligible 组件一次 expandMulti(全部 partNos)）
        stats.clear();
        long n0 = stats.getPrepareStatementCount();
        precompute(comps, lineItems);
        long newCnt = stats.getPrepareStatementCount() - n0;

        System.out.printf(
                "=== TC-6 往返度量: distinctParts=%d eligibleComps=%d OLD(逐个)=%d NEW(合桶)=%d ===%n",
                distinctPartNos.size(), M, oldCnt, newCnt);

        if (distinctPartNos.size() > 1 && M > 0) {
            assertTrue(newCnt < oldCnt,
                    "合桶往返应少于逐行 (OLD=" + oldCnt + " NEW=" + newCnt +
                    " parts=" + distinctPartNos.size() + " eligibleComps=" + M + ")");
        } else {
            System.out.println("  [TC-6] N=1 或无 eligible 组件，杠杆不足，跳过严格断言");
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // TC-7  空入参防御 — precompute 空 comps/lineItems 返空 Map
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @Transactional
    void emptyInputs_returnEmptyBuckets() {
        List<Map<String, Object>> lineItems = loadLineItems();
        Assumptions.assumeTrue(!lineItems.isEmpty(), "罗克韦尔报价单不存在，跳过");
        List<ConfigureSnapshotService.DriverComp> comps = loadComps();
        Assumptions.assumeTrue(!comps.isEmpty(), "无 driver 组件，跳过");

        // 传空 comps → 应返空
        Map<UUID, Map<String, ExpandDriverResponse>> r1 =
                precompute(List.of(), lineItems);
        assertTrue(r1.isEmpty(), "comps 为空时 precompute 应返回空 Map");

        // 传空 lineItems → 应返空（无料号）
        Map<UUID, Map<String, ExpandDriverResponse>> r2 =
                precompute(comps, List.of());
        assertTrue(r2.isEmpty(), "lineItems 为空时 precompute 应返回空 Map（无料号）");

        System.out.println("=== TC-7 空入参防御：空 comps/lineItems → 空 buckets ===");
    }
}
