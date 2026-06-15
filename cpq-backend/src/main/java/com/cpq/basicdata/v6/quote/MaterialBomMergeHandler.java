package com.cpq.basicdata.v6.quote;

import com.cpq.basicdata.v6.parser.ImportContext;
import com.cpq.basicdata.v6.parser.SheetImportResult;
import com.cpq.basicdata.v6.parser.SheetRow;
import com.cpq.basicdata.v6.repository.MaterialMasterRepository;
import com.cpq.basicdata.v6.versioning.VersionedV6Writer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 物料BOM ⇄ 组成件BOM 同料号去重合并（V3.2）。
 *
 * <p>解析「物料BOM」(MATERIAL/characteristic=NULL) + 「组成件BOM」(ASSEMBLY) 两 sheet，
 * 按 material_no 在单一事务内汇总两表子行（合并键=component_no；characteristic 与 seq_no 不作为合并键；
 * seq_no 仍作为内容列写入，冲突取组成件值），
 * 组成件优先判类型/取冲突值；写入前 FLIP 反向 characteristic 旧当前行为 is_current=false（保留历史，
 * 依赖 V293 子表版本化）；每料号单次 {@link VersionedV6Writer#writeVersionedMasterDetail}。
 *
 * <p>替代原 Q03/Q12 各写各的（会产生 NULL + ASSEMBLY 双 current 行）。material_master upsert
 * 副作用（物料BOM 投入料号）保留。CFG- 前缀料号拒绝导入（封死选配料号回填）。
 */
@ApplicationScoped
public class MaterialBomMergeHandler {

    @Inject VersionedV6Writer writer;
    @Inject MaterialMasterRepository materialMasterRepo;
    @Inject com.cpq.basicdata.v6.service.MaterialNoResolver materialNoResolver;

    /** 合并后子表内容列 = 物料BOM ∪ 组成件BOM。 */
    private static final List<String> CHILD_CONTENT = List.of(
        "seq_no", "component_no", "component_usage_type", "composition_qty",
        "base_qty", "issue_unit", "scrap_rate", "defect_rate",
        "operation_no", "item_seq");

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public SheetImportResult merge(List<SheetRow> materialRows, List<SheetRow> assemblyRows, ImportContext ctx) {
        SheetImportResult result = new SheetImportResult("物料BOM+组成件BOM(合并)");

        com.cpq.basicdata.v6.service.MaterialNoResolver.BatchState batch =
            new com.cpq.basicdata.v6.service.MaterialNoResolver.BatchState();

        Map<String, Map<String, Map<String, Object>>> matByMat = new LinkedHashMap<>();
        Map<String, Map<String, Map<String, Object>>> asmByMat = new LinkedHashMap<>();

        for (SheetRow row : materialRows) {
            result.totalRows++;
            String materialNo = row.getStr("宏丰料号");
            if (materialNo == null) { result.recordError(row.rowNo, "宏丰料号", "为空"); continue; }
            if (isCfg(materialNo)) { result.recordError(row.rowNo, "宏丰料号", "禁止导入系统生成料号(CFG- 前缀): " + materialNo); continue; }
            String componentUsageType = row.getStr("产出料号类型");
            String componentName = row.getStr("投入料号名称");
            // 注意：getStr("投入料号") 用 contains 匹配，会命中"投入料号名称"列（AP-bug）。
            // 必须用精确键读取，以区分"投入料号"(可空)和"投入料号名称"(名称)。
            String rawComponentNo = row.cells.get("投入料号");
            String exactComponentNo = (rawComponentNo == null || rawComponentNo.isBlank()) ? null : rawComponentNo.trim();
            String componentNo;
            try {
                componentNo = materialNoResolver.resolve(exactComponentNo, componentName, batch);
            } catch (com.cpq.basicdata.v6.service.MaterialNoUnresolvableException ex) {
                result.recordError(row.rowNo, "投入料号", "料号与名称均为空"); continue;
            }
            // 决策 #9：报价 §3 已存在则保留旧名称/类型 → preserveDescriptive=true
            materialMasterRepo.upsertByMaterialNo(componentNo, componentName,
                null, null, null, digitsOnly(componentUsageType), null, null, null, ctx.importedBy, true);
            result.recordWrite("material_master", 1);
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("seq_no", row.getInt("项次"));
            c.put("component_no", componentNo);
            c.put("component_usage_type", componentUsageType);
            c.put("composition_qty", row.getDecimal("材料毛重", "毛重"));
            c.put("base_qty", row.getDecimal("材料净重", "净重"));
            c.put("issue_unit", row.getStr("重量单位"));
            c.put("scrap_rate", row.getDecimal("损耗率"));
            c.put("defect_rate", row.getDecimal("不良率"));
            matByMat.computeIfAbsent(materialNo, k -> new LinkedHashMap<>())
                    .put(String.valueOf(componentNo), c);
            result.successRows++;
        }

        for (SheetRow row : assemblyRows) {
            result.totalRows++;
            String materialNo = row.getStr("宏丰料号");
            if (materialNo == null) { result.recordError(row.rowNo, "宏丰料号", "为空"); continue; }
            if (isCfg(materialNo)) { result.recordError(row.rowNo, "宏丰料号", "禁止导入系统生成料号(CFG- 前缀): " + materialNo); continue; }
            String componentNo = row.getStr("组成件料号");
            if (componentNo == null) { result.recordError(row.rowNo, "组成件料号", "为空"); continue; }
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("seq_no", row.getInt("项次（一级）", "项次"));
            c.put("operation_no", row.getStr("工序编号"));
            c.put("item_seq", row.getIntNth("项次", 2));
            c.put("component_no", componentNo);
            c.put("composition_qty", row.getDecimal("组成数量"));
            c.put("issue_unit", row.getStr("组成单位"));
            asmByMat.computeIfAbsent(materialNo, k -> new LinkedHashMap<>())
                    .put(String.valueOf(componentNo), c);
            result.successRows++;
        }

        Set<String> allMats = new LinkedHashSet<>();
        allMats.addAll(matByMat.keySet());
        allMats.addAll(asmByMat.keySet());

        for (String materialNo : allMats) {
            try {
                Map<String, Map<String, Object>> matChild = matByMat.getOrDefault(materialNo, Map.of());
                Map<String, Map<String, Object>> asmChild = asmByMat.getOrDefault(materialNo, Map.of());
                boolean isAssembly = !asmChild.isEmpty();
                String targetChar = isAssembly ? "ASSEMBLY" : null;
                String bomType = isAssembly ? "ASSEMBLY" : "MATERIAL";

                Map<String, Map<String, Object>> merged = new LinkedHashMap<>();
                for (Map.Entry<String, Map<String, Object>> e : matChild.entrySet()) {
                    merged.put(e.getKey(), new LinkedHashMap<>(e.getValue()));
                }
                for (Map.Entry<String, Map<String, Object>> e : asmChild.entrySet()) {
                    Map<String, Object> tgt = merged.computeIfAbsent(e.getKey(), k -> new LinkedHashMap<>());
                    for (Map.Entry<String, Object> f : e.getValue().entrySet()) {
                        if (f.getValue() != null) tgt.put(f.getKey(), f.getValue());
                    }
                }
                List<Map<String, Object>> childRows = new ArrayList<>(merged.values());

                // 把 bom_type/characteristic 写入每个子行（insertRowGeneric 会 putAll(row)）。
                // 注意：characteristic 不加入 CHILD_CONTENT（不参与 multisetEqual 内容比较，
                // 避免 NULL→ASSEMBLY 时被误判为组成件内容变化）。
                for (var r : childRows) r.put("characteristic", targetChar);

                // 分组键收敛为 system_type+customer_no+material_no（单料号单序列）。
                // bom_type/characteristic 降为 masterFixedColumns（固定写入列，不参与版本分组）。
                Map<String, Object> masterGk = new LinkedHashMap<>();
                masterGk.put("system_type", "QUOTE");
                masterGk.put("customer_no", ctx.customerNo);
                masterGk.put("material_no", materialNo);
                Map<String, Object> masterFixed = new LinkedHashMap<>();
                masterFixed.put("bom_type", bomType);
                masterFixed.put("characteristic", targetChar);
                Map<String, Object> childGk = new LinkedHashMap<>();
                childGk.put("system_type", "QUOTE");
                childGk.put("customer_no", ctx.customerNo);
                childGk.put("material_no", materialNo);
                writer.writeVersionedMasterDetail(
                    "material_bom", "bom_version", masterGk, masterFixed,
                    "material_bom_item", "bom_version", childGk, CHILD_CONTENT, childRows);
                result.recordWrite("material_bom", 1);
                result.recordWrite("material_bom_item", childRows.size());
            } catch (Exception ex) {
                result.recordError(0, "_group_", "material_no=" + materialNo + ": " + ex.getMessage());
            }
        }
        return result;
    }

    private static boolean isCfg(String materialNo) {
        return materialNo != null && materialNo.startsWith("CFG-");
    }

    private static String digitsOnly(String s) {
        if (s == null) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch >= '0' && ch <= '9') sb.append(ch);
            else if (sb.length() > 0) break;
        }
        return sb.length() == 0 ? null : sb.toString();
    }
}
