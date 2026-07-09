package com.cpq.basicdata.v6.quote;

import com.cpq.basicdata.v6.parser.ImportContext;
import com.cpq.basicdata.v6.parser.SheetImportResult;
import com.cpq.basicdata.v6.parser.SheetRow;
import com.cpq.basicdata.v6.repository.MaterialMasterRepository;
import com.cpq.basicdata.v6.repository.ProcessMasterRepository;
import com.cpq.basicdata.v6.service.MaterialNoResolver;
import com.cpq.basicdata.v6.service.MaterialNoUnresolvableException;
import com.cpq.basicdata.v6.service.QuoteMaterialNoAllocator;
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
    @Inject MaterialNoResolver materialNoResolver;
    @Inject ProcessMasterRepository processMasterRepo;

    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "cpq.v6import-setbased-writer", defaultValue = "false")
    boolean setBased;

    /** 合并后子表内容列 = 物料BOM ∪ 组成件BOM。 */
    private static final List<String> CHILD_CONTENT = List.of(
        "seq_no", "component_no", "component_usage_type", "composition_qty",
        "base_qty", "issue_unit", "scrap_rate", "defect_rate",
        "operation_no", "item_seq",
        "rough_weight", "net_weight", "weight_unit");

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public SheetImportResult merge(List<SheetRow> materialRows, List<SheetRow> assemblyRows, ImportContext ctx) {
        SheetImportResult result = new SheetImportResult("物料BOM+组成件BOM(合并)");

        MaterialNoResolver.BatchState batch = new MaterialNoResolver.BatchState();
        batch.customerNo = ctx.customerNo;
        batch.yyMm = java.time.YearMonth.now().format(java.time.format.DateTimeFormatter.ofPattern("yyMM"));

        Map<String, Map<String, Map<String, Object>>> matByMat = new LinkedHashMap<>();
        Map<String, Map<String, Map<String, Object>>> asmByMat = new LinkedHashMap<>();

        // §12 料号表 upsert 延后批量：按 material_no 去重 + 首个非空归并 name/type，
        // 末尾一次 upsertBatchNameType(preserve=true)，等价于原逐行 upsert（resolver 不依赖本批 upsert 可见性）。
        Map<String, String[]> mmAcc = new LinkedHashMap<>();   // material_no -> [name, type]（首个非空胜）

        for (SheetRow row : materialRows) {
            result.totalRows++;
            String materialNo = row.getStr("销售料号", "宏丰料号");
            if (materialNo == null) { result.recordError(row.rowNo, "宏丰料号", "为空"); continue; }
            if (isCfg(materialNo)) { result.recordError(row.rowNo, "宏丰料号", "禁止导入系统生成料号(CFG- 前缀): " + materialNo); continue; }
            String componentUsageType = row.getStr("产出料号类型");
            String componentName = row.getStr("投入料号名称");
            // 注意：getStr("投入料号") 用 contains 匹配，会命中"投入料号名称"列（AP-bug）。
            // 必须用精确键读取，以区分"投入料号"(可空)和"投入料号名称"(名称)。
            String componentNo;
            try {
                componentNo = materialNoResolver.resolve(row.exact("投入料号"), componentName, batch);
            } catch (MaterialNoUnresolvableException ex) {
                result.recordError(row.rowNo, "投入料号", "料号与名称均为空"); continue;
            } catch (QuoteMaterialNoAllocator.CrossCustomerQuoteNoException ex) {
                result.recordError(row.rowNo, "投入料号", "报价料号跨客户串号"); continue;
            }
            // material_master：产出料号类型只存汉字（labelOnly）。延后批量 upsert（见 mmAcc）。
            accMaterialMaster(mmAcc, componentNo, componentName, labelOnly(componentUsageType));
            result.recordWrite("material_master", 1);
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("seq_no", row.getInt("项次"));
            c.put("component_no", componentNo);
            c.put("component_usage_type", labelOnly(componentUsageType));
            c.put("rough_weight", row.getDecimal("材料毛重", "毛重"));
            c.put("net_weight",   row.getDecimal("材料净重", "净重"));
            c.put("weight_unit",  row.getStr("重量单位"));
            c.put("scrap_rate", row.getDecimal("损耗率"));
            c.put("defect_rate", row.getDecimal("不良率"));
            matByMat.computeIfAbsent(materialNo, k -> new LinkedHashMap<>())
                    .put(String.valueOf(componentNo), c);
            result.successRows++;
        }

        for (SheetRow row : assemblyRows) {
            result.totalRows++;
            String materialNo = row.getStr("销售料号", "宏丰料号");
            if (materialNo == null) { result.recordError(row.rowNo, "宏丰料号", "为空"); continue; }
            if (isCfg(materialNo)) { result.recordError(row.rowNo, "宏丰料号", "禁止导入系统生成料号(CFG- 前缀): " + materialNo); continue; }
            String componentName = row.getStr("组成件名称");
            String componentNo;
            try {
                componentNo = materialNoResolver.resolve(row.exact("组成件料号"), componentName, batch);
            } catch (MaterialNoUnresolvableException ex) {
                result.recordError(row.rowNo, "组成件料号", "料号与名称均为空"); continue;
            } catch (QuoteMaterialNoAllocator.CrossCustomerQuoteNoException ex) {
                result.recordError(row.rowNo, "组成件料号", "报价料号跨客户串号"); continue;
            }
            // §12 料号表同步：组成件 material_type 固定存汉字「组成件」，已存在保留原值（preserveDescriptive=true）。延后批量。
            accMaterialMaster(mmAcc, componentNo, componentName, "组成件");
            result.recordWrite("material_master", 1);

            // 工序回填（决策 #5）：工序编号空 + 组装工序(工序名称)有值 → 按名取第一条 process_no
            String operationNo = row.getStr("工序编号");
            if (operationNo == null) {
                String procName = row.getStr("组装工序");
                if (procName != null) {
                    operationNo = processMasterRepo.findFirstByProcessName(procName)
                        .map(p -> p.processNo).orElse(null);
                }
            }

            Map<String, Object> c = new LinkedHashMap<>();
            c.put("seq_no", row.getInt("项次（一级）", "项次"));
            c.put("operation_no", operationNo);
            c.put("item_seq", row.getIntNth("项次", 2));
            c.put("component_no", componentNo);
            c.put("composition_qty", row.getDecimal("组成数量"));
            c.put("issue_unit", row.getStr("组成单位"));
            asmByMat.computeIfAbsent(materialNo, k -> new LinkedHashMap<>())
                    .put(String.valueOf(componentNo), c);
            result.successRows++;
        }

        // §12 料号表：一次批量 upsert（去重后；preserve=true 与原逐行等价）。
        // 置于子表写入循环之前，保持"先写 material_master 再写 BOM 子表"的原有相对顺序。
        if (!mmAcc.isEmpty()) {
            List<MaterialMasterRepository.NameTypeRow> mmRows = new ArrayList<>(mmAcc.size());
            for (Map.Entry<String, String[]> e : mmAcc.entrySet()) {
                mmRows.add(new MaterialMasterRepository.NameTypeRow(
                    e.getKey(), e.getValue()[0], e.getValue()[1]));
            }
            materialMasterRepo.upsertBatchNameType(mmRows, ctx.importedBy, true);
        }

        Set<String> allMats = new LinkedHashSet<>();
        allMats.addAll(matByMat.keySet());
        allMats.addAll(asmByMat.keySet());

        if (setBased) {
            // 按 master 构建 item，再按 masterFixed 组合(bom_type+characteristic)分区，每个分区一次批量调用。
            // 批量 API 对整批应用同一 masterFixedColumns，故同一组合(MATERIAL/null 或 ASSEMBLY/ASSEMBLY)的 item 归一桶。
            // 物料要么是组成件(ASSEMBLY)要么不是 → 跨分区 master 组互斥，N 个批量调用与逐项循环等价。
            Map<Map<String, Object>, List<VersionedV6Writer.MasterDetailItem>> byFixed = new LinkedHashMap<>();
            for (String materialNo : allMats) {
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
                for (var r : childRows) r.put("characteristic", targetChar);

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
                byFixed.computeIfAbsent(masterFixed, k -> new ArrayList<>())
                       .add(new VersionedV6Writer.MasterDetailItem(masterGk, childGk, childRows));
            }
            for (Map.Entry<Map<String, Object>, List<VersionedV6Writer.MasterDetailItem>> p : byFixed.entrySet()) {
                List<VersionedV6Writer.MasterDetailItem> items = p.getValue();
                try {
                    writer.writeVersionedMasterDetails(
                        "material_bom", "bom_version", p.getKey(),
                        "material_bom_item", "bom_version", CHILD_CONTENT, items);
                    for (VersionedV6Writer.MasterDetailItem it : items) {
                        result.recordWrite("material_bom", 1);
                        result.recordWrite("material_bom_item", it.childRows.size());
                    }
                } catch (Exception ex) {
                    result.recordError(0, "_batch_", ex.getMessage());
                }
            }
        } else {
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
        }
        return result;
    }

    private static boolean isCfg(String materialNo) {
        return materialNo != null && materialNo.startsWith("CFG-");
    }

    /**
     * 累积 material_master 的 name/type（按 material_no 去重，<b>首个非空胜</b>）。
     * 对齐原逐行 {@code upsertByMaterialNo(preserve=true)} 的 COALESCE(existing,new) 顺序语义：
     * 同一 material_no 多次出现时，name/type 取遍历顺序里第一个非空值。
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
