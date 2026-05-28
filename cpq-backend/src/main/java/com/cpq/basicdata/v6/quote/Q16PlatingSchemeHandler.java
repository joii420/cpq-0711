package com.cpq.basicdata.v6.quote;

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

/** Q16 电镀方案 → plating_scheme。报价版本含 source_url / source_name / fetch_rule 三列。 */
@ApplicationScoped
public class Q16PlatingSchemeHandler implements SheetHandler {

    @Inject EntityManager em;

    @Override public String sheetName() { return "电镀方案"; }

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public SheetImportResult handle(List<SheetRow> rows, ImportContext ctx) {
        SheetImportResult result = new SheetImportResult(sheetName());
        for (SheetRow row : rows) {
            result.totalRows++;
            try {
                String schemeNo = row.getStr("方案编号");
                String version = row.getStr("版本");
                Integer seqNo = row.getInt("项次");
                String element = row.getStr("电镀元素名称");
                BigDecimal platingArea = row.getDecimal("电镀面积");
                BigDecimal thickness = row.getDecimal("镀层厚度");
                if (schemeNo == null || version == null || seqNo == null || element == null) {
                    result.recordError(row.rowNo, "方案编号/版本/项次/电镀元素", "必填项为空");
                    continue;
                }

                em.createNativeQuery(
                        "INSERT INTO plating_scheme (scheme_no, scheme_version, seq_no, plating_element, " +
                        "  plating_method, surface_area, plating_area, plating_thickness, plating_requirement, " +
                        "  element_usage, source_url, source_name, fetch_rule, created_at, updated_at, updated_by) " +
                        "VALUES (:sn, :sv, :seq, :el, :pm, :sa, :pa, :pt, :req, :eu, :su, :snm, :fr, NOW(), NOW(), :ub) " +
                        "ON CONFLICT (scheme_no, scheme_version, seq_no) DO UPDATE SET " +
                        "  plating_element = EXCLUDED.plating_element, " +
                        "  plating_method = COALESCE(EXCLUDED.plating_method, plating_scheme.plating_method), " +
                        "  surface_area = COALESCE(EXCLUDED.surface_area, plating_scheme.surface_area), " +
                        "  plating_area = COALESCE(EXCLUDED.plating_area, plating_scheme.plating_area), " +
                        "  plating_thickness = COALESCE(EXCLUDED.plating_thickness, plating_scheme.plating_thickness), " +
                        "  plating_requirement = COALESCE(EXCLUDED.plating_requirement, plating_scheme.plating_requirement), " +
                        "  element_usage = COALESCE(EXCLUDED.element_usage, plating_scheme.element_usage), " +
                        "  source_url = COALESCE(EXCLUDED.source_url, plating_scheme.source_url), " +
                        "  source_name = COALESCE(EXCLUDED.source_name, plating_scheme.source_name), " +
                        "  fetch_rule = COALESCE(EXCLUDED.fetch_rule, plating_scheme.fetch_rule), " +
                        "  updated_at = NOW(), updated_by = EXCLUDED.updated_by")
                    .setParameter("sn", schemeNo)
                    .setParameter("sv", version)
                    .setParameter("seq", seqNo)
                    .setParameter("el", element)
                    .setParameter("pm", "电镀")
                    .setParameter("sa", platingArea != null ? platingArea : BigDecimal.ZERO)
                    .setParameter("pa", platingArea)
                    .setParameter("pt", thickness != null ? thickness : BigDecimal.ZERO)
                    .setParameter("req", row.getStr("电镀要求"))
                    .setParameter("eu", BigDecimal.ZERO) // 由系统按 plating_area * thickness * density 计算，初始 0
                    .setParameter("su", row.getStr("元素单价来源网站网址", "网址"))
                    .setParameter("snm", row.getStr("元素单价来源网站名称", "网站名称"))
                    .setParameter("fr", row.getStr("元素单价抓取规则", "取用规则"))
                    .setParameter("ub", ctx.importedBy)
                    .executeUpdate();
                result.successRows++;
                result.recordWrite("plating_scheme", 1);
            } catch (Exception e) {
                result.recordError(row.rowNo, "_row_", e.getMessage());
            }
        }
        return result;
    }
}
