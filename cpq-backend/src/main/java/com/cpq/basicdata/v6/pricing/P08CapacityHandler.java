package com.cpq.basicdata.v6.pricing;

import com.cpq.basicdata.v6.parser.ImportContext;
import com.cpq.basicdata.v6.parser.SheetHandler;
import com.cpq.basicdata.v6.parser.SheetImportResult;
import com.cpq.basicdata.v6.parser.SheetRow;
import com.cpq.basicdata.v6.versioning.VersionedGroupSpec;
import com.cpq.basicdata.v6.versioning.VersionedV6Writer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * P08 产能 (PRICING) → capacity 整组版本化（对齐 Q14）+ labor_rate 版本同步。
 *
 * <p>capacity：按 material_no 聚合工序行整组写，calc_version 系统生成（2000 起，忽略 Excel 计算版本）；
 * 升版触发列 = process_no（工序集合变化才升版）；resource_group_no='PRICING_DEFAULT'，system_type='PRICING'。
 * <p>labor_rate：version_no 用 capacity 返回的系统版本号，保持与产能同版本。
 */
@ApplicationScoped
public class P08CapacityHandler implements SheetHandler {

    public static final String RESOURCE_GROUP = "PRICING_DEFAULT";

    @Inject VersionedV6Writer writer;
    @Inject EntityManager em;

    @Override public String sheetName() { return "产能"; }

    private static final List<String> CONTENT = List.of(
        "process_no", "production_type", "is_effective");
    private static final List<String> VERSION_TRIGGER = List.of("process_no");

    /** 暂存每料号的 labor_rate 行（capacity 升版后再按版本号写）。 */
    private record LaborRow(String processNo, BigDecimal rate, String currency, String unit) {}

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public SheetImportResult handle(List<SheetRow> rows, ImportContext ctx) {
        SheetImportResult result = new SheetImportResult(sheetName());

        Map<String, List<Map<String, Object>>> capByMat = new LinkedHashMap<>();
        Map<String, List<LaborRow>> laborByMat = new LinkedHashMap<>();

        for (SheetRow row : rows) {
            result.totalRows++;
            String materialNo = row.getStr("宏丰料号");
            String processNo = row.getStr("工序编号");
            if (materialNo == null || processNo == null) {
                result.recordError(row.rowNo, "宏丰料号/工序编号", "必填项为空");
                continue;
            }
            Boolean isEffective = row.getBool("是否有效");
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("process_no", processNo);
            c.put("production_type", "BATCH_FIXED");
            c.put("is_effective", isEffective == null ? Boolean.TRUE : isEffective);
            capByMat.computeIfAbsent(materialNo, k -> new ArrayList<>()).add(c);

            BigDecimal laborRate = row.getDecimal("人工标准单价");
            if (laborRate != null) {
                laborByMat.computeIfAbsent(materialNo, k -> new ArrayList<>())
                          .add(new LaborRow(processNo, laborRate, row.getStr("币种"), row.getStr("计量单位")));
            }
            result.successRows++;
        }

        for (Map.Entry<String, List<Map<String, Object>>> e : capByMat.entrySet()) {
            String materialNo = e.getKey();
            try {
                Map<String, Object> gk = new LinkedHashMap<>();
                gk.put("system_type", "PRICING");
                gk.put("material_no", materialNo);
                gk.put("resource_group_no", RESOURCE_GROUP);
                String version = writer.writeVersionedGroup(new VersionedGroupSpec(
                    "capacity", "calc_version", gk, CONTENT, e.getValue(), VERSION_TRIGGER));
                result.recordWrite("capacity", e.getValue().size());

                for (LaborRow lr : laborByMat.getOrDefault(materialNo, List.of())) {
                    em.createNativeQuery(
                            "INSERT INTO labor_rate (version_no, material_no, process_no, standard_labor_rate, " +
                            "  currency, unit, created_at, updated_at, updated_by) " +
                            "VALUES (:vn, :m, :p, :r, :c, :u, NOW(), NOW(), :ub) " +
                            "ON CONFLICT (version_no, process_no, COALESCE(material_no,''), COALESCE(labor_grade,'')) " +
                            "DO UPDATE SET standard_labor_rate = EXCLUDED.standard_labor_rate, " +
                            "  currency = COALESCE(EXCLUDED.currency, labor_rate.currency), " +
                            "  unit = COALESCE(EXCLUDED.unit, labor_rate.unit), " +
                            "  updated_at = NOW(), updated_by = EXCLUDED.updated_by")
                        .setParameter("vn", version)
                        .setParameter("m", materialNo)
                        .setParameter("p", lr.processNo())
                        .setParameter("r", lr.rate())
                        .setParameter("c", lr.currency())
                        .setParameter("u", lr.unit())
                        .setParameter("ub", ctx.importedBy)
                        .executeUpdate();
                    result.recordWrite("labor_rate", 1);
                }
            } catch (Exception ex) {
                result.recordError(0, "_group_", "material_no=" + materialNo + ": " + ex.getMessage());
            }
        }
        return result;
    }
}
