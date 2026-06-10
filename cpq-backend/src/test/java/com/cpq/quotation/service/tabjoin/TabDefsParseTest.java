package com.cpq.quotation.service.tabjoin;

import com.cpq.quotation.service.ExcelViewService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 纯函数单测：ExcelViewService.parseTabDefs 解析 componentsSnapshot JSON → tabDefs List。
 * 不依赖 DB / Quarkus CDI，直接调 static 方法。
 */
class TabDefsParseTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private List<Map<String, Object>> parseSnapshot(String json) throws Exception {
        return MAPPER.readValue(json, new TypeReference<>() {});
    }

    @Test
    void basic_two_tabs_alias_tabKey_rowKeyFields() throws Exception {
        // componentsSnapshot: 2 个组件，一个有 tabName，另一个没有
        String snapshotJson = """
            [
              {
                "componentId": "aaaaaaaa-0000-0000-0000-000000000001",
                "componentName": "投料",
                "componentCode": "COMP-0001",
                "componentType": "NORMAL",
                "tabName": "投料页签",
                "sortOrder": 0,
                "fields": [
                  {"name": "物料编码", "field_type": "DATA_SOURCE", "is_subtotal": false},
                  {"name": "金额",     "field_type": "FORMULA",     "is_subtotal": true}
                ]
              },
              {
                "componentId": "bbbbbbbb-0000-0000-0000-000000000002",
                "componentName": "回料",
                "componentCode": "COMP-0002",
                "componentType": "NORMAL",
                "tabName": null,
                "sortOrder": 1,
                "fields": [
                  {"name": "回料编码", "field_type": "DATA_SOURCE", "is_subtotal": false},
                  {"name": "回料金额", "field_type": "FORMULA",     "is_subtotal": true}
                ]
              }
            ]
            """;

        Map<String, List<String>> rkfByCompId = Map.of(
            "aaaaaaaa-0000-0000-0000-000000000001", List.of("物料编码"),
            "bbbbbbbb-0000-0000-0000-000000000002", List.of("回料编码")
        );

        List<Map<String, Object>> defs = ExcelViewService.parseTabDefs(
            parseSnapshot(snapshotJson), rkfByCompId);

        assertEquals(2, defs.size());

        // 第一个组件：tabName 非空 → alias=tabName
        Map<String, Object> d0 = defs.get(0);
        assertEquals("投料页签", d0.get("alias"));
        assertEquals("aaaaaaaa-0000-0000-0000-000000000001:0", d0.get("tabKey"));
        assertEquals(List.of("物料编码"), d0.get("rowKeyFields"));
        assertEquals(List.of("物料编码", "金额"), d0.get("detailFields"));
        assertEquals(List.of("金额"), d0.get("subtotalCols"));
        assertEquals(0, d0.get("sortOrder"));

        // 第二个组件：tabName=null → alias=componentName
        Map<String, Object> d1 = defs.get(1);
        assertEquals("回料", d1.get("alias"));
        assertEquals("bbbbbbbb-0000-0000-0000-000000000002:1", d1.get("tabKey"));
        assertEquals(List.of("回料编码"), d1.get("rowKeyFields"));
        assertEquals(List.of("回料编码", "回料金额"), d1.get("detailFields"));
        assertEquals(List.of("回料金额"), d1.get("subtotalCols"));
    }

    @Test
    void subtotal_component_type_passes_through() throws Exception {
        String snapshotJson = """
            [
              {
                "componentId": "cccccccc-0000-0000-0000-000000000003",
                "componentName": "小计",
                "componentCode": "COMP-SUB",
                "componentType": "SUBTOTAL",
                "tabName": null,
                "sortOrder": 2,
                "fields": [
                  {"name": "合计", "field_type": "FORMULA", "is_subtotal": true}
                ]
              }
            ]
            """;
        Map<String, List<String>> rkfByCompId = Map.of(
            "cccccccc-0000-0000-0000-000000000003", List.of()
        );
        List<Map<String, Object>> defs = ExcelViewService.parseTabDefs(
            parseSnapshot(snapshotJson), rkfByCompId);

        assertEquals(1, defs.size());
        Map<String, Object> d = defs.get(0);
        assertEquals("SUBTOTAL", d.get("componentType"));
        assertEquals("小计", d.get("alias"));
        assertEquals(List.of(), d.get("rowKeyFields")); // 小计组件通常无 rowKeyFields
        assertEquals(List.of("合计"), d.get("subtotalCols"));
    }

    @Test
    void empty_snapshot_returns_empty_list() throws Exception {
        List<Map<String, Object>> defs = ExcelViewService.parseTabDefs(List.of(), Map.of());
        assertNotNull(defs);
        assertTrue(defs.isEmpty());
    }

    @Test
    void missing_rkf_entry_defaults_to_empty() throws Exception {
        String snapshotJson = """
            [
              {
                "componentId": "dddddddd-0000-0000-0000-000000000004",
                "componentName": "加工",
                "componentCode": "COMP-0004",
                "componentType": "NORMAL",
                "tabName": "加工",
                "sortOrder": 3,
                "fields": [
                  {"name": "工序", "field_type": "DATA_SOURCE", "is_subtotal": false}
                ]
              }
            ]
            """;
        // rkfByCompId 不含该 componentId → 应降级空列表
        List<Map<String, Object>> defs = ExcelViewService.parseTabDefs(
            parseSnapshot(snapshotJson), Map.of());

        assertEquals(1, defs.size());
        assertEquals(List.of(), defs.get(0).get("rowKeyFields"));
        assertEquals(List.of("工序"), defs.get(0).get("detailFields"));
    }
}
