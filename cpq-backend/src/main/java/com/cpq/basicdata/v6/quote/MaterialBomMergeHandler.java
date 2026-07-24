package com.cpq.basicdata.v6.quote;

import com.cpq.basicdata.v6.parser.ImportContext;
import com.cpq.basicdata.v6.parser.SheetHandler;
import com.cpq.basicdata.v6.parser.SheetImportResult;
import com.cpq.basicdata.v6.parser.SheetRow;
import com.cpq.basicdata.v6.repository.MaterialMasterRepository;
import com.cpq.basicdata.v6.service.MaterialNoResolver;
import com.cpq.basicdata.v6.service.MaterialNoUnresolvableException;
import com.cpq.basicdata.v6.service.PartTypeInferenceService;
import com.cpq.basicdata.v6.service.PartTypeInferenceService.InferResult;
import com.cpq.basicdata.v6.service.PartTypeInferenceService.TypeIndex;
import com.cpq.basicdata.v6.service.QuoteMaterialNoAllocator;
import com.cpq.basicdata.v6.versioning.VersionedV6Writer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 物料BOM 单表三态导入（update-0723 Task B3/B4/B6 重构）。
 *
 * <p>新模板删除「组成件BOM」sheet，物料BOM 单表承载材质/零件/外购件三态：投入料号列可能是
 * 材质（RECIPE）、零件（ASSEMBLY）或外购件（OUTSOURCED），列本身不声明类型 —— 由 Phase 1
 * 预建的 {@link PartTypeInferenceService.TypeIndex}（ctx.sharedCache "partTypeIndex"）按
 * 跨 sheet 权威集 + 库内兜底推断（B2）。
 *
 * <ul>
 *   <li><b>RECIPE</b>：原始码直接作 component_no；只认 Excel 原始码/名称，按名/码校验存在于
 *       material_recipe（不存在 Phase 1 已拦截）；<b>不 resolve/不铸号/不登记 material_master</b>
 *       （沿用 repair-2 决策 A/B，防跨客户串号）。</li>
 *   <li><b>ASSEMBLY/OUTSOURCED</b>：走 {@link MaterialNoResolver}（有码直接占号落库，不另发号；
 *       只有名称按名查/发号，U2），登记 material_master（material_type=零件/外购件，B6）。</li>
 * </ul>
 *
 * <p><b>B4</b>：ASSEMBLY 子行按 (销售料号, 投入料号原始码或名称) 命中 Phase 1 预扫的
 * 「自制加工费」工序编号 map，反填 operation_no（组成件BOM 删除后的工艺路线断供修复）。
 * <p><b>issue_unit（U5）</b>：RECIPE 行沿用「重量单位」；ASSEMBLY/OUTSOURCED 行兜底 "PCS"
 * （新模板「组成单位」列已删除）。
 */
@ApplicationScoped
public class MaterialBomMergeHandler implements SheetHandler {

