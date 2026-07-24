package com.cpq.basicdata.v6.quote;

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
 * Q04 物料与元素BOM → element_bom 主表 + element_bom_item 子表。
 *
 * <p>版本化（Task 5）：按 material_no（投入料号）分组，调
 * {@link VersionedV6Writer#writeVersionedMasterDetail}：子表行集指纹相同复用 characteristic，
 * 不同则 characteristic max+1 + 主/子 is_current 翻转（子表 uq 含 characteristic → 多版本保留）。
 * <p>主表 bom_type='MATERIAL'（NOT NULL，经 masterFixedColumns 写入）。
 * <p>替代原 fingerprintExisting/fingerprintRows/nv() 自写指纹逻辑（已删）。
 */
@ApplicationScoped
public class Q04ElementBomHandler implements SheetHandler {

    @Inject VersionedV6Writer writer;
    @Inject MaterialMasterRepository materialMasterRepo;

    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "cpq.v6import-setbased-writer", defaultValue = "false")
    boolean setBased;

    @Override public String sheetName() { return "物料与元素BOM"; }

    private static final List<String> CHILD_CONTENT = List.of(
        "seq_no", "component_no", "content", "scrap_rate", "composition_qty", "issue_unit", "base_qty");

    @Override
    @Transactional(Transactional.TxType.MANDATORY)
    public SheetImportResult handle(List<SheetRow> rows, ImportContext ctx) {
        SheetImportResult result = new SheetImportResult(sheetName());

        // §P1-A 料号表 upsert 延后批量：material_no -> [name, type]
        Map<String, String[]> mmAcc = new LinkedHashMap<>();
        // 分组维度 = (material_no=销售料号, material_part_no=材质料号); 组内按(项次,元素)去重。
        // 报价该 sheet 无投入料号列, 不再走 materialNoResolver 铸号(见 backtask §3.1);
        // 销售料号的名称由 Q02(客户料号关系)维护, 此处仅登记料号+类型(name=null 不覆盖)。
        Map<List<String>, Map<List<Object>, Map<String, Object>>> childByKey = new LinkedHashMap<>();
        Map<List<String>, String[]> keyMeta = new LinkedHashMap<>();
        for (SheetRow row : rows) {
            result.totalRows++;
            String materialNo = row.getStr("销售料号");
            if (materialNo == null) { result.recordError(row.rowNo, "销售料号", "为空"); continue; }
            String materialPartNo = row.getStr("材质料号");
            MaterialMasterRepository.accNameType(mmAcc, materialNo, null, "成品");
            result.recordWrite("material_master", 1);
            Integer seq = row.getInt("项次");
            String componentNo = row.getStr("元素");
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("seq_no", seq);
            c.put("component_no", componentNo);
            c.put("content", row.getDecimal("组成含量"));
            c.put("scrap_rate", row.getDecimal("损耗率"));
            c.put("composition_qty", row.getDecimal("毛用量"));
            // issue_unit: 净用量单位非空(trim 后)时优先采用, 否则回退毛用量单位。
            String netUnit = row.getStr("净用量单位");
            c.put("issue_unit", netUnit != null ? netUnit : row.getStr("毛用量单位"));
            c.put("base_qty", row.getDecimal("净用量"));
            List<String> gkey = Arrays.asList(materialNo, materialPartNo == null ? "" : materialPartNo);
            keyMeta.putIfAbsent(gkey, new String[]{materialNo, materialPartNo});
            childByKey.computeIfAbsent(gkey, k -> new LinkedHashMap<>())
                      .put(Arrays.asList(seq, componentNo), c);   // 去重键 = (项次, 元素)
            result.successRows++;
        }

        // §P1-A 料号表：一次批量 upsert（去重后；preserve=true 与原逐行等价），置于版本化写入之前。
        if (!mmAcc.isEmpty()) {
            List<MaterialMasterRepository.NameTypeRow> mmRows = new ArrayList<>(mmAcc.size());
            for (Map.Entry<String, String[]> me : mmAcc.entrySet()) {
                mmRows.add(new MaterialMasterRepository.NameTypeRow(me.getKey(), me.getValue()[0], me.getValue()[1]));
            }
            // task-0721 B9：pending 模式暂存。
            materialMasterRepo.upsertBatchNameType(mmRows, ctx.importedBy, true, ctx.pendingQuotationId);
        }

        if (setBased) {
            List<VersionedV6Writer.MasterDetailItem> items = new ArrayList<>();
            for (Map.Entry<List<String>, Map<List<Object>, Map<String, Object>>> e : childByKey.entrySet()) {
                String[] meta = keyMeta.get(e.getKey());
                Map<String, Object> masterGk = new LinkedHashMap<>();
                masterGk.put("system_type", "QUOTE");
                masterGk.put("customer_no", ctx.customerNo);
                masterGk.put("material_no", meta[0]);
                masterGk.put("material_part_no", meta[1]);
                Map<String, Object> childGk = new LinkedHashMap<>(masterGk);
                List<Map<String, Object>> childRows = new ArrayList<>(e.getValue().values());
                items.add(new VersionedV6Writer.MasterDetailItem(masterGk, childGk, childRows));
            }
            try {
                writer.writeVersionedMasterDetails("element_bom", "characteristic",
                    Map.of("bom_type", "MATERIAL"), "element_bom_item", "characteristic",
                    CHILD_CONTENT, items, ctx.pendingQuotationId);
                for (VersionedV6Writer.MasterDetailItem it : items) {
                    result.recordWrite("element_bom", 1);
                    result.recordWrite("element_bom_item", it.childRows.size());
                }
            } catch (Exception ex) {
                result.recordError(0, "_batch_", ex.getMessage());
            }
        } else {
            for (Map.Entry<List<String>, Map<List<Object>, Map<String, Object>>> e : childByKey.entrySet()) {
                String[] meta = keyMeta.get(e.getKey());
                List<Map<String, Object>> childRows = new ArrayList<>(e.getValue().values());
                try {
                    Map<String, Object> masterGk = new LinkedHashMap<>();
                    masterGk.put("system_type", "QUOTE");
                    masterGk.put("customer_no", ctx.customerNo);
                    masterGk.put("material_no", meta[0]);
                    masterGk.put("material_part_no", meta[1]);
                    Map<String, Object> childGk = new LinkedHashMap<>(masterGk);   // element_bom_item 同身份
                    writer.writeVersionedMasterDetail(
                        "element_bom", "characteristic", masterGk, Map.of("bom_type", "MATERIAL"),
                        "element_bom_item", "characteristic", childGk, CHILD_CONTENT, childRows, ctx.pendingQuotationId);
                    result.recordWrite("element_bom", 1);
                    result.recordWrite("element_bom_item", childRows.size());
                } catch (Exception ex) {
                    result.recordError(0, "_group_", "material_no=" + meta[0] + "/part=" + meta[1] + ": " + ex.getMessage());
                }
            }
        }
        return result;
    }
}
