package com.cpq.basicdata.v6.quote;

import com.cpq.basicdata.v6.entity.Capacity;
import com.cpq.basicdata.v6.parser.ImportContext;
import com.cpq.basicdata.v6.parser.SheetHandler;
import com.cpq.basicdata.v6.parser.SheetImportResult;
import com.cpq.basicdata.v6.parser.SheetRow;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.util.List;

/**
 * Q14 组装加工费 → capacity 表（不是 unit_price）。
 * <p>业务键：(material_no, process_no, resource_group_no, calc_version)。本 Handler 用 "QUOTE_ASSEMBLY" 占位 resource_group_no，
 * V_DEFAULT 作 calc_version。
 */
@ApplicationScoped
public class Q14AssemblyProcessFeeHandler implements SheetHandler {

    @Inject EntityManager em;

    @Override public String sheetName() { return "组装加工费"; }

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public SheetImportResult handle(List<SheetRow> rows, ImportContext ctx) {
        SheetImportResult result = new SheetImportResult(sheetName());
        for (SheetRow row : rows) {
            result.totalRows++;
            try {
                String materialNo = row.getStr("宏丰料号");
                String processNo = row.getStr("组装工序", "工序编号");
                if (materialNo == null || processNo == null) {
                    result.recordError(row.rowNo, "宏丰料号/工序编号", "必填项为空");
                    continue;
                }
                String resourceGroupNo = "QUOTE_ASSEMBLY";
                String calcVersion = "V_DEFAULT";

                // 用 native upsert (ON CONFLICT (uq_capacity 索引列))
                em.createNativeQuery(
                        "INSERT INTO capacity (material_no, process_no, resource_group_no, seq_no, " +
                        "  production_type, fixed_cost, currency, capacity_unit, default_defect_rate, " +
                        "  calc_version, is_effective, created_at, updated_at, updated_by) " +
                        "VALUES (:m, :p, :r, :seq, 'BATCH_FIXED', :fc, :cur, :u, :dr, :cv, true, NOW(), NOW(), :ub) " +
                        "ON CONFLICT (material_no, process_no, resource_group_no, COALESCE(calc_version,'')) " +
                        "DO UPDATE SET fixed_cost = EXCLUDED.fixed_cost, " +
                        "  seq_no = COALESCE(EXCLUDED.seq_no, capacity.seq_no), " +
                        "  currency = COALESCE(EXCLUDED.currency, capacity.currency), " +
                        "  capacity_unit = COALESCE(EXCLUDED.capacity_unit, capacity.capacity_unit), " +
                        "  default_defect_rate = COALESCE(EXCLUDED.default_defect_rate, capacity.default_defect_rate), " +
                        "  updated_at = NOW(), updated_by = EXCLUDED.updated_by")
                    .setParameter("m", materialNo)
                    .setParameter("p", processNo)
                    .setParameter("r", resourceGroupNo)
                    .setParameter("seq", row.getInt("项次"))
                    .setParameter("fc", row.getDecimal("组装加工费"))
                    .setParameter("cur", row.getStr("货币"))
                    .setParameter("u", row.getStr("计价单位"))
                    .setParameter("dr", row.getDecimal("拒收率", "不良率"))
                    .setParameter("cv", calcVersion)
                    .setParameter("ub", ctx.importedBy)
                    .executeUpdate();
                result.successRows++;
                result.recordWrite("capacity", 1);
            } catch (Exception e) {
                result.recordError(row.rowNo, "_row_", e.getMessage());
            }
        }
        return result;
    }
}
