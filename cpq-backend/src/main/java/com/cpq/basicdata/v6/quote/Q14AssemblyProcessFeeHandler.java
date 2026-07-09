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
 * Q14 组装加工费 → capacity 表（不是 unit_price）。
 *
 * <p>版本化：groupKey=(material_no, resource_group_no=QUOTE_ASSEMBLY)，料号级整组升版。
 * <p>升版触发列：process_no + seq_no（工序编码/项次变化才升版）；金额/货币/计价单位/拒收率原地更新不升版。
 * <p>同料号多工序行汇总成一组 newRows，整体写入 capacity。
 */
@ApplicationScoped
public class Q14AssemblyProcessFeeHandler implements SheetHandler {

    @Inject VersionedV6Writer writer;

    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "cpq.v6import-setbased-writer", defaultValue = "false")
    boolean setBased;

    @Override public String sheetName() { return "组装加工费"; }

    private static final List<String> CONTENT = List.of(
        "process_no", "seq_no", "production_type", "fixed_cost",
        "currency", "capacity_unit", "default_defect_rate", "is_effective");

    /** 升版触发列：仅工序编码 + 项次(数量)变化才升版；金额/货币/计价单位/拒收率原地更新。 */
    private static final List<String> VERSION_TRIGGER = List.of("process_no", "seq_no");

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public SheetImportResult handle(List<SheetRow> rows, ImportContext ctx) {
        SheetImportResult result = new SheetImportResult(sheetName());
        // 按料号聚合：同一料号的所有工序行汇总成一组（料号级整组升版）
        Map<List<Object>, Map<String, Object>> groupKeyOf = new LinkedHashMap<>();
        Map<List<Object>, List<Map<String, Object>>> rowsOf = new LinkedHashMap<>();
        for (SheetRow row : rows) {
            result.totalRows++;
            String materialNo = row.getStr("销售料号", "宏丰料号");
            String processNo = row.getStr("组装工序", "工序编号");
            if (materialNo == null || processNo == null) {
                result.recordError(row.rowNo, "宏丰料号/工序编号", "必填项为空");
                continue;
            }
            String resourceGroupNo = "QUOTE_ASSEMBLY";
            List<Object> key = Arrays.asList(materialNo, resourceGroupNo);
            groupKeyOf.computeIfAbsent(key, k -> {
                Map<String, Object> g = new LinkedHashMap<>();
                g.put("system_type", "QUOTE");
                g.put("material_no", materialNo);
                g.put("resource_group_no", resourceGroupNo);
                return g;
            });
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("process_no", processNo);
            c.put("seq_no", row.getInt("项次"));
            c.put("production_type", "BATCH_FIXED");
            c.put("fixed_cost", row.getDecimal("组装加工费"));
            c.put("currency", row.getStr("货币"));
            c.put("capacity_unit", row.getStr("计价单位"));
            c.put("default_defect_rate", row.getDecimal("拒收率", "不良率"));
            c.put("is_effective", true);
            rowsOf.computeIfAbsent(key, k -> new ArrayList<>()).add(c);
            result.successRows++;
        }
        if (setBased) {
            LinkedHashMap<Map<String, Object>, List<Map<String, Object>>> groups = new LinkedHashMap<>();
            for (Map.Entry<List<Object>, List<Map<String, Object>>> e : rowsOf.entrySet())
                groups.put(groupKeyOf.get(e.getKey()), e.getValue());
            try {
                writer.writeVersionedGroups("capacity", "calc_version", CONTENT, VERSION_TRIGGER, groups);
                for (List<Map<String, Object>> groupRows : groups.values())
                    result.recordWrite("capacity", groupRows.size());
            } catch (Exception ex) {
                result.recordError(0, "_batch_", ex.getMessage());
            }
        } else {
            for (Map.Entry<List<Object>, List<Map<String, Object>>> e : rowsOf.entrySet()) {
                try {
                    writer.writeVersionedGroup(new VersionedGroupSpec(
                        "capacity", "calc_version", groupKeyOf.get(e.getKey()),
                        CONTENT, e.getValue(), VERSION_TRIGGER));
                    result.recordWrite("capacity", e.getValue().size());
                } catch (Exception ex) {
                    result.recordError(0, "_group_", ex.getMessage());
                }
            }
        }
        return result;
    }
}
