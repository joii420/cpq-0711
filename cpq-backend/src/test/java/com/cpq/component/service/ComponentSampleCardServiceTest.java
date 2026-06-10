package com.cpq.component.service;

import com.cpq.component.service.ComponentSampleCardService.CardRowProjection;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 纯 JUnit（非 @QuarkusTest）：验证 projectionsToSampleCards 纯转换。
 * 反查/求值是 DB/evaluator 绑定的，无法在此纯单测（依赖 Panache + ExcelViewService），
 * 仅 DTO 映射这层是纯函数——这里覆盖它（含 limit 截断 / null 行过滤 / null 字段透传）。
 */
class ComponentSampleCardServiceTest {

    @Test
    void projectionsToSampleCards_mapsFieldsAndStringifiesUuids() {
        UUID li = UUID.randomUUID();
        UUID q = UUID.randomUUID();
        CardRowProjection p = new CardRowProjection(li, q, "Q-2026-001", "产品A");

        List<Map<String, Object>> out =
            ComponentSampleCardService.projectionsToSampleCards(List.of(p), 50);

        assertEquals(1, out.size());
        Map<String, Object> e = out.get(0);
        assertEquals(li.toString(), e.get("lineItemId"));
        assertEquals(q.toString(), e.get("quotationId"));
        assertEquals("Q-2026-001", e.get("quotationNo"));
        assertEquals("产品A", e.get("cardName"));
    }

    @Test
    void projectionsToSampleCards_respectsLimit() {
        List<CardRowProjection> ps = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            ps.add(new CardRowProjection(UUID.randomUUID(), UUID.randomUUID(), "Q" + i, "C" + i));
        }
        List<Map<String, Object>> out =
            ComponentSampleCardService.projectionsToSampleCards(ps, 50);
        assertEquals(50, out.size(), "截断到 limit");
    }

    @Test
    void projectionsToSampleCards_nullList_returnsEmpty() {
        assertTrue(ComponentSampleCardService.projectionsToSampleCards(null, 50).isEmpty());
    }

    @Test
    void projectionsToSampleCards_skipsNullRowsAndNullLineItemId() {
        CardRowProjection good = new CardRowProjection(UUID.randomUUID(), null, null, null);
        CardRowProjection noLi = new CardRowProjection(null, UUID.randomUUID(), "Q", "C");
        List<CardRowProjection> ps = new ArrayList<>();
        ps.add(null);
        ps.add(noLi);
        ps.add(good);

        List<Map<String, Object>> out =
            ComponentSampleCardService.projectionsToSampleCards(ps, 50);

        assertEquals(1, out.size(), "null 行 + 无 lineItemId 行被跳过");
        Map<String, Object> e = out.get(0);
        assertNotNull(e.get("lineItemId"));
        assertNull(e.get("quotationId"), "null quotationId 透传为 null");
        assertNull(e.get("quotationNo"));
        assertNull(e.get("cardName"));
    }
}
