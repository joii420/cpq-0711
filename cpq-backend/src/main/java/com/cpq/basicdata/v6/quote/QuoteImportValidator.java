package com.cpq.basicdata.v6.quote;

import com.cpq.basicdata.v6.dto.SheetResultDTO;
import com.cpq.basicdata.v6.parser.ImportContext;
import com.cpq.basicdata.v6.parser.SheetImportResult;
import com.cpq.basicdata.v6.parser.SheetRow;
import com.cpq.basicdata.v6.service.PartTypeInferenceService;
import com.cpq.basicdata.v6.service.PartTypeInferenceService.InferResult;
import com.cpq.basicdata.v6.service.PartTypeInferenceService.TypeIndex;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 报价基础数据导入 Phase 1 校验器（update-0723 Task B7/B8）。
 *
 * <p>解析后的全 sheet 只读校验，<b>零写库</b>：跑 B2 类型推断（含冲突检测）+ 关键键列必填 /
 * 蓝色必填其一 / 材质缺库 / CFG- 前缀 等校验，全部收集不中断，返回按 sheet 分组的错误清单。
 * 全部通过（{@link Outcome#hasErrors()} = false）才进入 Phase 2 写入。
 *
 * <p><b>范围说明</b>：本校验器覆盖 B3~B6 核心改动涉及的 sheet（物料BOM / 物料与元素BOM /
 * 自制加工费 / 组成件其他费用 / 来料三表 / 客户料号关系）的关键键列 + 新增的类型冲突 / 材质缺库
 * 检查；其余既有 sheet（成品其他费用、组装加工费、电镀方案/费用、年降类）的既有 per-row 校验
 * 逻辑保留在各自 Phase 2 handler 内 —— 若该阶段仍产生 {@code recordError}，
 * {@link QuoteImportService#writeAll} 会整体回滚（B7 §8.3），故"零写库"目标在这些场景下由
 * 事务回滚保证净效果等价，而非 Phase 1 预判。跨客户串号占号预检本身即为可选项（B8），
 * 沿用既有 {@code dontRollbackOn} + Phase 2 回滚机制。
 */
@ApplicationScoped
public class QuoteImportValidator {

    @Inject PartTypeInferenceService typeInferenceService;

    public static final class Outcome {
        public final Map<String, SheetImportResult> bySheet = new LinkedHashMap<>();
        public TypeIndex typeIndex;
        /** B4：自制加工费 (销售料号, 投入料号原始码或名称) → 工序编号，供 Phase 2 反填 material_bom_item。 */
        public final Map<List<String>, String> selfProcessOperationNo = new LinkedHashMap<>();

        public boolean hasErrors() {
            for (SheetImportResult r : bySheet.values()) if (r.failedRows > 0) return true;
            return false;
        }

        public List<SheetResultDTO> toDtos() {
            List<SheetResultDTO> out = new ArrayList<>();
            for (SheetImportResult r : bySheet.values()) out.add(SheetResultDTO.from(r));
            return out;
        }
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public Outcome validate(Map<String, List<SheetRow>> sheetsByName, ImportContext ctx) {
        Outcome out = new Outcome();
        TypeIndex idx = typeInferenceService.buildIndex(sheetsByName);
        out.typeIndex = idx;

        // 类型冲突（B2 §3.2 / U6 洞①）先落各自 sheet 的错误桶。
        for (PartTypeInferenceService.ConflictError ce : idx.conflicts()) {
            result(out, ce.sheetName()).recordError(ce.rowNo(), ce.column(), ce.message());
        }

        validateMaterialBom(sheetsByName.getOrDefault("物料BOM", List.of()), idx, out);
        validateElementBom(sheetsByName.getOrDefault("物料与元素BOM", List.of()), out);
        validateSelfProcessFee(sheetsByName.getOrDefault("自制加工费", List.of()), out);
        validateComponentOtherFee(sheetsByName.getOrDefault("组成件其他费用", List.of()), out);
        validateIncoming("来料固定加工费", sheetsByName.getOrDefault("来料固定加工费", List.of()), idx, out);
        validateIncoming("来料其他费用", sheetsByName.getOrDefault("来料其他费用", List.of()), idx, out);
        validateIncoming("来料回收折扣", sheetsByName.getOrDefault("来料回收折扣", List.of()), idx, out);
        validateCustomerMap(sheetsByName.getOrDefault("客户料号与宏丰料号的关系", List.of()), out);

        // 其余 sheet（成品其他费用/组装加工费/电镀方案/电镀费用/年降类/单重/元素回收折扣等）：
        // 仅计数不深校验——U9 规则不改，既有 Phase 2 handler 的 recordError 仍会触发整单回滚。
        for (Map.Entry<String, List<SheetRow>> e : sheetsByName.entrySet()) {
            if (out.bySheet.containsKey(e.getKey())) continue;
            SheetImportResult r = result(out, e.getKey());
            r.totalRows = e.getValue().size();
            r.successRows = e.getValue().size();
        }
        return out;
    }

    private SheetImportResult result(Outcome out, String sheetName) {
        return out.bySheet.computeIfAbsent(sheetName, SheetImportResult::new);
    }

    private void validateMaterialBom(List<SheetRow> rows, TypeIndex idx, Outcome out) {
        SheetImportResult r = result(out, "物料BOM");
        for (SheetRow row : rows) {
            r.totalRows++;
            String materialNo = row.getStr("销售料号", "宏丰料号");
            if (materialNo == null) { r.recordError(row.rowNo, "销售料号", "为空"); continue; }
            if (materialNo.startsWith("CFG-")) {
                r.recordError(row.rowNo, "销售料号", "禁止导入系统生成料号(CFG- 前缀): " + materialNo);
                continue;
            }
            String rawNo = row.exact("投入料号");
            String rawName = row.exact("投入料号名称");
            if (isBlank(rawNo) && isBlank(rawName)) {
                r.recordError(row.rowNo, "投入料号", "料号与名称均为空");
                continue;
            }
            InferResult infer = idx.infer(rawNo, rawName);
            if (PartTypeInferenceService.RECIPE.equals(infer.characteristic())
                    && idx.resolveRecipeCode(rawNo, rawName) == null) {
                String shown = !isBlank(rawNo) ? rawNo : rawName;
                r.recordError(row.rowNo, "投入料号", "未找到材质「" + shown + "」");
                continue;
            }
            r.successRows++;
        }
    }

    private void validateElementBom(List<SheetRow> rows, Outcome out) {
        SheetImportResult r = result(out, "物料与元素BOM");
        for (SheetRow row : rows) {
            r.totalRows++;
            String materialNo = row.getStr("销售料号");
            if (materialNo == null) { r.recordError(row.rowNo, "销售料号", "为空"); continue; }
            r.successRows++;
        }
    }

    private void validateSelfProcessFee(List<SheetRow> rows, Outcome out) {
        SheetImportResult r = result(out, "自制加工费");
        for (SheetRow row : rows) {
            r.totalRows++;
            String materialNo = row.getStr("销售料号", "宏丰料号");
            if (materialNo == null) { r.recordError(row.rowNo, "销售料号", "为空"); continue; }
            r.successRows++;

            // B4：工序反填 map。同键多工序默认取首条(数据实况取首条)。
            String rawNo = row.exact("投入料号");
            String rawName = row.exact("投入料号名称");
            String opNo = row.getStr("工序编号");
            if (opNo == null) continue;
            if (!isBlank(rawNo)) {
                out.selfProcessOperationNo.putIfAbsent(Arrays.asList(materialNo.strip(), rawNo.strip()), opNo);
            }
            if (!isBlank(rawName)) {
                out.selfProcessOperationNo.putIfAbsent(Arrays.asList(materialNo.strip(), rawName.strip()), opNo);
            }
        }
    }

    private void validateComponentOtherFee(List<SheetRow> rows, Outcome out) {
        SheetImportResult r = result(out, "组成件其他费用");
        for (SheetRow row : rows) {
            r.totalRows++;
            String materialNo = row.getStr("销售料号", "宏丰料号");
            String costType = row.getStr("要素名称");
            if (materialNo == null || costType == null) {
                r.recordError(row.rowNo, "销售料号/要素名称", "必填项为空");
                continue;
            }
            String rawNo = row.exact("组成件料号");
            String rawName = row.exact("组成件名称");
            if (isBlank(rawNo) && isBlank(rawName)) {
                r.recordError(row.rowNo, "组成件料号", "料号与名称均为空");
                continue;
            }
            r.successRows++;
        }
    }

    private void validateIncoming(String sheetName, List<SheetRow> rows, TypeIndex idx, Outcome out) {
        SheetImportResult r = result(out, sheetName);
        for (SheetRow row : rows) {
            r.totalRows++;
            String materialNo = row.getStr("销售料号", "宏丰料号", "成品料号");
            if (materialNo == null) { r.recordError(row.rowNo, "销售料号", "为空"); continue; }
            String rawNo = row.exact("投入料号");
            String rawName = row.exact("投入料号名称");
            if (isBlank(rawNo) && isBlank(rawName)) {
                r.recordError(row.rowNo, "投入料号", "料号与名称均为空");
                continue;
            }
            // U10：只填名称时补名称反查；材质定型 + 查无 → 报错。
            if (isBlank(rawNo) && !isBlank(rawName)) {
                InferResult infer = idx.infer(null, rawName);
                if (PartTypeInferenceService.RECIPE.equals(infer.characteristic())
                        && idx.resolveRecipeCode(null, rawName) == null) {
                    r.recordError(row.rowNo, "投入料号名称", "未找到材质「" + rawName + "」");
                    continue;
                }
            }
            r.successRows++;
        }
    }

    private void validateCustomerMap(List<SheetRow> rows, Outcome out) {
        SheetImportResult r = result(out, "客户料号与宏丰料号的关系");
        for (SheetRow row : rows) {
            r.totalRows++;
            String materialNo = row.getStr("销售料号", "报价料号", "宏丰料号");
            String customerProductNo = row.getStr("客户产品编号");
            if (materialNo == null || customerProductNo == null) {
                r.recordError(row.rowNo, "报价料号/客户产品编号", "必填项为空");
                continue;
            }
            r.successRows++;
        }
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }
}
