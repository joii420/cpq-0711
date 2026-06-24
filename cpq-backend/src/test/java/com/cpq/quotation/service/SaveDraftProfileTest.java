package com.cpq.quotation.service;

import com.cpq.component.service.BomClosureService;
import com.cpq.component.service.ComponentDriverService;
import com.cpq.formula.dataloader.QuotationIdContext;
import com.cpq.quotation.dto.QuotationDTO;
import com.cpq.quotation.entity.Quotation;
import com.cpq.quotation.entity.QuotationLineItem;
import io.quarkus.narayana.jta.runtime.TransactionConfiguration;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Phase 0 profiling（2026-06-24,集合化重构前的"是哪段慢"实测）。<b>非断言测试</b>:只跑 + 打印分段耗时,
 * 用真实单回答两件事:
 * <ol>
 *   <li><b>H1 vs H2</b>:净环境(无 autosave 风暴)直接 {@code getById} 多大?{@code <2s}=H2(它被风暴拖死,
 *       止血即够);慢=H1(getById 自身也要治)。</li>
 *   <li><b>BOM 递归是不是主因</b>:对比"逐行 BOM 闭包冷态总耗时" vs "整单逐行 buildCostingCardValues 冷态总耗时",
 *       闭包占比小 → 主因是 driver expand/序列化而非递归。</li>
 * </ol>
 *
 * <p>缓存纪律:闭包/expand 都有进程级 Caffeine,测前 {@code evictAll} 取冷态(=导入首存的真实场景)。
 */
@QuarkusTest
class SaveDraftProfileTest {

    @Inject QuotationService quotationService;
    @Inject CardSnapshotService cardSnapshotService;
    @Inject BomClosureService bomClosureService;
    @Inject ComponentDriverService componentDriverService;

    /** 空白 BUG 复现单(77 行,QT-20260624-1844)。 */
    private static final UUID REPRO_QID = UUID.fromString("939e072e-bcef-4230-9eb2-66cb64dbe8e1");
    /** 罗克韦尔基准单(170 行)。 */
    private static final UUID ROCKWELL_QID = UUID.fromString("8f0c37a4-8186-4f5e-a9ca-358bd2d9662d");

    @Test
    void profile_reproQuote_939e072e() throws Exception {
        profileOne(REPRO_QID, "REPRO-939e072e(77行)");
    }

    @Test
    void profile_rockwell_8f0c37a4() throws Exception {
        profileOne(ROCKWELL_QID, "ROCKWELL-8f0c37a4(170行)");
    }

    // package-private + @Transactional + 由 @Test 直接调用 → Quarkus 测试拦截生效(B2 同款);
    // 整段在一个事务内,Panache findById 有会话。getById run#1 为冷态读数,#2/#3 同会话 L1 偏热。
    @Transactional
    @TransactionConfiguration(timeout = 600)
    void profileOne(UUID qid, String label) throws Exception {
        Quotation q = Quotation.findById(qid);
        Assumptions.assumeTrue(q != null, label + " 不存在,跳过");
        int n = QuotationLineItem.<QuotationLineItem>list("quotationId", qid).size();
        Assumptions.assumeTrue(n > 0, label + " 无行,跳过");

        System.out.println("\n========== PROFILE " + label + " lines=" + n + " ==========");

        // ① getById ×3(H1/H2)——读路径,不重算卡片值,读已存快照 + drift
        for (int i = 1; i <= 3; i++) {
            long t0 = System.nanoTime();
            QuotationDTO dto = getByIdTimed(qid);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            int liN = dto == null || dto.lineItems == null ? -1 : dto.lineItems.size();
            System.out.printf("[getById] run#%d = %d ms (lineItems=%d)%n", i, ms, liN);
        }

        // ② BOM 闭包冷态总耗时(隔离"递归"那段)
        long closureMs = closureColdTotal(qid);
        System.out.printf("[BOM-closure cold] sum over %d lines = %d ms%n", n, closureMs);

        // ③ 整单逐行 buildCostingCardValues 冷态总耗时(= 当前慢路径全貌:闭包+expand+assemble+序列化)
        if (q.costingCardTemplateId != null) {
            long fullMs = costingFullColdTotal(qid);
            System.out.printf("[costing buildCostingCardValues cold] sum over %d lines = %d ms%n", n, fullMs);
            System.out.printf("[结论] 闭包占核价构建比例 ≈ %.1f%% (closure %d / full %d ms)%n",
                    fullMs == 0 ? 0.0 : (closureMs * 100.0 / fullMs), closureMs, fullMs);
        } else {
            System.out.println("[costing] 无核价模板,跳过 ③");
        }
        System.out.println("========== END " + label + " ==========\n");
    }

    QuotationDTO getByIdTimed(UUID qid) {
        return quotationService.getById(qid);
    }

    long closureColdTotal(UUID qid) {
        bomClosureService.evictAll();
        List<QuotationLineItem> lines = QuotationLineItem.list("quotationId", qid);
        long t0 = System.nanoTime();
        for (QuotationLineItem li : lines) {
            String part = li.productPartNoSnapshot;
            if (part == null || part.isBlank()) continue;
            bomClosureService.compute(part, Map.of());
        }
        return (System.nanoTime() - t0) / 1_000_000;
    }

    long costingFullColdTotal(UUID qid) {
        Quotation q = Quotation.findById(qid);
        // 冷态:清闭包 + expand 进程缓存(= 导入首存真实场景)
        bomClosureService.evictAll();
        componentDriverService.evictAll();
        List<QuotationLineItem> lines = QuotationLineItem.list("quotationId", qid);
        long t0 = System.nanoTime();
        QuotationIdContext.set(qid);
        try {
            for (QuotationLineItem li : lines) {
                QuotationLineItem managed = QuotationLineItem.findById(li.id);
                if (managed == null) continue;
                // union=null + prefetch=null → 纯逐行路径(当前慢路径)
                cardSnapshotService.buildCostingCardValues(
                        managed, q.costingCardTemplateId, q.customerId, q.id, null, null);
            }
        } finally {
            QuotationIdContext.clear();
        }
        return (System.nanoTime() - t0) / 1_000_000;
    }
}
