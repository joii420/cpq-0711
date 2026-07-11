package com.cpq.basicdata.v6.pricing;

import com.cpq.basicdata.v6.parser.ImportContext;
import com.cpq.basicdata.v6.parser.SheetHandler;
import com.cpq.basicdata.v6.parser.SheetImportResult;
import com.cpq.basicdata.v6.parser.SheetRow;
import com.cpq.basicdata.v6.versioning.VersionedV6Writer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.math.BigDecimal;
import java.util.*;

/**
 * P12 模具工装成本 → tooling_cost，按 material_no 整批版本化（tesk-0709 Task 5）。
 * <p>groupKey = {system_type:"PRICING", material_no}；一个料号下所有模具明细
 * (process_no/seq_no/tooling_no 区分组内多行) 整批比对、整批升版。production_no 为
 * 只写入不参与版本比对的描述列。
 */
@ApplicationScoped
public class P12ToolingCostHandler implements SheetHandler {

    @Inject VersionedV6Writer writer;

    @Override public String sheetName() { return "模具工装成本"; }

    private static final List<String> CONTENT = List.of(
        "process_no", "seq_no", "tooling_no", "tooling_unit_cost", "tool_life",
        "cycle_output", "tooling_unit_price", "currency", "unit", "is_effective");
    private static final List<String> DESCRIPTOR = List.of("production_no");

    private static final class Row {
        final String processNo, toolingNo, currency, unit, productionNo;
        final Integer seqNo;
        final BigDecimal toolingUnitCost, cycleOutput, toolingUnitPrice;
        final Long toolLife;
        final Boolean isEffective;
        Row(String p, Integer s, String tn, BigDecimal tc, Long tl, BigDecimal co,
            BigDecimal tp, String c, String u, Boolean ie, String pn) {
            processNo = p; seqNo = s; toolingNo = tn; toolingUnitCost = tc;
            toolLife = tl; cycleOutput = co; toolingUnitPrice = tp; currency = c; unit = u; isEffective = ie;
            productionNo = pn;
        }
        /** 同批同 (processNo,seqNo,toolingNo) 折叠：tooling_unit_price 取 last(NOT NULL,解析时已兜底 ZERO)；
         *  其余(含 production_no) COALESCE → last-non-null，语义对齐原裸 SQL upsert 的 EXCLUDED/COALESCE 组合。 */
        static Row fold(Row p, Row n) {
            return new Row(n.processNo, n.seqNo, n.toolingNo,
                n.toolingUnitCost != null ? n.toolingUnitCost : p.toolingUnitCost,
                n.toolLife != null ? n.toolLife : p.toolLife,
                n.cycleOutput != null ? n.cycleOutput : p.cycleOutput,
                n.toolingUnitPrice,
                n.currency != null ? n.currency : p.currency,
                n.unit != null ? n.unit : p.unit,
                n.isEffective != null ? n.isEffective : p.isEffective,
                n.productionNo != null ? n.productionNo : p.productionNo);
        }
        Map<String, Object> toContentRow() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("process_no", processNo);
            m.put("seq_no", seqNo);
            m.put("tooling_no", toolingNo);
            m.put("tooling_unit_cost", toolingUnitCost);
            m.put("tool_life", toolLife);
            m.put("cycle_output", cycleOutput);
            m.put("tooling_unit_price", toolingUnitPrice);
            m.put("currency", currency);
            m.put("unit", unit);
            m.put("is_effective", isEffective);
            m.put("production_no", productionNo);   // 描述列：写入但不参与版本比对
            return m;
        }
    }
    private static String nz(String s) { return s == null ? "" : s; }

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public SheetImportResult handle(List<SheetRow> rows, ImportContext ctx) {
        SheetImportResult result = new SheetImportResult(sheetName());
        // materialNo → (processNo,seqNo,toolingNo) → Row：组内先按业务键折叠去重，
        // 避免同批同 key 多行在整批版本化 INSERT 时撞 (system_type, material_no, process_no, seq_no,
        // tooling_no, calc_version) 唯一索引（同组内所有行共用同一新版本号）。
        Map<String, LinkedHashMap<List<String>, Row>> byMat = new LinkedHashMap<>();
        for (SheetRow row : rows) {
            result.totalRows++;
            try {
                String materialNo = row.getStr("销售料号", "宏丰料号");
                String processNo = row.getStr("工序编号");
                Integer seqNo = row.getInt("项次");
                String toolingNo = row.getStr("模具台账", "工装编号", "模具编号");
                BigDecimal unitPrice = row.getDecimal("模具工装成本单价", "摊销后单价");
                if (materialNo == null || processNo == null || seqNo == null || toolingNo == null) {
                    result.recordError(row.rowNo, "宏丰料号/工序编号/项次/模具编号", "必填项为空");
                    continue;
                }
                // tesk-0709 Task 11 E2E 修复（2026-07-11）：tooling_unit_price 常来自 Excel 公式
                // （如"单个模具/寿命/单循环产量"），POI 读取的 cached 值是 IEEE-754 double 全精度
                // （可达 17~18 位有效数字，如 0.013333333333333334），而 DB 列 tooling_cost.tooling_unit_price
                // 是 numeric(18,8)，落库时会被 Postgres 静默四舍五入到 8 位小数。若不在解析时同步舍入，
                // "新解析值(全精度)" 与 "重导时从库里读回的 existing(已截断至 8 位)" 在 VersionedV6Writer
                // 内容比对(norm()/multisetEqual)里恒不相等 → 同文件重导也会误判"内容变化"而升版
                // （违反§7.4"重导不升版"）。按列的实际精度舍入，让"新解析值"与"落库后重读值"从一开始就一致。
                if (unitPrice != null) unitPrice = unitPrice.setScale(8, java.math.RoundingMode.HALF_UP);
                // tooling_unit_price 为 NOT NULL 列：解析不到时兜底 ZERO，保持原裸 SQL 逻辑不变。
                Row r = new Row(processNo, seqNo, toolingNo,
                    row.getDecimal("单个模具", "工装成本"), row.getLong("寿命"),
                    row.getDecimal("单循环产量"), unitPrice != null ? unitPrice : BigDecimal.ZERO,
                    row.getStr("币种"), row.getStr("计量单位"), row.getBool("是否有效"),
                    row.getStr("生产料号"));
                List<String> key = List.of(nz(processNo), String.valueOf(seqNo), nz(toolingNo));
                byMat.computeIfAbsent(materialNo, k -> new LinkedHashMap<>()).merge(key, r, Row::fold);
                result.successRows++;
            } catch (Exception e) {
                result.recordError(row.rowNo, "_row_", e.getMessage());
            }
        }

        LinkedHashMap<Map<String, Object>, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        for (Map.Entry<String, LinkedHashMap<List<String>, Row>> e : byMat.entrySet()) {
            Map<String, Object> gk = new LinkedHashMap<>();
            gk.put("system_type", "PRICING");
            gk.put("material_no", e.getKey());
            List<Map<String, Object>> content = new ArrayList<>();
            for (Row r : e.getValue().values()) content.add(r.toContentRow());
            groups.put(gk, content);
        }
        try {
            writer.writeVersionedGroups("tooling_cost", "calc_version", CONTENT, null, DESCRIPTOR, groups);
            for (List<Map<String, Object>> g : groups.values()) result.recordWrite("tooling_cost", g.size());
        } catch (Exception ex) {
            result.recordError(0, "_batch_", ex.getMessage());
        }
        return result;
    }
}
