package com.cpq.basicdata.v6.quote;

import com.cpq.basicdata.v6.parser.ImportContext;
import com.cpq.basicdata.v6.parser.SheetHandler;
import com.cpq.basicdata.v6.parser.SheetImportResult;
import com.cpq.basicdata.v6.parser.SheetRow;
import com.cpq.basicdata.v6.versioning.VersionedGroupSpec;
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
 * Q09 来料回收折扣 → unit_price (price=INCOMING_MATERIAL_RECYCLE, cost=回收折扣)。
 *
 * <p>版本化（Task 3）：groupKey=(QUOTE, customer_no, MATERIAL, 回收折扣, code, finished_material_no)，
 * content=[cost_ratio]。无 seq_no（§9 项次不导入）。
 */
@ApplicationScoped
public class Q09IncomingRecoveryHandler implements SheetHandler {

    @Inject VersionedV6Writer writer;

    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "cpq.v6import-setbased-writer", defaultValue = "false")
    boolean setBased;

    @Override public String sheetName() { return "来料回收折扣"; }

    private static final List<String> CONTENT = List.of("cost_ratio");

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public SheetImportResult handle(List<SheetRow> rows, ImportContext ctx) {
        SheetImportResult result = new SheetImportResult(sheetName());
        Map<List<Object>, Map<String, Object>> groupKeyOf = new LinkedHashMap<>();
        Map<List<Object>, List<Map<String, Object>>> contentOf = new LinkedHashMap<>();
        for (SheetRow row : rows) {
            result.totalRows++;
            // task-0717 扩围:投入料号=材质料号,恒按材质处理——原始码作 code,不 resolve/不铸号、
            // 不登记 material_customer_map、不登记 material_master(名走 material_recipe 兜底)。
            String raw = row.exact("投入料号");
            if (raw == null || raw.isBlank()) { result.recordError(row.rowNo, "投入料号", "为空"); continue; }
            String code = raw;
            String finishedMaterialNo = row.getStr("销售料号", "宏丰料号", "成品料号");
            List<Object> key = Arrays.asList(code, finishedMaterialNo);
            groupKeyOf.computeIfAbsent(key, k -> {
                Map<String, Object> g = new LinkedHashMap<>();
                g.put("system_type", "QUOTE");
                g.put("customer_no", ctx.customerNo);
                g.put("price_type", "INCOMING_MATERIAL_RECYCLE");
                g.put("cost_type", "回收折扣");
                g.put("code", code);
                g.put("finished_material_no", finishedMaterialNo);
                return g;
            });
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("cost_ratio", row.getDecimal("回收折扣"));
            contentOf.computeIfAbsent(key, k -> new ArrayList<>()).add(c);
            result.successRows++;
        }
        if (setBased) {
            LinkedHashMap<Map<String, Object>, List<Map<String, Object>>> groups = new LinkedHashMap<>();
            for (Map.Entry<List<Object>, List<Map<String, Object>>> e : contentOf.entrySet())
                groups.put(groupKeyOf.get(e.getKey()), e.getValue());
            try {
                writer.writeVersionedGroups("unit_price", "version_no", CONTENT, null, List.of(), groups, ctx.pendingQuotationId);
                for (List<Map<String, Object>> groupRows : groups.values())
                    result.recordWrite("unit_price", groupRows.size());
            } catch (Exception ex) {
                result.recordError(0, "_batch_", ex.getMessage());
            }
        } else {
            for (Map.Entry<List<Object>, List<Map<String, Object>>> e : contentOf.entrySet()) {
                try {
                    writer.writeVersionedGroup(new VersionedGroupSpec(
                        "unit_price", "version_no", groupKeyOf.get(e.getKey()), CONTENT, e.getValue(), null, ctx.pendingQuotationId));
                    result.recordWrite("unit_price", e.getValue().size());
                } catch (Exception ex) {
                    result.recordError(0, "_group_", ex.getMessage());
                }
            }
        }
        return result;
    }
}
