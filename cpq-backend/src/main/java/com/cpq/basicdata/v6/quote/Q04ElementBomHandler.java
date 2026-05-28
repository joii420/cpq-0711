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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Q04 物料与元素BOM → element_bom 主表 + element_bom_item 子表。
 *
 * <p>字段语义（2026-05-26 修正方案文档 §4 后的最终版）：
 * <ul>
 *   <li>"投入料号" → `material_no`（element_bom 主件料号）</li>
 *   <li>"元素" → `component_no`（element_bom_item 组件料号，存元素代码）</li>
 *   <li>"宏丰料号" 不导入（成品料号，不在元素 BOM 维度内）</li>
 * </ul>
 *
 * <p>分组键：投入料号（即 material_no）。同一投入料号的多个元素行作为一个 element_bom 主表 + N 个子表行。
 * <p>characteristic 默认 "2000"；同一主件料号已存在且元素组成/用量不同时递增 +1（保留方案文档原 versioning 语义）。
 * <p>子表写入策略：INSERT ON CONFLICT (uq_element_bom_item) DO UPDATE — 幂等，不依赖 DELETE 步骤。
 * <p>主表写入策略：INSERT ON CONFLICT (uq_element_bom_v6) DO UPDATE — 消除 SELECT COUNT→persist 假 upsert。
 */
@ApplicationScoped
public class Q04ElementBomHandler implements SheetHandler {

    @Inject EntityManager em;

    @Override public String sheetName() { return "物料与元素BOM"; }

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public SheetImportResult handle(List<SheetRow> rows, ImportContext ctx) {
        SheetImportResult result = new SheetImportResult(sheetName());

        // group by 投入料号（=material_no）
        Map<String, List<SheetRow>> grouped = new LinkedHashMap<>();
        for (SheetRow r : rows) {
            result.totalRows++;
            String inputMat = r.getStr("投入料号");
            if (inputMat == null) { result.recordError(r.rowNo, "投入料号", "为空（应作为主件料号）"); continue; }
            grouped.computeIfAbsent(inputMat, k -> new java.util.ArrayList<>()).add(r);
        }

        for (Map.Entry<String, List<SheetRow>> entry : grouped.entrySet()) {
            String materialNo = entry.getKey();
            List<SheetRow> groupRows = entry.getValue();

            try {
                // 找该主件料号已有的最大 characteristic（按字典序）
                @SuppressWarnings("unchecked")
                List<String> existingChars = em.createNativeQuery(
                        "SELECT characteristic FROM element_bom " +
                        "WHERE system_type='QUOTE' AND customer_no=:c AND material_no=:m " +
                        "ORDER BY characteristic DESC LIMIT 1")
                    .setParameter("c", ctx.customerNo)
                    .setParameter("m", materialNo)
                    .getResultList();
                String maxChar = existingChars.isEmpty() ? null : existingChars.get(0);

                String characteristic;
                if (maxChar == null) {
                    characteristic = "2000";
                } else {
                    // 比对当前 Excel rows 与已有 characteristic 下的 items 指纹，相同则跳过整组
                    String existingFp = fingerprintExisting(ctx.customerNo, materialNo, maxChar);
                    String newFp = fingerprintRows(groupRows);
                    if (existingFp.equals(newFp)) {
                        for (SheetRow r : groupRows) result.successRows++;
                        continue;
                    }
                    try {
                        characteristic = Integer.toString(Integer.parseInt(maxChar) + 1);
                    } catch (NumberFormatException nfe) {
                        characteristic = maxChar + "_NEW";
                    }
                }

                // 主表：INSERT ON CONFLICT (uq_element_bom_v6) DO UPDATE
                // uq_element_bom_v6 = (system_type, customer_no, material_no, characteristic)
                em.createNativeQuery(
                    "INSERT INTO element_bom " +
                    "  (id, system_type, customer_no, bom_type, material_no, characteristic, " +
                    "   updated_at, updated_by) " +
                    "VALUES (gen_random_uuid(), 'QUOTE', :c, 'MATERIAL', :m, :k, NOW(), :u) " +
                    "ON CONFLICT (system_type, customer_no, material_no, characteristic) " +
                    "DO UPDATE SET updated_at = NOW(), updated_by = EXCLUDED.updated_by")
                  .setParameter("c", ctx.customerNo)
                  .setParameter("m", materialNo)
                  .setParameter("k", characteristic)
                  .setParameter("u", ctx.importedBy)
                  .executeUpdate();
                result.recordWrite("element_bom", 1);

                // 子表：INSERT ON CONFLICT (uq_element_bom_item) DO UPDATE
                // uq_element_bom_item = (system_type, customer_no, material_no, characteristic,
                //   COALESCE(seq_no,0), COALESCE(component_no,''), COALESCE(part_no,''))
                for (SheetRow row : groupRows) {
                    em.createNativeQuery(
                        "INSERT INTO element_bom_item " +
                        "  (id, system_type, customer_no, material_no, hf_part_no, characteristic, " +
                        "   seq_no, component_no, content, scrap_rate, composition_qty, " +
                        "   issue_unit, base_qty, updated_at, updated_by) " +
                        "VALUES (gen_random_uuid(), 'QUOTE', :c, :m, :hf, :k, " +
                        "        :seq, :cn, :cont, :scrap, :compQty, " +
                        "        :unit, :baseQty, NOW(), :u) " +
                        "ON CONFLICT (system_type, customer_no, material_no, characteristic, " +
                        "             COALESCE(seq_no,0), COALESCE(component_no,''), COALESCE(part_no,'')) " +
                        "DO UPDATE SET " +
                        "  hf_part_no      = EXCLUDED.hf_part_no, " +
                        "  content         = EXCLUDED.content, " +
                        "  scrap_rate      = EXCLUDED.scrap_rate, " +
                        "  composition_qty = EXCLUDED.composition_qty, " +
                        "  issue_unit      = EXCLUDED.issue_unit, " +
                        "  base_qty        = EXCLUDED.base_qty, " +
                        "  updated_at      = NOW(), " +
                        "  updated_by      = EXCLUDED.updated_by")
                      .setParameter("c", ctx.customerNo)
                      .setParameter("m", materialNo)
                      .setParameter("hf", row.getStr("宏丰料号"))
                      .setParameter("k", characteristic)
                      .setParameter("seq", row.getInt("项次"))
                      .setParameter("cn", row.getStr("元素"))
                      .setParameter("cont", row.getDecimal("组成含量"))
                      .setParameter("scrap", row.getDecimal("损耗率"))
                      .setParameter("compQty", row.getDecimal("毛用量"))
                      .setParameter("unit", row.getStr("毛用量单位"))
                      .setParameter("baseQty", row.getDecimal("净用量"))
                      .setParameter("u", ctx.importedBy)
                      .executeUpdate();
                    result.successRows++;
                    result.recordWrite("element_bom_item", 1);
                }
            } catch (Exception ex) {
                for (SheetRow r : groupRows) result.recordError(r.rowNo, "_group_", ex.getMessage());
            }
        }
        return result;
    }

