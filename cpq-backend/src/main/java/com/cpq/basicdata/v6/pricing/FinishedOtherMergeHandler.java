package com.cpq.basicdata.v6.pricing;

import com.cpq.basicdata.v6.parser.ImportContext;
import com.cpq.basicdata.v6.parser.SheetImportResult;
import com.cpq.basicdata.v6.parser.SheetRow;
import com.cpq.basicdata.v6.versioning.VersionedV6Writer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * P19 成品其他比例费用 + P20 成品其他固定费用 → unit_price (PRICING/FINISHED_OTHER) 合并单版本组
 * 整批版本化（tesk-0709 Task 9）。
 *
 * <p>与 {@link IncomingOtherMergeHandler} 同构：P19/P20 是两个独立 Sheet，同 price_type=
 * FINISHED_OTHER，对同一销售料号（code）属于同一个版本组，必须合并成一组 {@code groups} 一次写入，
 * 否则两个 Sheet 各自调用 {@code writeVersionedGroups} 会互相当"旧组"重升版。
 *
 * <p>groupKey = {system_type:"PRICING", price_type:"FINISHED_OTHER", code(销售料号)}
 * （不含 cost_type ——动态"要素"值，比例/固定两类费用共享一个版本）。content = [cost_type, seq_no,
 * cost_ratio, pricing_price, currency, unit]；production_no 为描述列（写入不参与版本比对）。
 *
 * <p>组内去重键 = (cost_type, seq_no)：code/version_no 组内恒定，其余 uq_unit_price 维度
 * （customer_no/supplier_no/finished_material_no/operation_no/discount_order/item_seq/
 * effective_date）均未设置=NULL，避免整组升版时组内重复行相互撞键；同键取最后一行（末值覆盖）。
 */
@ApplicationScoped
public class FinishedOtherMergeHandler {

    @Inject VersionedV6Writer writer;

    private static final List<String> CONTENT = List.of(
        "cost_type", "seq_no", "cost_ratio", "pricing_price", "currency", "unit");
    private static final List<String> DESCRIPTOR = List.of("production_no");

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public SheetImportResult merge(List<SheetRow> ratioRows, List<SheetRow> fixedRows, ImportContext ctx) {
        SheetImportResult result = new SheetImportResult("成品其他比例费用+成品其他固定费用(合并)");

        // code(销售料号) -> dedupKey(cost_type|seq_no) -> content row（末值覆盖）
        Map<String, LinkedHashMap<String, Map<String, Object>>> byCode = new LinkedHashMap<>();

        for (SheetRow row : ratioRows) {
            result.totalRows++;
            String code = row.getStr("销售料号", "宏丰料号");
            String costType = row.getStr("要素名称");
            if (code == null || costType == null) {
                result.recordError(row.rowNo, "宏丰料号/要素名称", "必填项为空");
                continue;
            }
            Integer seqNo = row.getInt("项次");

            Map<String, Object> c = new LinkedHashMap<>();
            c.put("cost_type", costType);
            c.put("seq_no", seqNo);
            c.put("cost_ratio", row.getDecimal("比例"));
            c.put("pricing_price", BigDecimal.ZERO);  // 核价比例费用保持原行为（pricing_price=0）
            c.put("production_no", row.getStr("生产料号"));

            byCode.computeIfAbsent(code, k -> new LinkedHashMap<>())
                  .put(dedupKey(costType, seqNo), c);
            result.successRows++;
        }

        for (SheetRow row : fixedRows) {
            result.totalRows++;
            String code = row.getStr("销售料号", "宏丰料号");
            String costType = row.getStr("要素名称");
            if (code == null || costType == null) {
                result.recordError(row.rowNo, "宏丰料号/要素名称", "必填项为空");
                continue;
            }
            Integer seqNo = row.getInt("项次");
            BigDecimal price = row.getDecimal("费用");

            Map<String, Object> c = new LinkedHashMap<>();
            c.put("cost_type", costType);
            c.put("seq_no", seqNo);
            c.put("cost_ratio", null);
            c.put("pricing_price", price == null ? BigDecimal.ZERO : price);
            c.put("currency", row.getStr("币种"));
            c.put("unit", row.getStr("计价单位"));
            c.put("production_no", row.getStr("生产料号"));

            byCode.computeIfAbsent(code, k -> new LinkedHashMap<>())
                  .put(dedupKey(costType, seqNo), c);
            result.successRows++;
        }

        LinkedHashMap<Map<String, Object>, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        for (Map.Entry<String, LinkedHashMap<String, Map<String, Object>>> e : byCode.entrySet()) {
            Map<String, Object> gk = new LinkedHashMap<>();
            gk.put("system_type", "PRICING");
            gk.put("price_type", PricingPriceType.FINISHED_OTHER);
            gk.put("code", e.getKey());
            groups.put(gk, new ArrayList<>(e.getValue().values()));
        }
        try {
            writer.writeVersionedGroups("unit_price", "version_no", CONTENT, null, DESCRIPTOR, groups);
            for (List<Map<String, Object>> g : groups.values()) result.recordWrite("unit_price", g.size());
        } catch (Exception ex) {
            result.recordError(0, "_batch_", ex.getMessage());
        }
        return result;
    }

    private static String dedupKey(String costType, Integer seqNo) {
        return costType + "|" + seqNo;
    }
}
