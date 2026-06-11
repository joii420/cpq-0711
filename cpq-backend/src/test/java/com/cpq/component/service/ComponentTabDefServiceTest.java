package com.cpq.component.service;

import org.junit.jupiter.api.Test;

import com.cpq.component.entity.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 纯 JUnit（非 @QuarkusTest）：验证 componentsToTabDefs 纯转换。
 * 同目录三个组件 → 每个产出一条 tabDef，alias=code，componentName=name，
 * selfId 那条标记 self=true，fields/rowKeyFields 透传成 detailFields/subtotalCols/rowKeyFields。
 */
class ComponentTabDefServiceTest {

    private Component comp(UUID id, String code, String name, String type,
                           String fieldsJson, String rowKeyFieldsJson) {
        Component c = new Component();
        c.id = id;
        c.code = code;
        c.name = name;
        c.componentType = type;
        c.fields = fieldsJson;
        c.rowKeyFields = rowKeyFieldsJson;
        return c;
    }

    @Test
    void componentsToTabDefs_mapsEachComponent_withAliasCodeAndSelfFlag() {
        UUID idA = UUID.randomUUID();
        UUID idB = UUID.randomUUID();
        UUID idC = UUID.randomUUID();

        Component a = comp(idA, "FEED", "投料", "NORMAL",
            "[{\"name\":\"子件\"},{\"name\":\"小计\",\"is_subtotal\":true}]",
            "[\"子件\"]");
        Component b = comp(idB, "RETURN", "回料", "NORMAL",
            "[{\"name\":\"重量\"}]",
            "[\"重量\"]");
        Component c = comp(idC, "EXCEL_TAB", "加工", "EXCEL",
            "[]", null);

        List<Map<String, Object>> defs =
            ComponentTabDefService.componentsToTabDefs(List.of(a, b, c), idB);

        assertEquals(3, defs.size(), "每个同目录组件产出一条");

        Map<String, Object> defA = defs.get(0);
        assertEquals("FEED", defA.get("alias"), "alias = code");
        assertEquals("投料", defA.get("componentName"), "componentName = name");
        assertEquals("NORMAL", defA.get("componentType"));
        assertEquals(idA.toString(), defA.get("componentId"));
        assertEquals(List.of("子件"), defA.get("rowKeyFields"));
        assertEquals(List.of("子件", "小计"), defA.get("detailFields"));
        assertEquals(List.of("小计"), defA.get("subtotalCols"));
        assertNotEquals(Boolean.TRUE, defA.get("self"), "非请求组件不标 self");

        Map<String, Object> defB = defs.get(1);
        assertEquals("RETURN", defB.get("alias"));
        assertEquals(Boolean.TRUE, defB.get("self"), "selfId 那条标记 self=true");

        Map<String, Object> defC = defs.get(2);
        assertEquals("EXCEL_TAB", defC.get("alias"));
        assertEquals("EXCEL", defC.get("componentType"));
        assertEquals(List.of(), defC.get("rowKeyFields"), "rowKeyFields 为 null → 空列表");
        assertEquals(List.of(), defC.get("detailFields"), "fields=[] → 空 detailFields");
        assertEquals(List.of(), defC.get("subtotalCols"));

        // tabKey 非空且唯一
        assertNotNull(defA.get("tabKey"));
        assertNotEquals(defA.get("tabKey"), defB.get("tabKey"));
    }

    @Test
    void componentsToTabDefs_emptyList_returnsEmpty() {
        assertTrue(ComponentTabDefService.componentsToTabDefs(List.of(), UUID.randomUUID()).isEmpty());
    }

    /**
     * 需求3：INPUT_TEXT 文本字段从 detailFields 过滤掉（不可数值聚合，不应作明细令牌被公式引用），
     * 其余字段类型保留；detailFields 仍是 String[]（仅元素变少）。
     * 同时验证：INPUT_TEXT 作行键时仍出现在 rowKeyFields（走独立来源，不受明细过滤影响）。
     */
    @Test
    void componentsToTabDefs_filtersInputTextFromDetailFields_keepsRowKeyBadge() {
        UUID id = UUID.randomUUID();
        Component a = comp(id, "FEED", "投料", "NORMAL",
            "[{\"name\":\"子件号\",\"field_type\":\"INPUT_TEXT\"},"
            + "{\"name\":\"重量\",\"field_type\":\"INPUT_NUMBER\"},"
            + "{\"name\":\"备注\",\"field_type\":\"INPUT_TEXT\"},"
            + "{\"name\":\"小计\",\"field_type\":\"FORMULA\",\"is_subtotal\":true}]",
            "[\"子件号\"]");

        List<Map<String, Object>> defs =
            ComponentTabDefService.componentsToTabDefs(List.of(a), id);

        Map<String, Object> def = defs.get(0);
        // 文本字段「子件号」「备注」被过滤；数值/公式字段保留
        assertEquals(List.of("重量", "小计"), def.get("detailFields"),
            "INPUT_TEXT 字段从 detailFields 过滤，其余保留");
        assertEquals(List.of("小计"), def.get("subtotalCols"));
        // INPUT_TEXT 行键字段仍在徽标来源（独立于 detailFields）
        assertEquals(List.of("子件号"), def.get("rowKeyFields"),
            "INPUT_TEXT 作行键不受明细过滤影响");
    }
}
