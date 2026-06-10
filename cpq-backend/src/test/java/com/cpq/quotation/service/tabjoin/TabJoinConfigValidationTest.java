package com.cpq.quotation.service.tabjoin;

import com.cpq.common.exception.BusinessException;
import com.cpq.quotation.service.ExcelViewService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TabJoinConfigValidationTest {
    private List<Map<String,Object>> parse(String j) throws Exception {
        return new ObjectMapper().readValue(j, new TypeReference<>(){});
    }
    @Test void undeclared_alias_rejected() throws Exception {
        var cols = parse("""
          [{"col_key":"A","source_type":"TAB_JOIN_FORMULA","expression":"[未知.金额]","tabs":[]}]""");
        var ex = assertThrows(BusinessException.class, () -> ExcelViewService.validateTabJoinConfig(cols));
        assertTrue(ex.getMessage().contains("未知"));
    }
    @Test void detail_cross_rowkey_class_rejected() throws Exception {
        var cols = parse("""
          [{"col_key":"A","source_type":"TAB_JOIN_FORMULA","expression":"[投料.金额]+[回料.回料金额]",
            "tabs":[{"alias":"投料","tabKey":"x:0","rowKeyFields":["物料编码"]},
                    {"alias":"回料","tabKey":"y:1","rowKeyFields":["物料编码","工序"]}]}]""");
        assertThrows(BusinessException.class, () -> ExcelViewService.validateTabJoinConfig(cols));
    }
    @Test void empty_expression_rejected() throws Exception {
        var cols = parse("""
          [{"col_key":"A","source_type":"TAB_JOIN_FORMULA","expression":"","tabs":[]}]""");
        assertThrows(BusinessException.class, () -> ExcelViewService.validateTabJoinConfig(cols));
    }
    @Test void valid_passes() throws Exception {
        var cols = parse("""
          [{"col_key":"A","source_type":"TAB_JOIN_FORMULA","expression":"[投料.金额]+[回料(总计)]",
            "tabs":[{"alias":"投料","tabKey":"x:0","rowKeyFields":["物料编码"]},
                    {"alias":"回料","tabKey":"y:1","rowKeyFields":["物料编码","工序"]}]}]""");
        assertDoesNotThrow(() -> ExcelViewService.validateTabJoinConfig(cols));
    }
}
