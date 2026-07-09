package com.cpq.basicdata.v6.pricing;

import com.cpq.basicdata.v6.parser.ImportContext;
import com.cpq.basicdata.v6.parser.SheetHandler;
import com.cpq.basicdata.v6.parser.SheetImportResult;
import com.cpq.basicdata.v6.parser.SheetRow;
import com.cpq.basicdata.v6.repository.MaterialMasterRepository;
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
 * P06 物料BOM (PRICING) → material_bom（主） + material_bom_item（子），主从版本化。
 *
 * <p>对齐报价 Q03：按 material_no 分组调 {@link VersionedV6Writer#writeVersionedMasterDetail}；
 * 主表 bom_version max+1（首版 2000）；子表按 bom_version 多版本保留（V293 起），历史版本行 is_current=false 留存。
 * <p>核价 BOM 全局共享，customer_no 用 "_GLOBAL_" 哨兵；system_type='PRICING'，与报价物理隔离。
 */
@ApplicationScoped
public class P06MaterialBomHandler implements SheetHandler {

    public static final String PRICING_CUSTOMER = "_GLOBAL_";

    @Inject VersionedV6Writer writer;
    @Inject MaterialMasterRepository masterRepo;

    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "cpq.v6import-setbased-writer", defaultValue = "false")
    boolean setBased;

    @Override public String sheetName() { return "物料BOM"; }

    private static final List<String> CHILD_CONTENT = List.of(
        "seq_no", "component_no", "operation_no", "component_usage_type",
        "composition_qty", "issue_unit", "base_qty", "scrap_rate", "fixed_scrap",
        "defect_rate", "calc_type", "production_no");

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public SheetImportResult handle(List<SheetRow> rows, ImportContext ctx) {
        SheetImportResult result = new SheetImportResult(sheetName());

        Map<String, Map<List<Object>, Map<String, Object>>> childByMat = new LinkedHashMap<>();
        // 20260705：组成料号 → [品名, 规格, 尺寸]，按 material_no 去重、首个非空归并（同料号多父件/多 occurrence）。
        Map<String, String[]> compDesc = new LinkedHashMap<>();
        for (SheetRow row : rows) {
            result.totalRows++;
            String materialNo = row.getStr("销售料号", "宏丰料号");
            if (materialNo == null) { result.recordError(row.rowNo, "宏丰料号", "为空"); continue; }
            Integer seq = row.getInt("项次");
            String componentNo = row.getStr("组成料号", "组件料号");
            if (componentNo != null) {
                String[] cur = compDesc.get(componentNo);
                String name = row.getStr("品名"), spec = row.getStr("规格"), dim = row.getStr("尺寸");
                if (cur == null) {
                    compDesc.put(componentNo, new String[]{name, spec, dim});
                } else {
                    if (cur[0] == null) cur[0] = name;
                    if (cur[1] == null) cur[1] = spec;
                    if (cur[2] == null) cur[2] = dim;
                }
            }

            Map<String, Object> c = new LinkedHashMap<>();
            c.put("seq_no", seq);
            c.put("component_no", componentNo);
            c.put("operation_no", row.getStr("工序编号"));
            c.put("component_usage_type", row.getStr("使用特性"));
            c.put("composition_qty", row.getDecimal("组成用量"));
            c.put("issue_unit", row.getStr("组成用量单位"));
            c.put("base_qty", row.getDecimal("底数"));
            c.put("scrap_rate", row.getDecimal("材料损耗率", "损耗率"));
            c.put("fixed_scrap", row.getDecimal("材料固定损耗量", "固定损耗"));
            c.put("defect_rate", row.getDecimal("不良率"));
            c.put("calc_type", row.getStr("计算类型"));
            c.put("production_no", row.getStr("生产料号"));   // 描述列; material_bom_item 携带, master header 留 NULL
            childByMat.computeIfAbsent(materialNo, k -> new LinkedHashMap<>())
                      .put(Arrays.asList(seq, componentNo), c);   // 去重键 = (项次, 组成料号)
            result.successRows++;
        }

        if (setBased) {
            List<VersionedV6Writer.MasterDetailItem> items = new ArrayList<>();
            for (Map.Entry<String, Map<List<Object>, Map<String, Object>>> e : childByMat.entrySet()) {
                String materialNo = e.getKey();
                Map<String, Object> masterGk = new LinkedHashMap<>();
                masterGk.put("system_type", "PRICING");
                masterGk.put("customer_no", PRICING_CUSTOMER);
                masterGk.put("material_no", materialNo);
                masterGk.put("bom_type", "MATERIAL");
                Map<String, Object> childGk = new LinkedHashMap<>();
                childGk.put("system_type", "PRICING");
                childGk.put("customer_no", PRICING_CUSTOMER);
                childGk.put("material_no", materialNo);
                childGk.put("characteristic", null);
                List<Map<String, Object>> childRows = new ArrayList<>(e.getValue().values());
                items.add(new VersionedV6Writer.MasterDetailItem(masterGk, childGk, childRows));
            }
            try {
                writer.writeVersionedMasterDetails("material_bom", "bom_version",
                    Map.of(), "material_bom_item", "bom_version",
                    CHILD_CONTENT, items);
                for (VersionedV6Writer.MasterDetailItem it : items) {
                    result.recordWrite("material_bom", 1);
                    result.recordWrite("material_bom_item", it.childRows.size());
                }
            } catch (Exception ex) {
                result.recordError(0, "_batch_", ex.getMessage());
            }
        } else {
            for (Map.Entry<String, Map<List<Object>, Map<String, Object>>> e : childByMat.entrySet()) {
                String materialNo = e.getKey();
                List<Map<String, Object>> childRows = new ArrayList<>(e.getValue().values());
                try {
                    Map<String, Object> masterGk = new LinkedHashMap<>();
                    masterGk.put("system_type", "PRICING");
                    masterGk.put("customer_no", PRICING_CUSTOMER);
                    masterGk.put("material_no", materialNo);
                    masterGk.put("bom_type", "MATERIAL");
                    Map<String, Object> childGk = new LinkedHashMap<>();
                    childGk.put("system_type", "PRICING");
                    childGk.put("customer_no", PRICING_CUSTOMER);
                    childGk.put("material_no", materialNo);
                    childGk.put("characteristic", null);
                    writer.writeVersionedMasterDetail(
                        "material_bom", "bom_version", masterGk, Map.of(),
                        "material_bom_item", "bom_version", childGk, CHILD_CONTENT, childRows);
                    result.recordWrite("material_bom", 1);
                    result.recordWrite("material_bom_item", childRows.size());
                } catch (Exception ex) {
                    result.recordError(0, "_group_", "material_no=" + materialNo + ": " + ex.getMessage());
                }
            }
        }

        // 20260705：同步登记料号表 material_master（独立于 BOM 写入，见落库方案 §6）。
        // 父件：裸登记 material_no（本 Sheet 无父件名称列）；组成料号：回填 品名/规格/尺寸，仅补空白不覆盖 Sheet5 权威名称。
        try {
            if (!childByMat.isEmpty()) {
                masterRepo.upsertBatchMaterialNoOnly(new ArrayList<>(childByMat.keySet()), ctx.importedBy);
                result.recordWrite("material_master", childByMat.size());
            }
            for (Map.Entry<String, String[]> e : compDesc.entrySet()) {
                String[] d = e.getValue();
                masterRepo.upsertByMaterialNo(e.getKey(), d[0], d[1], d[2],
                    null, null, null, null, null, ctx.importedBy, /*preserveDescriptive=*/true);
                result.recordWrite("material_master", 1);
            }
        } catch (Exception ex) {
            result.recordError(0, "_material_master_", ex.getMessage());
        }
        return result;
    }
}
