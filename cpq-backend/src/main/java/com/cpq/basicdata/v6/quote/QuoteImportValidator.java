package com.cpq.basicdata.v6.quote;

import com.cpq.basicdata.v6.dto.SheetResultDTO;
import com.cpq.basicdata.v6.parser.ImportContext;
import com.cpq.basicdata.v6.parser.SheetImportResult;
import com.cpq.basicdata.v6.parser.SheetRow;
import com.cpq.basicdata.v6.service.PartTypeInferenceService;
import com.cpq.basicdata.v6.service.PartTypeInferenceService.InferResult;
import com.cpq.basicdata.v6.service.PartTypeInferenceService.TypeIndex;
import com.cpq.basicdata.v6.service.ProcessNoResolver;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 报价基础数据导入 Phase 1 校验器（update-0723 Task B7/B8）。
 *
 * <p>解析后的全 sheet 只读校验，<b>零写库</b>：跑 B2 类型推断（含冲突检测）+ 关键键列必填 /
 * 蓝色必填其一 / 材质缺库 / CFG- 前缀 等校验，全部收集不中断，返回按 sheet 分组的错误清单。
 * 全部通过（{@link Outcome#hasErrors()} = false）才进入 Phase 2 写入。
 *
 * <p><b>范围说明</b>：本校验器覆盖 B3~B6 核心改动涉及的 sheet（物料BOM / 物料与元素BOM /
 * 自制加工费 / 组成件其他费用 / 来料三表 / 客户料号关系）的关键键列 + 新增的类型冲突 / 材质缺库
 * 检查，以及 repair-0727 新增的组装加工费 / 组装加工费年降 工序解析（见
 * {@link #validateAssemblyProcess} / {@link #validateAssemblyAnnualDiscount}）；其余既有 sheet
 * （成品其他费用、电镀方案、年降类）的既有 per-row 校验逻辑保留在各自 Phase 2 handler
 * 内 —— 若该阶段仍产生 {@code recordError}，{@link QuoteImportService#writeAll} 会整体回滚
 * （B7 §8.3），故"零写库"目标在这些场景下由事务回滚保证净效果等价，而非 Phase 1 预判。
 * 跨客户串号占号预检本身即为可选项（B8），沿用既有 {@code dontRollbackOn} + Phase 2 回滚机制。
 */
@ApplicationScoped
public class QuoteImportValidator {

    @Inject PartTypeInferenceService typeInferenceService;
    @Inject ProcessNoResolver processNoResolver;

    public static final class Outcome {
        public final Map<String, SheetImportResult> bySheet = new LinkedHashMap<>();
        public TypeIndex typeIndex;
        /** B4：自制加工费 (销售料号, 投入料号原始码或名称) → 工序编号，供 Phase 2 反填 material_bom_item。 */
        public final Map<List<String>, String> selfProcessOperationNo = new LinkedHashMap<>();
        /**
         * repair-0727：组装工序解析结果。key = [sheetName, 销售料号/code, Excel「组装工序」原始值]
         * → 解析出的 (process_no, process_name)。sheetName 第一段区分「组装加工费」(Q14) 与
         * 「组装加工费年降」(Q15) 两个 key 空间，防止撞键；Phase 2 两个 handler 各自用手上的
         * (料号, 原始值) 精确取回，取值前须先 strip() 与本处一致。
         */
        public final Map<List<String>, ProcessNoResolver.Resolved> assemblyProcessNo = new LinkedHashMap<>();

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
        validateIncoming("来料固定加工费", sheetsByName.getOrDefault("来料固定加工费", List.of()), idx, out, false);
        validateIncoming("来料其他费用", sheetsByName.getOrDefault("来料其他费用", List.of()), idx, out, false);
        // task-0730：来料回收折扣新增「值」列，与「回收折扣（%）」并存但必填其一。
        validateIncoming("来料回收折扣", sheetsByName.getOrDefault("来料回收折扣", List.of()), idx, out, true);
        validateCustomerMap(sheetsByName.getOrDefault("客户料号与宏丰料号的关系", List.of()), out);
        validatePlatingCost(sheetsByName.getOrDefault("电镀费用", List.of()), idx, out);

        // repair-0727：组装工序解析结果索引只建一次（process_master 全表载入内存，AC-11 性能要求），
        // 两个 validate 方法共用同一个 Index 实例。
        ProcessNoResolver.Index processIdx = processNoResolver.buildIndex();
        validateAssemblyProcess(sheetsByName.getOrDefault("组装加工费", List.of()), processIdx, out);
        validateAssemblyAnnualDiscount(sheetsByName.getOrDefault("组装加工费年降", List.of()), processIdx, out);

        // 其余 sheet（成品其他费用/电镀方案/年降类/单重/元素回收折扣等）：
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

    /**
     * 来料三表共用的 Phase 1 校验。
     *
     * @param requireValueOrRatio task-0730：为 true 时额外要求「值」与「回收折扣（%）」<b>必填其一</b>
     *        （两者可并存，但不得同时为空）。仅「来料回收折扣」传 true——另两张表的金额列语义不同
     *        （基准值 / 要素值），沿用各自既有校验，不受影响。
     */
    private void validateIncoming(String sheetName, List<SheetRow> rows, TypeIndex idx, Outcome out,
                                  boolean requireValueOrRatio) {
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
            // task-0730：值 / 回收折扣（%）必填其一（可并存，不可同时为空）。
            if (requireValueOrRatio
                    && row.getDecimal("回收折扣") == null && row.getDecimal("值") == null) {
                r.recordError(row.rowNo, "值/回收折扣（%）", "必填其一，不能同时为空");
                continue;
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

    /**
     * repair-0802：电镀费用（Q17 → unit_price）。与 {@link #validateIncoming} 的**关键差异**是
     * 「投入料号」「投入料号名称」**均非必填**——两列皆空是合法输入（Q17 回退为销售料号，语义=
     * 电镀针对成品自身），故此处不得照抄来料三表的「料号与名称均为空」报错分支。
     *
     * <p>预校验的目的只有一个：只填名称时 Q17 会走反查/铸号（**写库**），把"材质查无"这类
     * 必然失败提前到 Phase 1（零写库）拦截，避免拖到 Phase 2 触发整单回滚。
     */
    private void validatePlatingCost(List<SheetRow> rows, TypeIndex idx, Outcome out) {
        SheetImportResult r = result(out, "电镀费用");
        for (SheetRow row : rows) {
            r.totalRows++;
            String materialNo = row.getStr("销售料号", "宏丰料号");
            if (materialNo == null) { r.recordError(row.rowNo, "销售料号", "为空"); continue; }
            String rawNo = row.exact("投入料号");
            String rawName = row.exact("投入料号名称");
            // 投入料号/名称均非必填：两列皆空 → 合法（Q17 回退销售料号）。
            // 只填名称时才需预判材质缺库（与 validateIncoming 同一规则）。
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

    /**
     * repair-0727：一行「进入解析环节」的组装工序（已具备料号）的中间态。{@code rawProcess == null}
     * 表示该行原始值为空且业务上<b>允许</b>为空（仅 T2.4 场景，见 {@link #validateAssemblyAnnualDiscount}）；
     * 此时 {@code resolved} 恒为 {@code null} 且不计入失败。只有 {@code rawProcess != null &&
     * resolved == null} 才代表"填了但解析不到"的真失败。
     */
    private record AssemblyProcessRow(int rowNo, String rawProcess, ProcessNoResolver.Resolved resolved) {}

    /**
     * repair-0727 T2.3：组装加工费（Q14 → capacity）。「销售料号」/「组装工序」均必填（既有必填
     * 校验保留，与 Q14 handler `:54-57` 现有语义一致、文案不并入聚合消息，只是提前到 Phase 1）；
     * 通过必填校验的行按料号分组，交给 {@link #finalizeAssemblyGroups} 做组级判定。
     */
    private void validateAssemblyProcess(List<SheetRow> rows, ProcessNoResolver.Index idx, Outcome out) {
        SheetImportResult r = result(out, "组装加工费");
        Map<String, List<AssemblyProcessRow>> byMaterial = new LinkedHashMap<>();
        for (SheetRow row : rows) {
            r.totalRows++;
            String materialNo = row.getStr("销售料号", "宏丰料号");
            String rawProcess = row.getStr("组装工序", "工序编号");
            if (materialNo == null || rawProcess == null) {
                r.recordError(row.rowNo, "宏丰料号/工序编号", "必填项为空");
                continue;
            }
            Optional<ProcessNoResolver.Resolved> resolved = processNoResolver.resolve(rawProcess, idx);
            byMaterial.computeIfAbsent(materialNo.strip(), k -> new ArrayList<>())
                .add(new AssemblyProcessRow(row.rowNo, rawProcess.strip(), resolved.orElse(null)));
        }
        finalizeAssemblyGroups("组装加工费", byMaterial, r, out);
    }

    /**
     * repair-0727 T2.4：组装加工费年降（Q15 → unit_price.operation_no）。差异于 T2.3：
     * 「组装工序」列<b>允许为空</b>（`operation_no` 允许 NULL，见 Q15 handler 现状）——为空则
     * 跳过解析、不记错，作为"允许为空"的 {@link AssemblyProcessRow} 进入该料号的分组；
     * 只有「填了但解析不了」才计为该料号的失败诱因。
     */
    private void validateAssemblyAnnualDiscount(List<SheetRow> rows, ProcessNoResolver.Index idx, Outcome out) {
        SheetImportResult r = result(out, "组装加工费年降");
        Map<String, List<AssemblyProcessRow>> byMaterial = new LinkedHashMap<>();
        for (SheetRow row : rows) {
            r.totalRows++;
            String materialNo = row.getStr("销售料号", "宏丰料号");
            if (materialNo == null) { r.recordError(row.rowNo, "宏丰料号", "为空"); continue; }
            String rawProcess = row.getStr("组装工序");
            ProcessNoResolver.Resolved resolved = rawProcess == null ? null
                : processNoResolver.resolve(rawProcess, idx).orElse(null);
            byMaterial.computeIfAbsent(materialNo.strip(), k -> new ArrayList<>())
                .add(new AssemblyProcessRow(row.rowNo, rawProcess == null ? null : rawProcess.strip(), resolved));
        }
        finalizeAssemblyGroups("组装加工费年降", byMaterial, r, out);
    }

    /**
     * T2.3/T2.4 共用收尾（2026-07-27 技术总监裁决①）：按料号做<b>组级判定</b>——语义是
     * "整个料号作废"，不是"只有失败的那几行作废"：
     * <ul>
     *   <li>组内存在任一 {@code rawProcess != null && resolved == null}（真失败）→ <b>整个料号</b>
     *       连同其中本来能解析成功的行一并作废：{@code errors} 只追加 <b>1 条</b>聚合消息
     *       （{@code recordError} 内部已 {@code failedRows++}），随后手动补
     *       {@code r.failedRows += 该料号行数 - 1}，使 {@code failedRows} 精确等于该料号在本 sheet
     *       的全部行数；这些行<b>一个都不计入</b> {@code successRows}，也<b>一个都不写入</b>
     *       {@code out.assemblyProcessNo}（不变量：绝不允许「该料号部分工序落库、部分被丢弃」）。</li>
     *   <li>组内全部行都能解析（或按业务规则允许为空）→ 全部行计入 {@code successRows}，
     *       可解析的行写入 {@code out.assemblyProcessNo}。</li>
     * </ul>
     * 由此保证 {@code totalRows == successRows + failedRows} 对本 sheet 恒成立（必填校验失败的行
     * 已在上游各自贡献 1 次 {@code recordError}，不参与本方法的分组与计数，不会被重复计算）。
     */
    private void finalizeAssemblyGroups(String sheetKey, Map<String, List<AssemblyProcessRow>> byMaterial,
                                         SheetImportResult r, Outcome out) {
        for (Map.Entry<String, List<AssemblyProcessRow>> e : byMaterial.entrySet()) {
            String materialNo = e.getKey();
            List<AssemblyProcessRow> materialRows = e.getValue();
            LinkedHashSet<String> unresolvedNames = new LinkedHashSet<>();
            for (AssemblyProcessRow ar : materialRows) {
                if (ar.rawProcess() != null && ar.resolved() == null) unresolvedNames.add(ar.rawProcess());
            }
            if (unresolvedNames.isEmpty()) {
                r.successRows += materialRows.size();
                for (AssemblyProcessRow ar : materialRows) {
                    if (ar.rawProcess() == null) continue;   // 允许为空的行无需登记进 assemblyProcessNo
                    out.assemblyProcessNo.put(List.of(sheetKey, materialNo, ar.rawProcess()), ar.resolved());
                }
            } else {
                int firstFailRowNo = materialRows.stream()
                    .filter(ar -> ar.rawProcess() != null && ar.resolved() == null)
                    .mapToInt(AssemblyProcessRow::rowNo).findFirst().orElseThrow();
                r.recordError(firstFailRowNo, "组装工序",
                    "销售料号「" + materialNo + "」的组装工序「" + String.join("、", unresolvedNames)
                        + "」未在工序主数据中登记，请先在 主数据维护 → 工序 中录入或导入");
                r.failedRows += materialRows.size() - 1;   // recordError 已 +1，这里补齐该料号剩余行数
            }
        }
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }
}
