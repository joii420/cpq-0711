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
import java.util.List;

/** P12 模具工装成本 → tooling_cost。 */
@ApplicationScoped
public class P12ToolingCostHandler implements SheetHandler {

    @Inject EntityManager em;

    @Override public String sheetName() { return "模具工装成本"; }

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public SheetImportResult handle(List<SheetRow> rows, ImportContext ctx) {
        SheetImportResult result = new SheetImportResult(sheetName());
        for (SheetRow row : rows) {
            result.totalRows++;
            try {
                String materialNo = row.getStr("宏丰料号");
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
                        "  currency, unit, is_effective, created_at, updated_at, updated_by) " +
                        "VALUES (:m, :p, :s, :tn, :tc, :tl, :co, :tp, :c, :u, :ie, NOW(), NOW(), :ub) " +
                        "ON CONFLICT (material_no, process_no, seq_no, tooling_no) DO UPDATE SET " +
                        "  tooling_unit_cost = COALESCE(EXCLUDED.tooling_unit_cost, tooling_cost.tooling_unit_cost), " +
                        "  tool_life = COALESCE(EXCLUDED.tool_life, tooling_cost.tool_life), " +
                        "  cycle_output = COALESCE(EXCLUDED.cycle_output, tooling_cost.cycle_output), " +
                        "  tooling_unit_price = EXCLUDED.tooling_unit_price, " +
                        "  currency = COALESCE(EXCLUDED.currency, tooling_cost.currency), " +
                        "  unit = COALESCE(EXCLUDED.unit, tooling_cost.unit), " +
                        "  is_effective = COALESCE(EXCLUDED.is_effective, tooling_cost.is_effective), " +
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
