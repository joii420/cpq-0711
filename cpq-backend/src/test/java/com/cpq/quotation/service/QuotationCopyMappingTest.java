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
}
