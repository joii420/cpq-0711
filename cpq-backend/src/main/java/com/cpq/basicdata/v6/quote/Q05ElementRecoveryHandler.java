package com.cpq.basicdata.v6.quote;

import com.cpq.basicdata.v6.parser.ImportContext;
import com.cpq.basicdata.v6.parser.SheetHandler;
import com.cpq.basicdata.v6.parser.SheetImportResult;
import com.cpq.basicdata.v6.parser.SheetRow;
import com.cpq.basicdata.v6.repository.ElementRecoveryDiscountRepository;
import com.cpq.basicdata.v6.repository.ElementRecoveryDiscountRepository.Update;
import com.cpq.basicdata.v6.service.MaterialNoResolver;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Q05 元素回收折扣 → element_bom_item UPDATE 操作。
 *
 * <p>字段语义（2026-05-26 修正方案文档 §5 后的最终版）：
 * <ul>
 *   <li>"投入料号" → `material_no`（匹配键，与 §4 主件料号对齐）</li>
 *   <li>"元素" → `component_no`（匹配键，与 §4 组件料号对齐）</li>
 *   <li>"项次" 不导入（§5 明细口径，**不作匹配键**）</li>
 *   <li>"宏丰料号" 不导入</li>
 *   <li>"回收折扣（%）" → `recovery_discount`（更新值）</li>
 * </ul>
 *
 * <p>Task 8（决策⑤）：按 **2 键 (material_no, component_no) + is_current=true** 匹配当前生效版本组的
 * element_bom_item 行批量 UPDATE recovery_discount；不新增版本、不翻转 is_current。
 */
@ApplicationScoped
public class Q05ElementRecoveryHandler implements SheetHandler {

    @Inject ElementRecoveryDiscountRepository repo;
    @Inject MaterialNoResolver materialNoResolver;

    @Override public String sheetName() { return "元素回收折扣"; }

    /** 一条已通过校验的更新意图（保留 rowNo 供逐行报告）。 */
    private record ValidRow(int rowNo, String materialNo, String componentNo, BigDecimal rd) {}

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public SheetImportResult handle(List<SheetRow> rows, ImportContext ctx) {
        SheetImportResult result = new SheetImportResult(sheetName());
        MaterialNoResolver.BatchState batch = new MaterialNoResolver.BatchState();

        // §P2-Q05 阶段1：逐行解析+校验(resolveMatchOnly 顺序/缓存不变)，收集有效行。
        List<ValidRow> valid = new ArrayList<>();
        for (SheetRow row : rows) {
            result.totalRows++;
            try {
                String inputName = row.exact("投入料号名称");
                String materialNo = materialNoResolver.resolveMatchOnly(row.exact("投入料号"), inputName, batch);
                String componentNo = row.getStr("元素");
                BigDecimal recoveryDiscount = row.getDecimal("回收折扣");
                // §5 更新型：只按名匹配不生成。料号为空且按名称查不到 → 记错误跳过。
                if (materialNo == null || componentNo == null) {
                    result.recordError(row.rowNo, "投入料号/元素",
                        materialNo == null
                            ? "投入料号为空，且按名称[" + (inputName == null ? "" : inputName) + "]在料号表查无对应料号"
                            : "匹配键不全");
                    continue;
                }
                valid.add(new ValidRow(row.rowNo, materialNo, componentNo, recoveryDiscount));
            } catch (Exception e) {
                result.recordError(row.rowNo, "_row_", e.getMessage());
            }
        }

        if (!valid.isEmpty()) {
            // §P2-Q05 阶段2：一次 tuple-IN 取每键 is_current 匹配行数(=逐行 updated 计数)；
            // 去重(末值胜，对齐逐行后写覆盖)后一条 UPDATE…FROM(VALUES) 批量写。
            Map<String, Update> dedup = new LinkedHashMap<>();   // 末值胜
            for (ValidRow vr : valid) {
                dedup.put(ElementRecoveryDiscountRepository.key(vr.materialNo(), vr.componentNo()),
                    new Update(vr.materialNo(), vr.componentNo(), vr.rd()));
            }
            List<String[]> distinctKeys = new ArrayList<>();
            for (Update u : dedup.values()) distinctKeys.add(new String[]{u.materialNo(), u.componentNo()});

            Map<String, Integer> matchCount = repo.countCurrentMatches(ctx.customerNo, distinctKeys);
            repo.batchUpdate(ctx.customerNo, new ArrayList<>(dedup.values()), ctx.importedBy);

            // §P2-Q05 阶段3：逐行报告(与原逐行 updated==0 错误 / successRows / recordWrite 计数逐位一致)。
            for (ValidRow vr : valid) {
                int updated = matchCount.getOrDefault(
                    ElementRecoveryDiscountRepository.key(vr.materialNo(), vr.componentNo()), 0);
                if (updated == 0) {
                    result.recordError(vr.rowNo(), "_lookup_",
                        String.format("未匹配 element_bom_item (material_no=%s, component_no=%s) - 请先导入物料与元素BOM",
                            vr.materialNo(), vr.componentNo()));
                } else {
                    result.successRows++;
                    result.recordWrite("element_bom_item", updated);
                }
            }
        }
        return result;
    }
}
