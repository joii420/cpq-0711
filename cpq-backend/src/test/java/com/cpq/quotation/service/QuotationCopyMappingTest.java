package com.cpq.quotation.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class QuotationCopyMappingTest {
    private final ObjectMapper M = new ObjectMapper();

    @Test
    void migratesOnlyTargetInputFieldsByName() throws Exception {
        String src = "[{\"row_index\":0,\"材料管理费\":\"12\",\"外购件管理费\":\"33\",\"利润\":99,\"汇率\":7.12}]";
        Set<String> targetInputs = Set.of("材料管理费", "外购件管理费");
        String out = QuotationService.mapInputRowData(src, targetInputs, M);
        var rows = M.readTree(out);
        assertEquals(1, rows.size());
        assertEquals(0, rows.get(0).get("row_index").asInt());
        assertEquals("12", rows.get(0).get("材料管理费").asText());
        assertEquals("33", rows.get(0).get("外购件管理费").asText());
        assertFalse(rows.get(0).has("利润"), "FORMULA 字段不应迁移");
        assertFalse(rows.get(0).has("汇率"), "BASIC_DATA 字段不应迁移");
    }

    @Test
    void unmatchedTargetFieldLeftEmpty() throws Exception {
        String src = "[{\"row_index\":0,\"材料管理费\":\"12\"}]";
        Set<String> targetInputs = Set.of("材料管理费", "新字段");
        String out = QuotationService.mapInputRowData(src, targetInputs, M);
        var row = M.readTree(out).get(0);
        assertEquals("12", row.get("材料管理费").asText());
        assertFalse(row.has("新字段"), "源无值的目标字段留空(不写键)");
    }

    @Test
    void nullOrEmptySourceReturnsEmptyArray() throws Exception {
        assertEquals("[]", QuotationService.mapInputRowData(null, Set.of("x"), M));
        assertEquals("[]", QuotationService.mapInputRowData("[]", Set.of("x"), M));
    }

    @Test
    void parsesInputFieldNamesFromSnapshot() throws Exception {
        String snap = "[{\"componentId\":\"c1\",\"tabName\":\"产品\",\"fields\":["
            + "{\"name\":\"材料管理费\",\"field_type\":\"INPUT_NUMBER\"},"
            + "{\"name\":\"品名\",\"field_type\":\"INPUT_TEXT\"},"
            + "{\"name\":\"利润\",\"field_type\":\"FORMULA\"},"
            + "{\"name\":\"汇率\",\"field_type\":\"BASIC_DATA\"}]}]";
        var tabs = QuotationService.parseTemplateTabFields(snap, M);
        assertEquals(1, tabs.size());
        assertEquals("c1", tabs.get(0).componentId);
        assertEquals("产品", tabs.get(0).tabName);
        assertEquals(Set.of("材料管理费", "品名"), tabs.get(0).inputFieldNames);
    }
}
