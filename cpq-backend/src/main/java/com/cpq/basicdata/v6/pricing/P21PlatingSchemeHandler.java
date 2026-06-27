package com.cpq.basicdata.v6.pricing;

import com.cpq.basicdata.v6.parser.ImportContext;
import com.cpq.basicdata.v6.parser.SheetHandler;
import com.cpq.basicdata.v6.parser.SheetImportResult;
import com.cpq.basicdata.v6.parser.SheetRow;
import com.cpq.basicdata.v6.versioning.VersionedGroupSpec;
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
 * P21 电镀方案 (PRICING) → plating_scheme 整组版本化（对齐 Q16）。
 *
 * <p>全局表，groupKey=(system_type='PRICING', scheme_no)；scheme_version 系统生成（忽略 Excel「版本」列）；
 * 组内多行按 seq_no 区分（plating_scheme uq 含 seq_no）。核价版本含 density 列。
 */
@ApplicationScoped
public class P21PlatingSchemeHandler implements SheetHandler {

    @Inject VersionedV6Writer writer;

    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "cpq.v6import-setbased-writer", defaultValue = "false")
    boolean setBased;

    @Override public String sheetName() { return "电镀方案"; }

    private static final List<String> CONTENT = List.of(
        "seq_no", "plating_element", "plating_method", "surface_area", "plating_area",
        "plating_thickness", "plating_requirement", "density", "element_usage");

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public SheetImportResult handle(List<SheetRow> rows, ImportContext ctx) {
        SheetImportResult result = new SheetImportResult(sheetName());
        Map<String, List<Map<String, Object>>> contentOf = new LinkedHashMap<>();
        for (SheetRow row : rows) {
            result.totalRows++;
            String schemeNo = row.getStr("方案编号");
            Integer seqNo = row.getInt("项次");
            String element = row.getStr("电镀元素名称");
            if (schemeNo == null || seqNo == null || element == null) {
                result.recordError(row.rowNo, "方案编号/项次/电镀元素", "必填项为空");
                continue;
            }
            BigDecimal platingArea = row.getDecimal("电镀面积");
            BigDecimal thickness = row.getDecimal("镀层厚度");
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("seq_no", seqNo);
            c.put("plating_element", element);
            c.put("plating_method", "电镀");
            c.put("surface_area", platingArea != null ? platingArea : BigDecimal.ZERO);
            c.put("plating_area", platingArea);
            c.put("plating_thickness", thickness != null ? thickness : BigDecimal.ZERO);
            c.put("plating_requirement", row.getStr("电镀要求"));
            c.put("density", row.getDecimal("密度"));
            c.put("element_usage", BigDecimal.ZERO);
            contentOf.computeIfAbsent(schemeNo, k -> new ArrayList<>()).add(c);
            result.successRows++;
        }
        if (setBased) {
            LinkedHashMap<Map<String, Object>, List<Map<String, Object>>> groups = new LinkedHashMap<>();
            for (Map.Entry<String, List<Map<String, Object>>> e : contentOf.entrySet()) {
                Map<String, Object> gk = new LinkedHashMap<>();
                gk.put("system_type", "PRICING");
                gk.put("scheme_no", e.getKey());
                groups.put(gk, e.getValue());
            }
            try {
                writer.writeVersionedGroups("plating_scheme", "scheme_version", CONTENT, null, groups);
                for (List<Map<String, Object>> groupRows : groups.values())
                    result.recordWrite("plating_scheme", groupRows.size());
            } catch (Exception ex) {
                result.recordError(0, "_batch_", ex.getMessage());
            }
        } else {
            for (Map.Entry<String, List<Map<String, Object>>> e : contentOf.entrySet()) {
                try {
                    Map<String, Object> gk = new LinkedHashMap<>();
                    gk.put("system_type", "PRICING");
                    gk.put("scheme_no", e.getKey());
                    writer.writeVersionedGroup(new VersionedGroupSpec(
                        "plating_scheme", "scheme_version", gk, CONTENT, e.getValue()));
                    result.recordWrite("plating_scheme", e.getValue().size());
                } catch (Exception ex) {
                    result.recordError(0, "_group_", "scheme_no=" + e.getKey() + ": " + ex.getMessage());
                }
            }
        }
        return result;
    }
}
