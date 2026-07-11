package com.cpq.basicdata.v6.pricing;

import com.cpq.basicdata.v6.parser.ImportContext;
import com.cpq.basicdata.v6.parser.SheetHandler;
import com.cpq.basicdata.v6.parser.SheetImportResult;
import com.cpq.basicdata.v6.parser.SheetRow;
import com.cpq.basicdata.v6.util.DecimalScale;
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
 * P08 产能 (PRICING) → capacity 整组版本化（对齐 Q14）+ labor_rate 独立版本化（tesk-0709 §5.1 C 组）。
 *
 * <p>capacity：按 material_no 聚合工序行整组写，calc_version 系统生成（2000 起，忽略 Excel 计算版本）；
 * 去触发列（versionTriggerColumns=null 退化为用 contentColumns 作触发列 → 任一内容列变化即升版）；
 * production_no 变化本身不该驱动升版，故降为描述列（写入但不参与升版比对）；
 * resource_group_no='PRICING_DEFAULT'，system_type='PRICING'。
 * <p>labor_rate：不再借用 capacity 的版本号，按 material_no 独立聚合、独立版本化写入自身 version_no。
 */
@ApplicationScoped
public class P08CapacityHandler implements SheetHandler {

    public static final String RESOURCE_GROUP = "PRICING_DEFAULT";

    @Inject VersionedV6Writer writer;

    @Override public String sheetName() { return "产能"; }

    private static final List<String> CAP_CONTENT = List.of(
        "process_no", "production_type", "is_effective");
    private static final List<String> CAP_DESCRIPTOR = List.of("production_no");

    private static final List<String> LABOR_CONTENT = List.of(
        "process_no", "standard_labor_rate", "currency", "unit");
    private static final List<String> LABOR_DESCRIPTOR = List.of("production_no");

    /** 暂存每料号的 labor_rate 行（按 material_no 聚合后独立版本化写入）。 */
    private record LaborRow(String processNo, BigDecimal rate, String currency, String unit) {}

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public SheetImportResult handle(List<SheetRow> rows, ImportContext ctx) {
        SheetImportResult result = new SheetImportResult(sheetName());

        Map<String, List<Map<String, Object>>> capByMat = new LinkedHashMap<>();
        Map<String, List<LaborRow>> laborByMat = new LinkedHashMap<>();
        // 按 material_no 缓存生产料号（首个非空归并），供下方 capacity/labor_rate 描述列复用。
        Map<String, String> prodNoByMat = new LinkedHashMap<>();

        for (SheetRow row : rows) {
            result.totalRows++;
            String materialNo = row.getStr("销售料号", "宏丰料号");
            String processNo = row.getStr("工序编号");
            if (materialNo == null || processNo == null) {
                result.recordError(row.rowNo, "宏丰料号/工序编号", "必填项为空");
                continue;
            }
            Boolean isEffective = row.getBool("是否有效");
            String productionNo = row.getStr("生产料号");
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("process_no", processNo);
            c.put("production_type", "BATCH_FIXED");
            c.put("is_effective", isEffective == null ? Boolean.TRUE : isEffective);
            c.put("production_no", productionNo);
            capByMat.computeIfAbsent(materialNo, k -> new ArrayList<>()).add(c);
            prodNoByMat.putIfAbsent(materialNo, productionNo);

            BigDecimal laborRate = DecimalScale.at(row.getDecimal("人工标准单价"), 6);
            if (laborRate != null) {
                laborByMat.computeIfAbsent(materialNo, k -> new ArrayList<>())
                          .add(new LaborRow(processNo, laborRate, row.getStr("币种"), row.getStr("计量单位")));
            }
            result.successRows++;
        }

        // 1) capacity 整组版本化：去触发列（null → 任一内容列变化即升版），production_no 降为描述列。
        LinkedHashMap<Map<String, Object>, List<Map<String, Object>>> capGroups = new LinkedHashMap<>();
        for (Map.Entry<String, List<Map<String, Object>>> e : capByMat.entrySet()) {
            Map<String, Object> gk = new LinkedHashMap<>();
            gk.put("system_type", "PRICING");
            gk.put("material_no", e.getKey());
            gk.put("resource_group_no", RESOURCE_GROUP);
            capGroups.put(gk, e.getValue());
        }
        if (!capGroups.isEmpty()) {
            try {
                writer.writeVersionedGroups("capacity", "calc_version",
                    CAP_CONTENT, null, CAP_DESCRIPTOR, capGroups);
                for (List<Map<String, Object>> groupRows : capGroups.values())
                    result.recordWrite("capacity", groupRows.size());
            } catch (Exception ex) {
                result.recordError(0, "_batch_capacity_", ex.getMessage());
            }
        }

        // 2) labor_rate 独立版本化：不再借用 capacity 版本号，自身 groupKey={system_type, material_no}。
        LinkedHashMap<Map<String, Object>, List<Map<String, Object>>> laborGroups = new LinkedHashMap<>();
        for (Map.Entry<String, List<LaborRow>> e : laborByMat.entrySet()) {
            Map<String, Object> gk = new LinkedHashMap<>();
            gk.put("system_type", "PRICING");
            gk.put("material_no", e.getKey());
            List<Map<String, Object>> rs = new ArrayList<>();
            for (LaborRow lr : e.getValue()) {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("process_no", lr.processNo());
                r.put("standard_labor_rate", lr.rate());
                r.put("currency", lr.currency());
                r.put("unit", lr.unit());
                r.put("production_no", prodNoByMat.get(e.getKey()));  // 描述列
                rs.add(r);
            }
            laborGroups.put(gk, rs);
        }
        if (!laborGroups.isEmpty()) {
            try {
                writer.writeVersionedGroups("labor_rate", "version_no",
                    LABOR_CONTENT, null, LABOR_DESCRIPTOR, laborGroups);
                for (List<Map<String, Object>> groupRows : laborGroups.values())
                    result.recordWrite("labor_rate", groupRows.size());
            } catch (Exception ex) {
                result.recordError(0, "_batch_labor_rate_", ex.getMessage());
            }
        }

        return result;
    }
}
