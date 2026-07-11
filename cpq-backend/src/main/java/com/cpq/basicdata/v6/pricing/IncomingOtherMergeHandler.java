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
 * P16 来料其他费用（比例）+ P17 来料其他固定费用 → unit_price (PRICING/INCOMING_OTHER) 合并单版本组
 * 整批版本化（tesk-0709 Task 9）。
 *
 * <p>P16/P17 是两个独立 Sheet，但同 price_type=INCOMING_OTHER，对同一销售料号
 * （finished_material_no）属于同一个版本组：若各自独立调用 {@code writeVersionedGroups}，
 * 第二个 Sheet 会把第一个 Sheet 刚写入的 current 行当作"旧组"整体 flip + 重升版
 * （双升版 + 互相覆盖）。故本 handler 不是 {@link com.cpq.basicdata.v6.parser.SheetHandler}，
 * 而是独立 bean，照搬 {@code MaterialBomMergeHandler} 范式，由 {@code PricingImportService}
 * 在编排循环外显式调用 {@link #merge}，两个源 Sheet 一次性合并成一组 {@code groups} 再单次写入。
 *
 * <p>groupKey = {system_type:"PRICING", price_type:"INCOMING_OTHER", finished_material_no}
 * （不含 cost_type ——cost_type 是动态"要素"值，同一销售料号下比例/固定两类费用共享一个版本，
 * 任一费用变化整组一起升版）。content = [code(来料料号), cost_type, seq_no, cost_ratio,
 * pricing_price, currency, unit]；production_no 为描述列（写入不参与版本比对）。
 *
 * <p>组内去重键 = (code, cost_type, seq_no)：与 uq_unit_price 业务唯一键在本组内退化后的维度一致
 * （version_no/finished_material_no 组内恒定，customer_no/supplier_no/operation_no/discount_order/
 * item_seq/effective_date 均未设置=NULL），避免同批整组升版时组内重复行相互撞键；同键取最后一行
 * （末值覆盖，对齐原逐行 upsert 的 EXCLUDED 覆盖语义）。
 */
@ApplicationScoped
public class IncomingOtherMergeHandler {

    @Inject VersionedV6Writer writer;

    private static final List<String> CONTENT = List.of(
        "code", "cost_type", "seq_no", "cost_ratio", "pricing_price", "currency", "unit");
    private static final List<String> DESCRIPTOR = List.of("production_no");

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public SheetImportResult merge(List<SheetRow> ratioRows, List<SheetRow> fixedRows, ImportContext ctx) {
        SheetImportResult result = new SheetImportResult("来料其他费用（比例）+来料其他固定费用(合并)");

        // finished_material_no -> dedupKey(code|cost_type|seq_no) -> content row（末值覆盖）
        Map<String, LinkedHashMap<String, Map<String, Object>>> byFin = new LinkedHashMap<>();

        for (SheetRow row : ratioRows) {
            result.totalRows++;
            String code = row.getStr("来料料号");
            String costType = row.getStr("要素编号");
            if (code == null || costType == null) {
                result.recordError(row.rowNo, "来料料号/要素编号", "必填项为空");
                continue;
            }
            String fin = row.getStr("销售料号", "宏丰料号", "成品料号");
            if (fin == null) { result.recordError(row.rowNo, "销售料号", "为空"); continue; }
            Integer seqNo = row.getInt("二级项次", "项次");

            Map<String, Object> c = new LinkedHashMap<>();
            c.put("code", code);
            c.put("cost_type", costType);
            c.put("seq_no", seqNo);
            c.put("cost_ratio", row.getDecimal("比例"));
            c.put("pricing_price", BigDecimal.ZERO);  // 核价比例费用保持原行为（pricing_price=0）
            c.put("production_no", row.getStr("生产料号"));

            byFin.computeIfAbsent(fin, k -> new LinkedHashMap<>())
                 .put(dedupKey(code, costType, seqNo), c);
            result.successRows++;
        }

        for (SheetRow row : fixedRows) {
            result.totalRows++;
            String code = row.getStr("来料料号");
            String costType = row.getStr("要素名称");
            if (code == null || costType == null) {
                result.recordError(row.rowNo, "来料料号/要素名称", "必填项为空");
                continue;
            }
            String fin = row.getStr("销售料号", "宏丰料号", "成品料号");
            if (fin == null) { result.recordError(row.rowNo, "销售料号", "为空"); continue; }
            Integer seqNo = row.getInt("二级项次", "项次");
            BigDecimal price = row.getDecimal("费用");

            Map<String, Object> c = new LinkedHashMap<>();
            c.put("code", code);
            c.put("cost_type", costType);
            c.put("seq_no", seqNo);
            c.put("cost_ratio", null);
            c.put("pricing_price", price == null ? BigDecimal.ZERO : price);
            c.put("currency", row.getStr("币种"));
            c.put("unit", row.getStr("计价单位"));
            c.put("production_no", row.getStr("生产料号"));

            byFin.computeIfAbsent(fin, k -> new LinkedHashMap<>())
                 .put(dedupKey(code, costType, seqNo), c);
            result.successRows++;
        }

        LinkedHashMap<Map<String, Object>, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        for (Map.Entry<String, LinkedHashMap<String, Map<String, Object>>> e : byFin.entrySet()) {
            Map<String, Object> gk = new LinkedHashMap<>();
            gk.put("system_type", "PRICING");
            gk.put("price_type", PricingPriceType.INCOMING_OTHER);
            gk.put("finished_material_no", e.getKey());
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

    private static String dedupKey(String code, String costType, Integer seqNo) {
        return code + "|" + costType + "|" + seqNo;
    }
}
