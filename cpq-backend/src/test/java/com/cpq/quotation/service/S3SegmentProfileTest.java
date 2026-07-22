package com.cpq.quotation.service;

import com.cpq.component.dto.ExpandDriverResponse;
import com.cpq.component.service.ComponentDriverService;
import com.cpq.formula.dataloader.QuotationIdContext;
import com.cpq.quotation.entity.Quotation;
import com.cpq.quotation.entity.QuotationLineItem;
import io.quarkus.narayana.jta.runtime.TransactionConfiguration;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.node.ArrayNode;

import java.util.*;

/**
 * S3 分段 profiling(2026-06-26,P3 立项前定位)。<b>非断言、只读</b>:把首存 S3(per 新行 card values)
 * 的 10-12s 拆成「报价侧 buildCardValues(复用 snapshot_rows,公式+序列化)」vs「核价侧 buildCostingCardValues
 * (BOM expand + 组装 + 序列化,union/prefetch 已应用)」,定位 P3 §2.3(多 line expand)该打哪侧。
 *
 * <p>生产路径同款:precomputeCostingDriverUnion + precomputeCardValuesPrefetch 先建,再逐行调两侧 build；
 * 含树页签模板另先整单调 {@link BomTreeRenderService#render} 拿 precomputedBaseRows（Task 5.2
 * 硬切换后 {@code buildCostingCardValues} 6 参重载对含树页签模板不再兜底出正确结果，须显式按
 * {@code templateHasTreeTab} 分支同生产路径）。两个 build 都返回 String、不写实体,故只读不污染。
 * 冷态:evictAll 取导入首存真实场景。
 */
@QuarkusTest
class S3SegmentProfileTest {

    @Inject QuotationService quotationService;
    @Inject CardSnapshotService cardSnapshotService;
    @Inject BomTreeRenderService bomTreeRenderService;
    @Inject ComponentDriverService componentDriverService;
    @Inject com.cpq.quotation.service.ExcelViewService excelViewService;
    @Inject com.cpq.template.service.TemplateFormulaService templateFormulaService;

    private static final UUID ROCKWELL_QID = UUID.fromString("8f0c37a4-8186-4f5e-a9ca-358bd2d9662d");

    private static double pct(long part, long total) { return total == 0 ? 0.0 : part * 100.0 / total; }
    private static double perRow(long ms, int n) { return n == 0 ? 0.0 : ms * 1.0 / n; }

    @Test
    void profileS3_rockwell() {
        // -Dqid=<uuid> 覆盖基准单(用户实测单);缺省 rockwell
        String qidProp = System.getProperty("qid");
        UUID qid = (qidProp != null && !qidProp.isBlank()) ? UUID.fromString(qidProp) : ROCKWELL_QID;
        profileOne(qid, "QID-" + qid);
    }

