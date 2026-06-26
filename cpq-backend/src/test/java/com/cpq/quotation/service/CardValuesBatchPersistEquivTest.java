package com.cpq.quotation.service;

import com.cpq.component.dto.ExpandDriverResponse;
import com.cpq.quotation.entity.Quotation;
import com.cpq.quotation.entity.QuotationLineItem;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FIX 2 等价护栏(2026-06-26):证明「卡片值集合化落库」`snapshotNewLinesCardValues`(两遍 build-then-assign,
 * 单事务)赋给每行的 quoteCardValues/costingCardValues 与「逐行路径」直接调 buildCardValues/buildCostingCardValues
 * 的产出<b>逐位相同</b>。二者本就调同一 build 方法、同一输入,故等价由构造保证;本测试钉死之。
 *
 * <p>{@code @TestTransaction} 包裹 → 测试结束<b>回滚</b>,集合化方法的赋值不落库,基准单不被污染。
 */
@QuarkusTest
class CardValuesBatchPersistEquivTest {

    @Inject CardSnapshotService svc;

    private static final UUID ROCKWELL_QID = UUID.fromString("8f0c37a4-8186-4f5e-a9ca-358bd2d9662d");

    @Test
    @TestTransaction
    void batchAssignEqualsPerLineBuild() {
        Quotation q = Quotation.findById(ROCKWELL_QID);
        Assumptions.assumeTrue(q != null && q.customerTemplateId != null, "基准单缺失,跳过");
        List<QuotationLineItem> lines = QuotationLineItem.list("quotationId", ROCKWELL_QID);
        Assumptions.assumeTrue(!lines.isEmpty(), "无行,跳过");

        List<UUID> lineIds = lines.stream().map(li -> li.id).collect(Collectors.toList());
        List<UUID> allLineIds = new ArrayList<>(lineIds);

        // setup(与生产同款)
        Map<UUID, Map<String, ExpandDriverResponse>> union = svc.precomputeCostingDriverUnion(ROCKWELL_QID);
        CardSnapshotService.CardValuesPrefetch prefetch = svc.precomputeCardValuesPrefetch(ROCKWELL_QID, allLineIds);

        // ── 参照:逐行直接 build(不落库)──
        Map<UUID, String> refQuote = new HashMap<>();
        Map<UUID, String> refCosting = new HashMap<>();
        com.cpq.formula.dataloader.QuotationIdContext.set(ROCKWELL_QID);
        try {
            for (QuotationLineItem li : lines) {
                refQuote.put(li.id, svc.buildCardValues(li, q.customerTemplateId, prefetch));
                if (q.costingCardTemplateId != null)
                    refCosting.put(li.id, svc.buildCostingCardValues(
                        li, q.costingCardTemplateId, q.customerId, q.id, union, prefetch));
            }
        } finally {
            com.cpq.formula.dataloader.QuotationIdContext.clear();
        }

        // ── 集合化:赋托管实体(同 tx,回滚不落库)──
        svc.snapshotNewLinesCardValues(ROCKWELL_QID, lineIds, union, prefetch);

        // ── 对账:每行 batch 赋的值 == 逐行 build 的值(逐位)──
        int checked = 0;
        for (QuotationLineItem li : lines) {
            QuotationLineItem managed = QuotationLineItem.findById(li.id);
            assertEquals(refQuote.get(li.id), managed.quoteCardValues,
                    "line=" + li.id + " quoteCardValues 集合化 vs 逐行 build 应逐位相同");
            if (q.costingCardTemplateId != null)
                assertEquals(refCosting.get(li.id), managed.costingCardValues,
                        "line=" + li.id + " costingCardValues 集合化 vs 逐行 build 应逐位相同");
            checked++;
        }
        assertTrue(checked > 0, "应至少校验一行");
        System.out.printf("[cardvalues-batch-equiv] quotation=%s 校验 %d 行,集合化赋值与逐行 build 逐位等价 ✅%n",
                ROCKWELL_QID, checked);
    }
}
