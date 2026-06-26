package com.cpq.quotation.service;

import com.cpq.quotation.entity.Quotation;
import com.cpq.quotation.entity.QuotationLineComponentData;
import com.cpq.quotation.entity.QuotationLineItem;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P3 lazy-excel 等价护栏(2026-06-26)。证明「懒算 Excel 值」与「首存就算」<b>逐位等价</b>:
 * 二者都调同一 {@link CardSnapshotService#buildExcelValues}(同 cardValues 输入),唯一差别是上下文搭建。
 * 本测试证 buildExcelValues 的输出<b>与 ExcelCompDataContext 预取上下文无关</b>(命中预取 vs 逐行查回落
 * 结果逐位相同)→ ensureExcelValues 只要传对 cardValues,产出就 == 同步路径,与是否预取无关。只读,不写库。
 */
@QuarkusTest
class LazyExcelValuesEquivTest {

    @Inject CardSnapshotService cardSnapshotService;

    private static final UUID ROCKWELL_QID = UUID.fromString("8f0c37a4-8186-4f5e-a9ca-358bd2d9662d");

    @Test
    @Transactional
    void lazyExcelEqualsSyncExcel_contextIndependent() {
        Quotation q = Quotation.findById(ROCKWELL_QID);
        Assumptions.assumeTrue(q != null && q.costingCardTemplateId != null, "基准单缺失,跳过");
        List<QuotationLineItem> lines = QuotationLineItem.list("quotationId", ROCKWELL_QID);
        Assumptions.assumeTrue(!lines.isEmpty(), "无行,跳过");

        List<UUID> lineIds = lines.stream().map(li -> li.id).collect(Collectors.toList());
        Map<UUID, List<QuotationLineComponentData>> cdByLine = QuotationLineComponentData
                .<QuotationLineComponentData>list("lineItemId IN ?1 ORDER BY lineItemId, sortOrder, id", lineIds)
                .stream().collect(Collectors.groupingBy(cd -> cd.lineItemId));

        com.cpq.formula.dataloader.QuotationIdContext.set(ROCKWELL_QID);
        int checked = 0;
        try {
            for (QuotationLineItem li : lines) {
                QuotationLineItem managed = QuotationLineItem.findById(li.id);
                if (managed == null) continue;

                // (A) 懒算路径上下文:整单 compData 预取命中
                com.cpq.formula.dataloader.ExcelCompDataContext.set(cdByLine);
                String quoteA = cardSnapshotService.buildExcelValues(
                        managed, q.customerTemplateId, q.customerId, managed.quoteCardValues);
                String costingA = cardSnapshotService.buildExcelValues(
                        managed, q.costingCardTemplateId, q.customerId, managed.costingCardValues, true);

                // (B) 无预取上下文:buildRowData 逐行查回落(同步老路径之一)
                com.cpq.formula.dataloader.ExcelCompDataContext.clear();
                String quoteB = cardSnapshotService.buildExcelValues(
                        managed, q.customerTemplateId, q.customerId, managed.quoteCardValues);
                String costingB = cardSnapshotService.buildExcelValues(
                        managed, q.costingCardTemplateId, q.customerId, managed.costingCardValues, true);

                assertEquals(quoteB, quoteA, "line=" + li.id + " 报价 Excel 值应与预取上下文无关(逐位相同)");
                assertEquals(costingB, costingA, "line=" + li.id + " 核价 Excel 值应与预取上下文无关(逐位相同)");
                checked++;
            }
        } finally {
            com.cpq.formula.dataloader.ExcelCompDataContext.clear();
            com.cpq.formula.dataloader.QuotationIdContext.clear();
        }
        assertTrue(checked > 0, "应至少校验一行");
        System.out.printf("[lazy-excel-equiv] quotation=%s 校验 %d 行,Excel 值预取/逐行两路逐位等价 ✅%n",
                ROCKWELL_QID, checked);
    }
}
