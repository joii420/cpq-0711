package com.cpq.basicdata.v6.quote;

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
 * Q05 元素回收折扣 → element_bom_item UPDATE 操作。
 *
 * <p>字段语义（2026-05-26 修正方案文档 §5 后的最终版）：
 * <ul>
 *   <li>"投入料号" → `material_no`（匹配键，与 §4 主件料号对齐）</li>
 *   <li>"元素" → `component_no`（匹配键，与 §4 组件料号对齐）</li>
 *   <li>"项次" → `seq_no`（匹配键）</li>
 *   <li>"宏丰料号" 不导入</li>
 *   <li>"回收折扣（%）" → `recovery_discount`（更新值）</li>
 * </ul>
 *
 * <p>characteristic 不在 Excel 列中，取该 (material_no) 下字典序最大的 characteristic（即最新版本）。
 */
@ApplicationScoped
public class Q05ElementRecoveryHandler implements SheetHandler {

    @Inject EntityManager em;

    @Override public String sheetName() { return "元素回收折扣"; }

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public SheetImportResult handle(List<SheetRow> rows, ImportContext ctx) {
        SheetImportResult result = new SheetImportResult(sheetName());
        for (SheetRow row : rows) {
            result.totalRows++;
            try {
                String materialNo = row.getStr("投入料号");
                String componentNo = row.getStr("元素");
                java.math.BigDecimal recoveryDiscount = row.getDecimal("回收折扣");

                // §5 字段表：项次 ❌ 不导入。匹配键仅 (material_no=投入料号, component_no=元素)，取最新 characteristic。
                if (materialNo == null || componentNo == null) {
                    result.recordError(row.rowNo, "投入料号/元素", "匹配键不全");
                    continue;
                }

                int updated = em.createNativeQuery(
                        "UPDATE element_bom_item SET recovery_discount = :rd, updated_at = NOW(), updated_by = :u " +
                        "WHERE system_type='QUOTE' AND customer_no=:c AND material_no=:m " +
                        "  AND component_no=:cn " +
                        "  AND characteristic = (SELECT characteristic FROM element_bom " +
                        "    WHERE system_type='QUOTE' AND customer_no=:c AND material_no=:m " +
                        "    ORDER BY characteristic DESC LIMIT 1)")
                    .setParameter("rd", recoveryDiscount)
                    .setParameter("u", ctx.importedBy)
                    .setParameter("c", ctx.customerNo)
                    .setParameter("m", materialNo)
                    .setParameter("cn", componentNo)
                    .executeUpdate();

                if (updated == 0) {
                    result.recordError(row.rowNo, "_lookup_",
                        String.format("未匹配 element_bom_item (material_no=%s, component_no=%s) - 请先导入物料与元素BOM",
                            materialNo, componentNo));
                } else {
                    result.successRows++;
                    result.recordWrite("element_bom_item", updated);
                }
            } catch (Exception e) {
                result.recordError(row.rowNo, "_row_", e.getMessage());
            }
        }
        return result;
    }
}
