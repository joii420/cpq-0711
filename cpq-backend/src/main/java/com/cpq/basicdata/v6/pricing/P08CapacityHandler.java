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
 * P08 产能 → capacity + labor_rate 双表同步。
 * <p>同 Sheet 写两张表：capacity 用占位 resource_group_no="PRICING_DEFAULT"；labor_rate 含人工标准单价。
 */
@ApplicationScoped
public class P08CapacityHandler implements SheetHandler {

    @Inject EntityManager em;

    @Override public String sheetName() { return "产能"; }

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public SheetImportResult handle(List<SheetRow> rows, ImportContext ctx) {
        SheetImportResult result = new SheetImportResult(sheetName());
        for (SheetRow row : rows) {
            result.totalRows++;
            try {
                String materialNo = row.getStr("宏丰料号");
                String processNo = row.getStr("工序编号");
                String calcVersion = row.getStr("取用的计算版本", "计算版本");
                if (materialNo == null || processNo == null || calcVersion == null) {
                    result.recordError(row.rowNo, "宏丰料号/工序编号/计算版本", "必填项为空");
                    continue;
                }
                Boolean isEffective = row.getBool("是否有效");
                java.math.BigDecimal laborRate = row.getDecimal("人工标准单价");

                // 1. capacity upsert
                em.createNativeQuery(
                        "INSERT INTO capacity (material_no, process_no, resource_group_no, " +
                        "  production_type, calc_version, is_effective, created_at, updated_at, updated_by) " +
                        "VALUES (:m, :p, 'PRICING_DEFAULT', 'BATCH_FIXED', :cv, :ie, NOW(), NOW(), :ub) " +
                        "ON CONFLICT (material_no, process_no, resource_group_no, COALESCE(calc_version,'')) " +
                        "DO UPDATE SET is_effective = EXCLUDED.is_effective, " +
                        "  updated_at = NOW(), updated_by = EXCLUDED.updated_by")
                    .setParameter("m", materialNo)
                    .setParameter("p", processNo)
                    .setParameter("cv", calcVersion)
                    .setParameter("ie", isEffective == null ? Boolean.TRUE : isEffective)
                    .setParameter("ub", ctx.importedBy)
                    .executeUpdate();
                result.recordWrite("capacity", 1);

                // 2. labor_rate upsert（含人工单价）
                if (laborRate != null) {
                    em.createNativeQuery(
                            "INSERT INTO labor_rate (version_no, material_no, process_no, standard_labor_rate, " +
                            "  currency, unit, created_at, updated_at, updated_by) " +
                            "VALUES (:vn, :m, :p, :r, :c, :u, NOW(), NOW(), :ub) " +
                            "ON CONFLICT (version_no, process_no, COALESCE(material_no,''), COALESCE(labor_grade,'')) " +
                            "DO UPDATE SET standard_labor_rate = EXCLUDED.standard_labor_rate, " +
                            "  currency = COALESCE(EXCLUDED.currency, labor_rate.currency), " +
                            "  unit = COALESCE(EXCLUDED.unit, labor_rate.unit), " +
                            "  updated_at = NOW(), updated_by = EXCLUDED.updated_by")
                        .setParameter("vn", calcVersion)
                        .setParameter("m", materialNo)
                        .setParameter("p", processNo)
                        .setParameter("r", laborRate)
                        .setParameter("c", row.getStr("币种"))
                        .setParameter("u", row.getStr("计量单位"))
                        .setParameter("ub", ctx.importedBy)
                        .executeUpdate();
                    result.recordWrite("labor_rate", 1);
                }
                result.successRows++;
            } catch (Exception e) {
                result.recordError(row.rowNo, "_row_", e.getMessage());
            }
        }
        return result;
    }
}
