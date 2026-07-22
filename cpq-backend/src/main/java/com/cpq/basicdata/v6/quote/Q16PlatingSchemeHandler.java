package com.cpq.basicdata.v6.quote;

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
 * Q16 电镀方案 → plating_scheme（全局共享表，groupKey 仅 scheme_no）。
 *
 * <p>版本化（Task 6）：按 scheme_no 分组，scheme_version 由系统生成（**忽略 Excel「版本」列**，决策⑨）。
 * 组内多行按 seq_no 区分（plating_scheme uq 含 seq_no）。
 */
@ApplicationScoped
public class Q16PlatingSchemeHandler implements SheetHandler {

    @Inject VersionedV6Writer writer;

    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "cpq.v6import-setbased-writer", defaultValue = "false")
    boolean setBased;

    @Override public String sheetName() { return "电镀方案"; }

    private static final List<String> CONTENT = List.of(
        "seq_no", "plating_element", "plating_method", "surface_area", "plating_area",
        "plating_thickness", "plating_requirement", "element_usage", "source_url", "source_name", "fetch_rule");

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
            c.put("element_usage", BigDecimal.ZERO);   // 由系统按 area*thickness*density 计算，初始 0
            c.put("source_url", row.getStr("元素单价来源网站网址", "网址"));
            c.put("source_name", row.getStr("元素单价来源网站名称", "网站名称"));
            c.put("fetch_rule", row.getStr("元素单价抓取规则", "取用规则"));
            contentOf.computeIfAbsent(schemeNo, k -> new ArrayList<>()).add(c);
            result.successRows++;
        }
        if (setBased) {
            LinkedHashMap<Map<String, Object>, List<Map<String, Object>>> groups = new LinkedHashMap<>();
            for (Map.Entry<String, List<Map<String, Object>>> e : contentOf.entrySet()) {
                Map<String, Object> gk = new LinkedHashMap<>();
                gk.put("system_type", "QUOTE");
                gk.put("scheme_no", e.getKey());
                groups.put(gk, e.getValue());
            }
            try {
                writer.writeVersionedGroups("plating_scheme", "scheme_version", CONTENT, null, List.of(), groups, ctx.pendingQuotationId);
                for (List<Map<String, Object>> groupRows : groups.values())
                    result.recordWrite("plating_scheme", groupRows.size());
            } catch (Exception ex) {
                result.recordError(0, "_batch_", ex.getMessage());
            }
        } else {
            for (Map.Entry<String, List<Map<String, Object>>> e : contentOf.entrySet()) {
                try {
                    Map<String, Object> gk = new LinkedHashMap<>();
                    gk.put("system_type", "QUOTE");
                    gk.put("scheme_no", e.getKey());
                    writer.writeVersionedGroup(new VersionedGroupSpec(
                        "plating_scheme", "scheme_version", gk, CONTENT, e.getValue(), null, ctx.pendingQuotationId));
                    result.recordWrite("plating_scheme", e.getValue().size());
                } catch (Exception ex) {
                    result.recordError(0, "_group_", "scheme_no=" + e.getKey() + ": " + ex.getMessage());
                }
            }
        }
        return result;
    }
}
