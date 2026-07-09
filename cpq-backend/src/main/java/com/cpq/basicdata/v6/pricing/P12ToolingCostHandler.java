package com.cpq.basicdata.v6.pricing;

import com.cpq.basicdata.v6.parser.ImportContext;
import com.cpq.basicdata.v6.parser.SheetHandler;
import com.cpq.basicdata.v6.parser.SheetImportResult;
import com.cpq.basicdata.v6.parser.SheetRow;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

/** P12 模具工装成本 → tooling_cost。 */
@ApplicationScoped
public class P12ToolingCostHandler implements SheetHandler {

    @Inject EntityManager em;

    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "cpq.v6import-setbased-writer", defaultValue = "false")
    boolean setBased;

    @Override public String sheetName() { return "模具工装成本"; }

    private static final class Row {
        final String materialNo, processNo, toolingNo, currency, unit, productionNo;
        final Integer seqNo;
        final BigDecimal toolingUnitCost, cycleOutput, toolingUnitPrice;
        final Long toolLife;
        final Boolean isEffective;
        Row(String m, String p, Integer s, String tn, BigDecimal tc, Long tl, BigDecimal co,
            BigDecimal tp, String c, String u, Boolean ie, String pn) {
            materialNo = m; processNo = p; seqNo = s; toolingNo = tn; toolingUnitCost = tc;
            toolLife = tl; cycleOutput = co; toolingUnitPrice = tp; currency = c; unit = u; isEffective = ie;
            productionNo = pn;
        }
        static Row fold(Row p, Row n) {
            // tooling_unit_price=EXCLUDED 覆盖 → last；其余(含 production_no) COALESCE → last-non-null。
            return new Row(n.materialNo, n.processNo, n.seqNo, n.toolingNo,
                n.toolingUnitCost != null ? n.toolingUnitCost : p.toolingUnitCost,
                n.toolLife != null ? n.toolLife : p.toolLife,
                n.cycleOutput != null ? n.cycleOutput : p.cycleOutput,
                n.toolingUnitPrice,
                n.currency != null ? n.currency : p.currency,
                n.unit != null ? n.unit : p.unit,
                n.isEffective != null ? n.isEffective : p.isEffective,
                n.productionNo != null ? n.productionNo : p.productionNo);
        }
    }
    private static String nz(String s) { return s == null ? "" : s; }

    private void batchUpsert(List<Row> rows, UUID ub) {
        if (rows.isEmpty()) return;
        LinkedHashMap<List<String>, Row> dedup = new LinkedHashMap<>();
        for (Row r : rows) {
            dedup.merge(List.of(nz(r.materialNo), nz(r.processNo), String.valueOf(r.seqNo), nz(r.toolingNo)), r, Row::fold);
        }
        List<Row> folded = new ArrayList<>(dedup.values());
        final int CHUNK = 500;
        for (int off = 0; off < folded.size(); off += CHUNK) {
            List<Row> chunk = folded.subList(off, Math.min(off + CHUNK, folded.size()));
            StringBuilder vals = new StringBuilder();
            for (int i = 0; i < chunk.size(); i++) {
                if (i > 0) vals.append(", ");
                int b = i * 12;
                vals.append("(:p").append(b).append(", :p").append(b + 1).append(", :p").append(b + 2)
                    .append(", :p").append(b + 3).append(", :p").append(b + 4).append(", :p").append(b + 5)
                    .append(", :p").append(b + 6).append(", :p").append(b + 7).append(", :p").append(b + 8)
                    .append(", :p").append(b + 9).append(", :p").append(b + 10).append(", :p").append(b + 11)
                    .append(", NOW(), NOW(), :ub)");
            }
            jakarta.persistence.Query q = em.createNativeQuery(
                "INSERT INTO tooling_cost (material_no, process_no, seq_no, tooling_no, " +
                "  tooling_unit_cost, tool_life, cycle_output, tooling_unit_price, " +
                "  currency, unit, is_effective, production_no, created_at, updated_at, updated_by) VALUES " + vals +
                " ON CONFLICT (material_no, process_no, seq_no, tooling_no) DO UPDATE SET " +
                "  tooling_unit_cost = COALESCE(EXCLUDED.tooling_unit_cost, tooling_cost.tooling_unit_cost), " +
                "  tool_life = COALESCE(EXCLUDED.tool_life, tooling_cost.tool_life), " +
                "  cycle_output = COALESCE(EXCLUDED.cycle_output, tooling_cost.cycle_output), " +
                "  tooling_unit_price = EXCLUDED.tooling_unit_price, " +
                "  currency = COALESCE(EXCLUDED.currency, tooling_cost.currency), " +
                "  unit = COALESCE(EXCLUDED.unit, tooling_cost.unit), " +
                "  is_effective = COALESCE(EXCLUDED.is_effective, tooling_cost.is_effective), " +
                "  production_no = COALESCE(EXCLUDED.production_no, tooling_cost.production_no), " +
                "  updated_at = NOW(), updated_by = EXCLUDED.updated_by");
            for (int i = 0; i < chunk.size(); i++) {
                Row r = chunk.get(i); int b = i * 12;
                q.setParameter("p" + b, r.materialNo);
                q.setParameter("p" + (b + 1), r.processNo);
                q.setParameter("p" + (b + 2), r.seqNo);
                q.setParameter("p" + (b + 3), r.toolingNo);
                q.setParameter("p" + (b + 4), r.toolingUnitCost);
                q.setParameter("p" + (b + 5), r.toolLife);
                q.setParameter("p" + (b + 6), r.cycleOutput);
                q.setParameter("p" + (b + 7), r.toolingUnitPrice);
                q.setParameter("p" + (b + 8), r.currency);
                q.setParameter("p" + (b + 9), r.unit);
                q.setParameter("p" + (b + 10), r.isEffective);
                q.setParameter("p" + (b + 11), r.productionNo);
            }
            q.setParameter("ub", ub);
            q.executeUpdate();
        }
    }

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public SheetImportResult handle(List<SheetRow> rows, ImportContext ctx) {
        SheetImportResult result = new SheetImportResult(sheetName());
        if (setBased) {
            List<Row> acc = new ArrayList<>();
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
                    acc.add(new Row(materialNo, processNo, seqNo, toolingNo,
                        row.getDecimal("单个模具", "工装成本"), row.getLong("寿命"),
                        row.getDecimal("单循环产量"), unitPrice != null ? unitPrice : BigDecimal.ZERO,
                        row.getStr("币种"), row.getStr("计量单位"), row.getBool("是否有效"),
                        row.getStr("生产料号")));
                    result.successRows++;
                    result.recordWrite("tooling_cost", 1);
                } catch (Exception e) {
                    result.recordError(row.rowNo, "_row_", e.getMessage());
                }
            }
            batchUpsert(acc, ctx.importedBy);
            return result;
        }
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
                em.createNativeQuery(
                        "INSERT INTO tooling_cost (material_no, process_no, seq_no, tooling_no, " +
                        "  tooling_unit_cost, tool_life, cycle_output, tooling_unit_price, " +
                        "  currency, unit, is_effective, production_no, created_at, updated_at, updated_by) " +
                        "VALUES (:m, :p, :s, :tn, :tc, :tl, :co, :tp, :c, :u, :ie, :pn, NOW(), NOW(), :ub) " +
                        "ON CONFLICT (material_no, process_no, seq_no, tooling_no) DO UPDATE SET " +
                        "  tooling_unit_cost = COALESCE(EXCLUDED.tooling_unit_cost, tooling_cost.tooling_unit_cost), " +
                        "  tool_life = COALESCE(EXCLUDED.tool_life, tooling_cost.tool_life), " +
                        "  cycle_output = COALESCE(EXCLUDED.cycle_output, tooling_cost.cycle_output), " +
                        "  tooling_unit_price = EXCLUDED.tooling_unit_price, " +
                        "  currency = COALESCE(EXCLUDED.currency, tooling_cost.currency), " +
                        "  unit = COALESCE(EXCLUDED.unit, tooling_cost.unit), " +
                        "  is_effective = COALESCE(EXCLUDED.is_effective, tooling_cost.is_effective), " +
                        "  production_no = COALESCE(EXCLUDED.production_no, tooling_cost.production_no), " +
                        "  updated_at = NOW(), updated_by = EXCLUDED.updated_by")
                    .setParameter("m", materialNo)
                    .setParameter("p", processNo)
                    .setParameter("s", seqNo)
                    .setParameter("tn", toolingNo)
                    .setParameter("tc", row.getDecimal("单个模具", "工装成本"))
                    .setParameter("tl", row.getLong("寿命"))
                    .setParameter("co", row.getDecimal("单循环产量"))
                    .setParameter("tp", unitPrice != null ? unitPrice : BigDecimal.ZERO)
                    .setParameter("c", row.getStr("币种"))
                    .setParameter("u", row.getStr("计量单位"))
                    .setParameter("ie", row.getBool("是否有效"))
                    .setParameter("pn", row.getStr("生产料号"))
                    .setParameter("ub", ctx.importedBy)
                    .executeUpdate();
                result.successRows++;
                result.recordWrite("tooling_cost", 1);
            } catch (Exception e) {
                result.recordError(row.rowNo, "_row_", e.getMessage());
            }
        }
        return result;
    }
}
