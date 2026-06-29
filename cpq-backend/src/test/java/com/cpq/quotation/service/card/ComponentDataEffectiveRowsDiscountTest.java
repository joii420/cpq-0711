package com.cpq.quotation.service.card;

import com.cpq.quotation.entity.QuotationLineComponentData;
import com.cpq.quotation.service.FormulaCalculator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.*;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ComponentDataEffectiveRowsDiscountTest {

    private final FormulaCalculator fc = new FormulaCalculator();
    private final ObjectMapper M = new ObjectMapper();

    /**
     * 产品小计公式: [组装加工费.费用] + [其他费用.费用]
     * 两页签费用列和 8 与 2 → S0=10
     */
    private Map<UUID, ComponentDataEffectiveRows.Meta> metas(UUID asm, UUID oth, UUID sub) throws Exception {
        var formulas = M.readTree("[{\"expression\":[" +
            "{\"type\":\"component_subtotal\",\"value\":\"费用\",\"component_code\":\"ASM\",\"tab_name\":\"组装加工费\"}," +
            "{\"type\":\"operator\",\"value\":\"+\"}," +
            "{\"type\":\"component_subtotal\",\"value\":\"费用\",\"component_code\":\"OTH\",\"tab_name\":\"其他费用\"}]}]");
        Map<UUID, ComponentDataEffectiveRows.Meta> m = new HashMap<>();
        m.put(asm, new ComponentDataEffectiveRows.Meta("ASM", "组装加工费", "NORMAL", null));
        m.put(oth, new ComponentDataEffectiveRows.Meta("OTH", "其他费用", "NORMAL", null));
        m.put(sub, new ComponentDataEffectiveRows.Meta("SUB", "报价小计", "SUBTOTAL", formulas));
        return m;
    }

    private QuotationLineComponentData cd(UUID id, int sort, String rowData) {
        QuotationLineComponentData c = new QuotationLineComponentData();
        c.componentId = id;
        c.sortOrder = sort;
        c.rowData = rowData;
        c.subtotal = BigDecimal.ZERO;
        return c;
    }

    @Test
    void discountOnAsmComponent_recomputesSubtotal() throws Exception {
        UUID asm = UUID.randomUUID(), oth = UUID.randomUUID(), sub = UUID.randomUUID();
        var list = List.of(
            cd(asm, 0, "[{\"费用\":8}]"),
            cd(oth, 1, "[{\"费用\":2}]"),
            cd(sub, 2, "[]"));
        // 折 ASM 20% → 8*0.8 + 2 = 8.4
        BigDecimal s1 = ComponentDataEffectiveRows.subtotalWithDiscount(
            list, metas(asm, oth, sub), sub, fc, "ASM", 0.8);
        assertEquals(0, new BigDecimal("8.4000").compareTo(s1),
            "折扣后小计应为 8.4000，实际=" + s1);
        // 无折扣 → 10
        BigDecimal s0 = ComponentDataEffectiveRows.subtotalWithDiscount(
            list, metas(asm, oth, sub), sub, fc, null, 1.0);
        assertEquals(0, new BigDecimal("10.0000").compareTo(s0),
            "无折扣小计应为 10.0000，实际=" + s0);
    }

    /**
     * 按列折扣：来料(LL) 同组件两列 材料成本=5 / 材料损耗成本=3，组装加工费.费用=8，其他费用.费用=2 → S0=18。
     * discountCode='LL#材料成本' 仅折该列：5*0.8=4 → 4+3+8+2=17（材料损耗成本不受影响）。
     */
    @Test
    void discountOnSingleColumn_onlyScalesThatColumn() throws Exception {
        UUID ll = UUID.randomUUID(), asm = UUID.randomUUID(), oth = UUID.randomUUID(), sub = UUID.randomUUID();
        var formulas = M.readTree("[{\"expression\":[" +
            "{\"type\":\"component_subtotal\",\"value\":\"材料成本\",\"component_code\":\"LL\",\"tab_name\":\"来料\"}," +
            "{\"type\":\"operator\",\"value\":\"+\"}," +
            "{\"type\":\"component_subtotal\",\"value\":\"材料损耗成本\",\"component_code\":\"LL\",\"tab_name\":\"来料\"}," +
            "{\"type\":\"operator\",\"value\":\"+\"}," +
            "{\"type\":\"component_subtotal\",\"value\":\"费用\",\"component_code\":\"ASM\",\"tab_name\":\"组装加工费\"}," +
            "{\"type\":\"operator\",\"value\":\"+\"}," +
            "{\"type\":\"component_subtotal\",\"value\":\"费用\",\"component_code\":\"OTH\",\"tab_name\":\"其他费用\"}]}]");
        Map<UUID, ComponentDataEffectiveRows.Meta> m = new HashMap<>();
        m.put(ll, new ComponentDataEffectiveRows.Meta("LL", "来料", "NORMAL", null));
        m.put(asm, new ComponentDataEffectiveRows.Meta("ASM", "组装加工费", "NORMAL", null));
        m.put(oth, new ComponentDataEffectiveRows.Meta("OTH", "其他费用", "NORMAL", null));
        m.put(sub, new ComponentDataEffectiveRows.Meta("SUB", "报价小计", "SUBTOTAL", formulas));
        var list = List.of(
            cd(ll, 0, "[{\"材料成本\":5,\"材料损耗成本\":3}]"),
            cd(asm, 1, "[{\"费用\":8}]"),
            cd(oth, 2, "[{\"费用\":2}]"),
            cd(sub, 3, "[]"));

        BigDecimal s0 = ComponentDataEffectiveRows.subtotalWithDiscount(list, m, sub, fc, null, 1.0);
        assertEquals(0, new BigDecimal("18.0000").compareTo(s0), "无折扣应为 18，实际=" + s0);

        // 仅折 LL#材料成本 20% → 17
        BigDecimal s1 = ComponentDataEffectiveRows.subtotalWithDiscount(list, m, sub, fc, "LL#材料成本", 0.8);
        assertEquals(0, new BigDecimal("17.0000").compareTo(s1), "折 LL#材料成本 后应为 17，实际=" + s1);

        // 折 LL#材料损耗成本 20% → 3*0.8=2.4 → 5+2.4+8+2=17.4
        BigDecimal s2 = ComponentDataEffectiveRows.subtotalWithDiscount(list, m, sub, fc, "LL#材料损耗成本", 0.8);
        assertEquals(0, new BigDecimal("17.4000").compareTo(s2), "折 LL#材料损耗成本 后应为 17.4，实际=" + s2);
    }
}