    @Inject VersionedV6Writer writer;
    @Inject MaterialMasterRepository materialMasterRepo;
    @Inject MaterialNoResolver materialNoResolver;

    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "cpq.v6import-setbased-writer", defaultValue = "false")
    boolean setBased;

    @Override public String sheetName() { return "物料BOM"; }

    /** 子表内容列。三态统一：characteristic 必须参与内容比较，否则仅类型变化会被判"无变化"静默丢失。 */
    private static final List<String> CHILD_CONTENT = List.of(
        "seq_no", "component_no", "component_usage_type", "composition_qty",
        "base_qty", "issue_unit", "scrap_rate", "defect_rate",
        "operation_no", "item_seq",
        "rough_weight", "net_weight", "weight_unit",
        "characteristic");

    @Override
    @Transactional(Transactional.TxType.MANDATORY)
    public SheetImportResult handle(List<SheetRow> rows, ImportContext ctx) {
        return merge(rows, ctx);
    }

    /**
     * 单 sheet 三态合并入口（B3）。历史双参签名 {@code merge(materialRows, assemblyRows, ctx)}
     * 已随「组成件BOM」sheet 删除而废弃——新模板单 sheet 承载三态，故改为单参。
     */
    @Transactional(Transactional.TxType.MANDATORY)
    public SheetImportResult merge(List<SheetRow> rows, ImportContext ctx) {
        SheetImportResult result = new SheetImportResult(sheetName());

        // R2：全 handler 共享一个 BatchState（见 MaterialNoResolver#batchStateFor），防止同一
        // 物理件在物料BOM/自制加工费/组成件其他费用/来料三表间被二次发号。
        MaterialNoResolver.BatchState batch = MaterialNoResolver.batchStateFor(ctx);

        TypeIndex typeIndex = (TypeIndex) ctx.sharedCache.get("partTypeIndex");
        @SuppressWarnings("unchecked")
        Map<List<String>, String> opNoMap = (Map<List<String>, String>) ctx.sharedCache
            .getOrDefault("selfProcessOperationNo", Map.of());

        // material_no -> component_no -> content（同料号同投入料号只留最后一行，与历史行为一致）
        Map<String, Map<String, Map<String, Object>>> childByMat = new LinkedHashMap<>();
        Map<String, String[]> mmAcc = new LinkedHashMap<>();   // material_master 延后批量（首个非空胜）

        for (SheetRow row : rows) {
            result.totalRows++;
            String materialNo = row.getStr("销售料号", "宏丰料号");
            if (materialNo == null) { result.recordError(row.rowNo, "销售料号", "为空"); continue; }
            if (isCfg(materialNo)) {
                result.recordError(row.rowNo, "销售料号", "禁止导入系统生成料号(CFG- 前缀): " + materialNo);
                continue;
            }

            String rawNo = row.exact("投入料号");
            String rawName = row.exact("投入料号名称");
            if (isBlank(rawNo) && isBlank(rawName)) {
                result.recordError(row.rowNo, "投入料号", "料号与名称均为空");
                continue;
            }

            InferResult infer = typeIndex != null ? typeIndex.infer(rawNo, rawName)
                : new InferResult(PartTypeInferenceService.ASSEMBLY, PartTypeInferenceService.Source.DEFAULT);
            String characteristic = infer.characteristic();

            String componentNo;
            if (PartTypeInferenceService.RECIPE.equals(characteristic)) {
                componentNo = typeIndex != null ? typeIndex.resolveRecipeCode(rawNo, rawName) : rawNo;
                if (componentNo == null) {
                    String shown = !isBlank(rawNo) ? rawNo : rawName;
                    result.recordError(row.rowNo, "投入料号", "未找到材质「" + shown + "」");
                    continue;
                }
                // 材质：原始码，不 resolve/不铸号/不登记 material_master（repair-2 决策 A/B）。
            } else {
                try {
                    componentNo = materialNoResolver.resolve(rawNo, rawName, batch);
                } catch (MaterialNoUnresolvableException ex) {
                    result.recordError(row.rowNo, "投入料号", "料号与名称均为空"); continue;
                } catch (QuoteMaterialNoAllocator.CrossCustomerQuoteNoException ex) {
                    result.recordError(row.rowNo, "投入料号", "报价料号跨客户串号"); continue;
                }
                String materialType = PartTypeInferenceService.OUTSOURCED.equals(characteristic) ? "外购件" : "零件";
                accMaterialMaster(mmAcc, componentNo, rawName, materialType);
                result.recordWrite("material_master", 1);
            }

            // B4：ASSEMBLY 子行按 (销售料号, 投入料号原始码/名称) 命中自制加工费工序编号反填。
            String operationNo = null;
            if (PartTypeInferenceService.ASSEMBLY.equals(characteristic) && !opNoMap.isEmpty()) {
                if (!isBlank(rawNo)) operationNo = opNoMap.get(Arrays.asList(materialNo.strip(), rawNo.strip()));
                if (operationNo == null && !isBlank(rawName)) {
                    operationNo = opNoMap.get(Arrays.asList(materialNo.strip(), rawName.strip()));
                }
            }

            String weightUnit = row.getStr("重量单位");
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("seq_no", row.getInt("项次"));
            c.put("component_no", componentNo);
            c.put("component_usage_type", labelOnly(row.getStr("产出料号类型")));
            c.put("composition_qty", row.getDecimal("组成数量"));
            c.put("rough_weight", row.getDecimal("材料毛重", "毛重"));
            c.put("net_weight",   row.getDecimal("材料净重", "净重"));
            c.put("weight_unit",  weightUnit);
            // issue_unit（U5）：材质沿用重量单位；零件/外购件兜底 PCS（「组成单位」列已删除）。
            c.put("issue_unit", PartTypeInferenceService.RECIPE.equals(characteristic) ? weightUnit : "PCS");
            c.put("scrap_rate", row.getDecimal("损耗率"));
            c.put("defect_rate", row.getDecimal("不良率"));
            c.put("operation_no", operationNo);
            c.put("characteristic", characteristic);
            childByMat.computeIfAbsent(materialNo, k -> new LinkedHashMap<>())
                      .put(componentNo, c);
            result.successRows++;
        }

        if (!mmAcc.isEmpty()) {
            List<MaterialMasterRepository.NameTypeRow> mmRows = new ArrayList<>(mmAcc.size());
            for (Map.Entry<String, String[]> e : mmAcc.entrySet()) {
                mmRows.add(new MaterialMasterRepository.NameTypeRow(e.getKey(), e.getValue()[0], e.getValue()[1]));
            }
            materialMasterRepo.upsertBatchNameType(mmRows, ctx.importedBy, true, ctx.pendingQuotationId);
        }

        if (setBased) {
            // 按 masterFixed(bom_type+characteristic) 组合分区，每个分区一次批量调用（与其它 handler 一致）。
            Map<Map<String, Object>, List<VersionedV6Writer.MasterDetailItem>> byFixed = new LinkedHashMap<>();
            for (Map.Entry<String, Map<String, Map<String, Object>>> e : childByMat.entrySet()) {
                String materialNo = e.getKey();
                List<Map<String, Object>> childRows = new ArrayList<>(e.getValue().values());
                boolean isAssembly = childRows.stream().anyMatch(r ->
                    "ASSEMBLY".equals(r.get("characteristic")) || "OUTSOURCED".equals(r.get("characteristic")));
                String targetChar = isAssembly ? "ASSEMBLY" : null;
                String bomType = isAssembly ? "ASSEMBLY" : "MATERIAL";

                Map<String, Object> masterGk = new LinkedHashMap<>();
                masterGk.put("system_type", "QUOTE");
                masterGk.put("customer_no", ctx.customerNo);
                masterGk.put("material_no", materialNo);
                Map<String, Object> masterFixed = new LinkedHashMap<>();
                masterFixed.put("bom_type", bomType);
                masterFixed.put("characteristic", targetChar);
                Map<String, Object> childGk = new LinkedHashMap<>(masterGk);
                byFixed.computeIfAbsent(masterFixed, k -> new ArrayList<>())
                       .add(new VersionedV6Writer.MasterDetailItem(masterGk, childGk, childRows));
            }
            for (Map.Entry<Map<String, Object>, List<VersionedV6Writer.MasterDetailItem>> p : byFixed.entrySet()) {
                List<VersionedV6Writer.MasterDetailItem> items = p.getValue();
                try {
                    writer.writeVersionedMasterDetails(
                        "material_bom", "bom_version", p.getKey(),
                        "material_bom_item", "bom_version", CHILD_CONTENT, items, ctx.pendingQuotationId);
                    for (VersionedV6Writer.MasterDetailItem it : items) {
                        result.recordWrite("material_bom", 1);
                        result.recordWrite("material_bom_item", it.childRows.size());
                    }
                } catch (Exception ex) {
                    result.recordError(0, "_batch_", ex.getMessage());
                }
            }
        } else {
            for (Map.Entry<String, Map<String, Map<String, Object>>> e : childByMat.entrySet()) {
                String materialNo = e.getKey();
                try {
                    List<Map<String, Object>> childRows = new ArrayList<>(e.getValue().values());
                    boolean isAssembly = childRows.stream().anyMatch(r ->
                        "ASSEMBLY".equals(r.get("characteristic")) || "OUTSOURCED".equals(r.get("characteristic")));
                    String targetChar = isAssembly ? "ASSEMBLY" : null;
                    String bomType = isAssembly ? "ASSEMBLY" : "MATERIAL";

                    Map<String, Object> masterGk = new LinkedHashMap<>();
                    masterGk.put("system_type", "QUOTE");
                    masterGk.put("customer_no", ctx.customerNo);
                    masterGk.put("material_no", materialNo);
                    Map<String, Object> masterFixed = new LinkedHashMap<>();
                    masterFixed.put("bom_type", bomType);
                    masterFixed.put("characteristic", targetChar);
                    Map<String, Object> childGk = new LinkedHashMap<>(masterGk);
                    writer.writeVersionedMasterDetail(
                        "material_bom", "bom_version", masterGk, masterFixed,
                        "material_bom_item", "bom_version", childGk, CHILD_CONTENT, childRows, ctx.pendingQuotationId);
                    result.recordWrite("material_bom", 1);
                    result.recordWrite("material_bom_item", childRows.size());
                } catch (Exception ex) {
                    result.recordError(0, "_group_", "material_no=" + materialNo + ": " + ex.getMessage());
                }
            }
        }
        return result;
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }

    private static boolean isCfg(String materialNo) {
        return materialNo != null && materialNo.startsWith("CFG-");
    }

    /**
     * 累积 material_master 的 name/type（按 material_no 去重，<b>首个非空胜</b>）。
     */
    private static void accMaterialMaster(Map<String, String[]> acc, String no, String name, String type) {
        String[] cur = acc.get(no);
        if (cur == null) {
            acc.put(no, new String[]{name, type});
        } else {
            if (cur[0] == null) cur[0] = name;
            if (cur[1] == null) cur[1] = type;
        }
    }

    /**
     * 「产出料号类型」只存汉字：剥离前导数字 + 紧随的一个分隔符，保留其后标签。
     * "1.银点类"→"银点类"；"2.非银点类"→"非银点类"；"组成件"→"组成件"；"1"→"1"；null→null。
     */
    static String labelOnly(String s) {
        if (s == null) return null;
        String t = s.trim();
        int i = 0;
        while (i < t.length() && t.charAt(i) >= '0' && t.charAt(i) <= '9') i++;
        if (i > 0 && i < t.length()) {
            char sep = t.charAt(i);
            if (sep == '.' || sep == '。' || sep == '、' || sep == '．'
                    || sep == '/' || sep == '／' || sep == ' ' || sep == '\t') i++;
        }
        String rest = t.substring(i).trim();
        return rest.isEmpty() ? t : rest;
    }
}
