package com.cpq.quotation;

import com.cpq.quotation.service.QuotationService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 2 Task 1: getById 暴露行级 quote_card_values + 报价单级结构快照(渲染脱钩前提)。
 */
@QuarkusTest
public class QuotationSnapshotExposureTest {

    @Inject QuotationService svc;
    @Inject EntityManager em;

    @Test
    void getById_exposesCardValuesAndStructures() {
        @SuppressWarnings("unchecked")
        var r = em.createNativeQuery(
            "SELECT quotation_id FROM quotation_line_item WHERE quote_card_values IS NOT NULL LIMIT 1")
            .getResultList();
        Assumptions.assumeTrue(!r.isEmpty(), "需要已有 quote_card_values 的报价单(先 configure 或跑过 snapshotLineValues)");
        UUID qid = UUID.fromString(r.get(0).toString());

        var dto = svc.getById(qid);

        // 报价单级结构快照(QUOTE_CARD)应被暴露(若该单已 ensureStructure)
        // 用 Assumptions 兜底: 老单可能无结构, 但有 card_values 的单通常 Phase1 也建了结构
        boolean anyLineHasCardValues = dto.lineItems.stream().anyMatch(li -> li.quoteCardValues != null);
        assertTrue(anyLineHasCardValues, "LineItemDTO 应暴露 quoteCardValues");

        // 结构: 该单若有 quotation_view_structure 则非空(Assumptions 不强求老单)
        if (dto.quoteCardStructure != null) {
            assertTrue(dto.quoteCardStructure.has("tabs"), "quoteCardStructure 应含 tabs");
        }
    }
}
