package com.cpq.basicdata.v6.pricing;

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
 * P09 设备折旧成本 → production_energy.depreciation_unit_price 合并写入。
 * <p>用 SQL ON CONFLICT DO UPDATE 合并到同一行 (material_no, process_no, equipment_no, calc_version)。
 */
@ApplicationScoped
public class P09EquipmentDepreciationHandler implements SheetHandler {

    @Inject EntityManager em;

    @Override public String sheetName() { return "设备折旧成本"; }

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public SheetImportResult handle(List<SheetRow> rows, ImportContext ctx) {
        SheetImportResult result = new SheetImportResult(sheetName());
        for (SheetRow row : rows) {
            result.totalRows++;
            try {
                String materialNo = row.getStr("宏丰料号");
                String processNo = row.getStr("工序编号");
                if (materialNo == null || processNo == null) {
                    result.recordError(row.rowNo, "宏丰料号/工序编号", "必填项为空");
                    continue;
                }
                em.createNativeQuery(
                        "INSERT INTO production_energy (material_no, process_no, depreciation_unit_price, " +
                        "  currency, unit, calc_version, created_at, updated_at, updated_by) " +
                        "VALUES (:m, :p, :d, :c, :u, :cv, NOW(), NOW(), :ub) " +
                        "ON CONFLICT (material_no, process_no, COALESCE(equipment_no,''), COALESCE(calc_version,'')) " +
                        "DO UPDATE SET depreciation_unit_price = EXCLUDED.depreciation_unit_price, " +
                        "  currency = COALESCE(EXCLUDED.currency, production_energy.currency), " +
                        "  unit = COALESCE(EXCLUDED.unit, production_energy.unit), " +
                        "  updated_at = NOW(), updated_by = EXCLUDED.updated_by")
                    .setParameter("m", materialNo)
                    .setParameter("p", processNo)
                    .setParameter("d", row.getDecimal("折旧单价"))
                    .setParameter("c", row.getStr("币种"))
                    .setParameter("u", row.getStr("计量单位"))
                    .setParameter("cv", row.getStr("取用的计算版本", "计算版本"))
                    .setParameter("ub", ctx.importedBy)
                    .executeUpdate();
                result.successRows++;
                result.recordWrite("production_energy", 1);
            } catch (Exception e) {
                result.recordError(row.rowNo, "_row_", e.getMessage());
            }
        }
        return result;
    }
}