    @Transactional
    @TransactionConfiguration(timeout = 600)
    void profileOne(UUID qid, String label) {
        Quotation q = Quotation.findById(qid);
        Assumptions.assumeTrue(q != null, label + " 不存在,跳过");
        List<QuotationLineItem> lines = QuotationLineItem.list("quotationId", qid);
        Assumptions.assumeTrue(!lines.isEmpty(), label + " 无行,跳过");
        Assumptions.assumeTrue(q.costingCardTemplateId != null, label + " 无核价模板,跳过");

        int n = lines.size();
        List<UUID> lineIds = new ArrayList<>();
        for (QuotationLineItem li : lines) lineIds.add(li.id);

        // 冷态(= 导入首存真实场景)
        componentDriverService.evictAll();

        QuotationIdContext.set(qid);
        try {
            long t0 = System.nanoTime();
            Map<UUID, Map<String, ExpandDriverResponse>> union =
                    cardSnapshotService.precomputeCostingDriverUnion(qid);
            long unionMs = (System.nanoTime() - t0) / 1_000_000;

            long t1 = System.nanoTime();
            CardSnapshotService.CardValuesPrefetch prefetch =
                    cardSnapshotService.precomputeCardValuesPrefetch(qid, lineIds);
            long prefetchMs = (System.nanoTime() - t1) / 1_000_000;

            // 生产路径同款(Task 3.1/5.2)：含树页签模板先整单调 BomTreeRenderService.render,
            // 逐 li 复用其结果；不含树页签 → 恒空 map,下方 getOrDefault 恒 null → 走非树页签平铺路径。
            Map<UUID, Map<String, ArrayNode>> treeBaseRowsByLine = Collections.emptyMap();
            if (cardSnapshotService.templateHasTreeTab(q.costingCardTemplateId)) {
                treeBaseRowsByLine = bomTreeRenderService.render(q.costingCardTemplateId, lines);
            }

            long quoteMs = 0, costingMs = 0, quoteExcelMs = 0, costingExcelMs = 0;
            long quoteBytes = 0, costingBytes = 0, quoteExcelBytes = 0, costingExcelBytes = 0;
            for (QuotationLineItem li : lines) {
                QuotationLineItem managed = QuotationLineItem.findById(li.id);
                if (managed == null) continue;

                long a = System.nanoTime();
                String quote = cardSnapshotService.buildCardValues(managed, q.customerTemplateId, prefetch);
                quoteMs += (System.nanoTime() - a) / 1_000_000;
                if (quote != null) quoteBytes += quote.length();

                // 报价 Excel(导入新行 quoteExcelValues=null → 生产 S3 会跑;这里强制跑以测真实成本)
                long c = System.nanoTime();
                String quoteExcel = cardSnapshotService.buildExcelValues(managed, q.customerTemplateId, q.customerId, quote);
                quoteExcelMs += (System.nanoTime() - c) / 1_000_000;
                if (quoteExcel != null) quoteExcelBytes += quoteExcel.length();

                long b = System.nanoTime();
                Map<String, ArrayNode> precomputed = treeBaseRowsByLine.get(li.id);
                String costing = cardSnapshotService.buildCostingCardValues(
                        managed, q.costingCardTemplateId, q.customerId, qid, union, prefetch, precomputed);
                costingMs += (System.nanoTime() - b) / 1_000_000;
                if (costing != null) costingBytes += costing.length();

                // 核价 Excel(生产 S3 无 null 守卫,恒跑)
                long d = System.nanoTime();
                String costingExcel = cardSnapshotService.buildExcelValues(
                        managed, q.costingCardTemplateId, q.customerId, costing, true);
                costingExcelMs += (System.nanoTime() - d) / 1_000_000;
                if (costingExcel != null) costingExcelBytes += costingExcel.length();
            }

            // 量化「per-line 重复 template 不变量再推导」可 hoist 的部分(quote + costing 两模板)
            long redundantMs = 0;
            for (QuotationLineItem li : lines) {
                long t = System.nanoTime();
                com.cpq.template.entity.Template tq = com.cpq.template.entity.Template.findById(q.customerTemplateId);
                if (tq != null) excelViewService.getEffectiveColumns(tq);
                templateFormulaService.listByTemplate(q.customerTemplateId);
                com.cpq.template.entity.Template tc = com.cpq.template.entity.Template.findById(q.costingCardTemplateId);
                if (tc != null) excelViewService.getEffectiveColumns(tc);
                templateFormulaService.listByTemplate(q.costingCardTemplateId);
                redundantMs += (System.nanoTime() - t) / 1_000_000;
            }
            long onceT = System.nanoTime();
            com.cpq.template.entity.Template tq1 = com.cpq.template.entity.Template.findById(q.customerTemplateId);
            if (tq1 != null) excelViewService.getEffectiveColumns(tq1);
            templateFormulaService.listByTemplate(q.customerTemplateId);
            com.cpq.template.entity.Template tc1 = com.cpq.template.entity.Template.findById(q.costingCardTemplateId);
            if (tc1 != null) excelViewService.getEffectiveColumns(tc1);
            templateFormulaService.listByTemplate(q.costingCardTemplateId);
            long onceMs = (System.nanoTime() - onceT) / 1_000_000;

            long buildTotal = quoteMs + costingMs + quoteExcelMs + costingExcelMs;
            System.out.println("\n========== S3-PROFILE " + label + " lines=" + n + " ==========");
            System.out.printf("[setup] precomputeCostingDriverUnion=%dms  precomputeCardValuesPrefetch=%dms%n",
                    unionMs, prefetchMs);
            System.out.printf("[报价卡 buildCardValues]        total=%5dms  (%.1f%%)  ~%.1fms/行  out=%dKB%n",
                    quoteMs, pct(quoteMs, buildTotal), perRow(quoteMs, n), quoteBytes / 1024);
            System.out.printf("[报价Excel buildExcelValues]    total=%5dms  (%.1f%%)  ~%.1fms/行  out=%dKB%n",
                    quoteExcelMs, pct(quoteExcelMs, buildTotal), perRow(quoteExcelMs, n), quoteExcelBytes / 1024);
            System.out.printf("[核价卡 buildCostingCardValues] total=%5dms  (%.1f%%)  ~%.1fms/行  out=%dKB%n",
                    costingMs, pct(costingMs, buildTotal), perRow(costingMs, n), costingBytes / 1024);
            System.out.printf("[核价Excel buildExcelValues]    total=%5dms  (%.1f%%)  ~%.1fms/行  out=%dKB%n",
                    costingExcelMs, pct(costingExcelMs, buildTotal), perRow(costingExcelMs, n), costingExcelBytes / 1024);
            System.out.printf("[★可hoist模板不变量] per-line 重复(findById+getEffectiveColumns+listByTemplate ×2模板)=%dms, 提到循环外只需=%dms → 可省 ~%dms%n",
                    redundantMs, onceMs, redundantMs - onceMs);
            System.out.printf("[合计] setup=%dms + build(4 段)=%dms = %dms%n",
                    unionMs + prefetchMs, buildTotal, unionMs + prefetchMs + buildTotal);
            System.out.println("==========================================================\n");
        } finally {
            QuotationIdContext.clear();
        }
    }
}
