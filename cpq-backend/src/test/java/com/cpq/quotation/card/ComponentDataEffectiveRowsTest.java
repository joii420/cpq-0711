package com.cpq.quotation.card;

import com.cpq.quotation.entity.QuotationLineComponentData;
import com.cpq.quotation.service.FormulaCalculator;
import com.cpq.quotation.service.card.CardEffectiveRows;
import com.cpq.quotation.service.card.ComponentDataEffectiveRows;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ComponentDataEffectiveRowsTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static QuotationLineComponentData cd(String id, int sort, String rowJson) {
        QuotationLineComponentData d = new QuotationLineComponentData();
        d.componentId = UUID.fromString(id);
        d.sortOrder = sort;
        d.rowData = rowJson;
        d.subtotal = BigDecimal.ZERO;
        return d;
    }

    private static com.fasterxml.jackson.databind.JsonNode formulas(String json) {
        try { return M.readTree(json); } catch (Exception e) { throw new RuntimeException(e); }
    }

    /** 明细页签：列求和进 subtotalByColumn，且双键（裸 cid + cid:sort）都命中同一 TabRows。 */
    @Test
    void detailColumnSumsAndDualKey() {
        String LL = "11111111-1111-1111-1111-111111111111";
        var cdList = List.of(cd(LL, 1,
            "[{\"材料成本\":0.0433,\"材料损耗成本\":0},{\"材料成本\":0.0341,\"材料损耗成本\":0}]"));
        Map<UUID, ComponentDataEffectiveRows.Meta> meta = Map.of(
            UUID.fromString(LL), new ComponentDataEffectiveRows.Meta("COMP-0028__imp1", "来料", "DETAIL", null));

        Map<String, CardEffectiveRows.TabRows> out =
            ComponentDataEffectiveRows.compute(cdList, meta, new FormulaCalculator());

        CardEffectiveRows.TabRows byBare = out.get(LL);
        CardEffectiveRows.TabRows bySort = out.get(LL + ":1");
        assertNotNull(byBare, "裸 componentId 必须命中");
        assertSame(byBare, bySort, "双键指向同一 TabRows");
        assertEquals(2, byBare.rows.size());
        assertEquals(0, new BigDecimal("0.0774").compareTo(byBare.subtotalByColumn.get("材料成本")));
        assertEquals(0, BigDecimal.ZERO.compareTo(byBare.subtotalByColumn.get("材料损耗成本")));
    }

    /** SUBTOTAL 组件总计 = 其 component_subtotal 公式求值（跨 tab，code#col 键，含同名列 费用 不串值）。 */
    @Test
    void subtotalComponentFormulaEvaluated() {
        String LL = "11111111-1111-1111-1111-111111111111"; // 来料 COMP-0028
        String ZZ = "22222222-2222-2222-2222-222222222222"; // 组装加工费 COMP-0038
        String QT = "33333333-3333-3333-3333-333333333333"; // 其他费用 COMP-0031
        String ST = "44444444-4444-4444-4444-444444444444"; // 报价小计 COMP-0034 SUBTOTAL

        var cdList = List.of(
            cd(LL, 1, "[{\"材料成本\":0.0433,\"材料损耗成本\":0},{\"材料成本\":0.0341,\"材料损耗成本\":0}]"),
            cd(ZZ, 2, "[{\"费用\":0.08}]"),
            cd(QT, 3, "[{\"费用\":0.067074}]"),
            cd(ST, 8, "[]"));

        String subtotalFormulas = "[{\"name\":\"总成本\",\"expression\":["
            + "{\"type\":\"component_subtotal\",\"value\":\"材料成本\",\"tab_name\":\"材料成本\",\"component_code\":\"COMP-0028__imp1\"},"
            + "{\"type\":\"operator\",\"value\":\"+\"},"
            + "{\"type\":\"component_subtotal\",\"value\":\"材料损耗成本\",\"tab_name\":\"材料损耗成本\",\"component_code\":\"COMP-0028__imp1\"},"
            + "{\"type\":\"operator\",\"value\":\"+\"},"
            + "{\"type\":\"component_subtotal\",\"value\":\"费用\",\"tab_name\":\"费用\",\"component_code\":\"COMP-0038__imp1\"},"
            + "{\"type\":\"operator\",\"value\":\"+\"},"
            + "{\"type\":\"component_subtotal\",\"value\":\"费用\",\"tab_name\":\"费用\",\"component_code\":\"COMP-0031__imp1\"}"
            + "]}]";

        Map<UUID, ComponentDataEffectiveRows.Meta> meta = new HashMap<>();
        meta.put(UUID.fromString(LL), new ComponentDataEffectiveRows.Meta("COMP-0028__imp1", "来料", "DETAIL", null));
        meta.put(UUID.fromString(ZZ), new ComponentDataEffectiveRows.Meta("COMP-0038__imp1", "组装加工费", "DETAIL", null));
        meta.put(UUID.fromString(QT), new ComponentDataEffectiveRows.Meta("COMP-0031__imp1", "其他费用", "DETAIL", null));
        meta.put(UUID.fromString(ST), new ComponentDataEffectiveRows.Meta("COMP-0034__imp1", "报价小计", "SUBTOTAL", formulas(subtotalFormulas)));

        Map<String, CardEffectiveRows.TabRows> out =
            ComponentDataEffectiveRows.compute(cdList, meta, new FormulaCalculator());

        BigDecimal productSubtotal = out.get(ST).subtotal;
        assertNotNull(productSubtotal);
        // 0.0774 + 0 + 0.08 + 0.067074 = 0.224474（FormulaCalculator 四舍五入到 4 位 → 0.2245）
        assertEquals(0, new BigDecimal("0.2245").compareTo(productSubtotal),
            "实际=" + productSubtotal);
    }

    /**
     * 新配置报价单：SUBTOTAL 组件无 cd 记录（ConfigureSnapshotService 不为其建行）→
     * 通过 extraSubtotalMetas 读时合成其 TabRows，公式对 NORMAL 页签列小计求值，
     * 以裸 componentId 键命中 → [报价小计(总计)] 解析为正确总计（修复产品小计=0 残留 bug）。
     */
    @Test
    void synthesizedSubtotalWithoutCdRecord() {
        String LL = "11111111-1111-1111-1111-111111111111"; // 来料 COMP-0028 (NORMAL, 有 cd)
        String ZZ = "22222222-2222-2222-2222-222222222222"; // 组装加工费 COMP-0038 (NORMAL, 有 cd)
        String ST = "44444444-4444-4444-4444-444444444444"; // 报价小计 COMP-0034 SUBTOTAL (无 cd)

        // cdList 只含 NORMAL 页签；列小计：材料成本=0.08，费用=0.06
        var cdList = List.of(
            cd(LL, 1, "[{\"材料成本\":0.05},{\"材料成本\":0.03}]"),
            cd(ZZ, 2, "[{\"费用\":0.04},{\"费用\":0.02}]"));

        Map<UUID, ComponentDataEffectiveRows.Meta> meta = new HashMap<>();
        meta.put(UUID.fromString(LL), new ComponentDataEffectiveRows.Meta("COMP-0028__imp1", "来料", "DETAIL", null));
        meta.put(UUID.fromString(ZZ), new ComponentDataEffectiveRows.Meta("COMP-0038__imp1", "组装加工费", "DETAIL", null));

        String subtotalFormulas = "[{\"name\":\"产品小计\",\"expression\":["
            + "{\"type\":\"component_subtotal\",\"value\":\"材料成本\",\"tab_name\":\"材料成本\",\"component_code\":\"COMP-0028__imp1\"},"
            + "{\"type\":\"operator\",\"value\":\"+\"},"
            + "{\"type\":\"component_subtotal\",\"value\":\"费用\",\"tab_name\":\"费用\",\"component_code\":\"COMP-0038__imp1\"}"
            + "]}]";

        // SUBTOTAL 组件 meta 走 extraSubtotalMetas（不在 cdList，也不在 metaById）
        Map<UUID, ComponentDataEffectiveRows.Meta> extra = new HashMap<>();
        extra.put(UUID.fromString(ST), new ComponentDataEffectiveRows.Meta(
            "COMP-0034__imp1", "报价小计", "SUBTOTAL", formulas(subtotalFormulas)));

        Map<String, CardEffectiveRows.TabRows> out =
            ComponentDataEffectiveRows.compute(cdList, meta, extra, new FormulaCalculator());

        CardEffectiveRows.TabRows st = out.get(ST);
        assertNotNull(st, "合成的 SUBTOTAL 必须以裸 componentId 命中（[报价小计(总计)] 解析依赖此键）");
        assertEquals(0, st.rows.size(), "AP-51: 纯公式总计，无明细行");
        // 0.08 + 0.06 = 0.14
        assertEquals(0, new BigDecimal("0.14").compareTo(st.subtotal),
            "实际=" + st.subtotal);
    }

    /**
     * BL-0017：`[页签(总计)]`（component_subtotal token value=__amount_total__）只取金额列(is_amount)之和，
     * 不含非金额小计列；裸键仍 = Σ所有小计列（不变）。
     */
    @Test
    void bl0017AmountTotalSentinelKeyOnlySumsAmountColumns() {
        String LL = "11111111-1111-1111-1111-111111111111"; // 来料 (NORMAL)：材料成本(金额) + 汇率(非金额小计列)
        String ST = "44444444-4444-4444-4444-444444444444"; // 报价小计 SUBTOTAL

        // 来料两小计列：材料成本=10（is_amount），汇率=7（is_subtotal 但非金额）
        var cdList = List.of(cd(LL, 1, "[{\"材料成本\":10,\"汇率\":7}]"));

        Map<UUID, ComponentDataEffectiveRows.Meta> meta = new HashMap<>();
        // amountCols 只含 材料成本 → 哨兵键 code#__amount_total__ = 10（不含 汇率）
        meta.put(UUID.fromString(LL), new ComponentDataEffectiveRows.Meta(
            "COMP-0028__imp1", "来料", "DETAIL", null, Set.of("材料成本")));

        // SUBTOTAL 公式 = [来料(总计)]：token value=__amount_total__（BL-0017 解析器产出）
        String subtotalFormulas = "[{\"name\":\"产品小计\",\"expression\":["
            + "{\"type\":\"component_subtotal\",\"value\":\"__amount_total__\","
            + "\"tab_name\":\"__amount_total__\",\"component_code\":\"COMP-0028__imp1\",\"is_tab_total\":true}"
            + "]}]";
        Map<UUID, ComponentDataEffectiveRows.Meta> extra = new HashMap<>();
        extra.put(UUID.fromString(ST), new ComponentDataEffectiveRows.Meta(
            "COMP-0034__imp1", "报价小计", "SUBTOTAL", formulas(subtotalFormulas)));

        Map<String, CardEffectiveRows.TabRows> out =
            ComponentDataEffectiveRows.compute(cdList, meta, extra, new FormulaCalculator());

        // [来料(总计)] = Σ金额列 = 材料成本 10（非 10+7=17）
        assertEquals(0, new BigDecimal("10").compareTo(out.get(ST).subtotal),
            "[来料(总计)] 应=金额列之和 10，非含非金额列的 17；实际=" + out.get(ST).subtotal);
    }

    /**
     * BL-0017 + 折扣重算：`subtotalWithDiscount`（LineDiscountService 路径）按列折扣缩放后，
     * `[页签(总计)]` 哨兵键 = 缩放后 Σ金额列（仅金额列参与，非金额小计列不受影响）。
     */
    @Test
    void bl0017AmountTotalSentinelUnderPerColumnDiscount() {
        String LL = "11111111-1111-1111-1111-111111111111"; // 来料：材料成本(金额)=10 + 汇率(非金额)=7
        String ST = "44444444-4444-4444-4444-444444444444"; // 报价小计 SUBTOTAL

        var cdList = List.of(
            cd(LL, 1, "[{\"材料成本\":10,\"汇率\":7}]"),
            cd(ST, 8, "[]"));

        // SUBTOTAL 公式 = [来料(总计)]：token value=__amount_total__
        String subtotalFormulas = "[{\"name\":\"产品小计\",\"expression\":["
            + "{\"type\":\"component_subtotal\",\"value\":\"__amount_total__\","
            + "\"tab_name\":\"__amount_total__\",\"component_code\":\"COMP-0028__imp1\",\"is_tab_total\":true}"
            + "]}]";

        Map<UUID, ComponentDataEffectiveRows.Meta> meta = new HashMap<>();
        meta.put(UUID.fromString(LL), new ComponentDataEffectiveRows.Meta(
            "COMP-0028__imp1", "来料", "DETAIL", null, Set.of("材料成本")));
        meta.put(UUID.fromString(ST), new ComponentDataEffectiveRows.Meta(
            "COMP-0034__imp1", "报价小计", "SUBTOTAL", formulas(subtotalFormulas)));

        FormulaCalculator fc = new FormulaCalculator();

        // 无折扣：[来料(总计)] = Σ金额列 = 10
        BigDecimal s0 = ComponentDataEffectiveRows.subtotalWithDiscount(
            cdList, meta, UUID.fromString(ST), fc, null, 1.0);
        assertEquals(0, new BigDecimal("10").compareTo(s0), "无折扣应=金额列 10，实际=" + s0);

        // 按列折扣：材料成本 ×0.5 → 哨兵键 = 5（非金额的 汇率 不参与，原本也不在金额总计里）
        BigDecimal sD = ComponentDataEffectiveRows.subtotalWithDiscount(
            cdList, meta, UUID.fromString(ST), fc, "COMP-0028__imp1#材料成本", 0.5);
        assertEquals(0, new BigDecimal("5").compareTo(sD),
            "材料成本×0.5 后 [来料(总计)] 应=5（缩放后 Σ金额列），实际=" + sD);
    }

    /** 脏/空数据兜底：null rowData、非数值列、缺 meta 都不抛异常。 */
    @Test
    void nullAndDirtyDataSafe() {
        String X = "55555555-5555-5555-5555-555555555555";
        var cdList = new ArrayList<QuotationLineComponentData>();
        cdList.add(cd(X, 0, null));
        cdList.add(cd(X.replace('5','6'), 1, "[{\"料件\":\"H65带\",\"项次\":1,\"加工费\":0.04}]"));
        Map<String, CardEffectiveRows.TabRows> out =
            ComponentDataEffectiveRows.compute(cdList, Map.of(), new FormulaCalculator());
        assertEquals(0, out.get(X).rows.size());
        assertFalse(out.get(X.replace('5','6')).subtotalByColumn.containsKey("料件"));
        assertEquals(0, new BigDecimal("0.04").compareTo(
            out.get(X.replace('5','6')).subtotalByColumn.get("加工费")));
    }
}