    /** 指纹比对：组件代码 + 含量 + 毛用量 排序后拼接（与 fingerprintRows 同口径）。 */
    @SuppressWarnings("unchecked")
    private String fingerprintExisting(String customerNo, String materialNo, String characteristic) {
        List<Object[]> data = em.createNativeQuery(
                "SELECT component_no, content, composition_qty FROM element_bom_item " +
                "WHERE system_type='QUOTE' AND customer_no=:c AND material_no=:m AND characteristic=:k " +
                "ORDER BY seq_no")
            .setParameter("c", customerNo)
            .setParameter("m", materialNo)
            .setParameter("k", characteristic)
            .getResultList();
        StringBuilder sb = new StringBuilder();
        for (Object[] r : data) sb.append(r[0]).append('|').append(r[1]).append('|').append(r[2]).append(';');
        return sb.toString();
    }

    private String fingerprintRows(List<SheetRow> rows) {
        StringBuilder sb = new StringBuilder();
        for (SheetRow r : rows) {
            sb.append(nv(r.getStr("元素"))).append('|')
              .append(nv(r.getDecimal("组成含量"))).append('|')
              .append(nv(r.getDecimal("毛用量"))).append(';');
        }
        return sb.toString();
    }

    private static String nv(Object o) {
        return o == null ? "" : (o instanceof BigDecimal ? ((BigDecimal) o).stripTrailingZeros().toPlainString() : o.toString());
    }
}
