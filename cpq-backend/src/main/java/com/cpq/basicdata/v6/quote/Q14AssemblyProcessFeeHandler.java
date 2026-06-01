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

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Q14 组装加工费 → capacity 表（不是 unit_price）。
 *
 * <p>版本化（Task 7）：groupKey=(material_no, process_no, resource_group_no=QUOTE_ASSEMBLY)，
 * calc_version 由系统生成（去原硬编码 'V_DEFAULT'）。
 * <p>capacity uq 不含 seq_no → 每 (material,process,resource_group) 仅一行；Excel 多行后写覆盖（匹配原 ON CONFLICT）。
 * <p>is_effective=true 纳入 content 保全（capacity.is_effective 可空无默认；过渡期防 is_effective 过滤读取方漏数）。
 */
@ApplicationScoped
public class Q14AssemblyProcessFeeHandler implements SheetHandler {

    @Inject VersionedV6Writer writer;

    @Override public String sheetName() { return "组装加工费"; }

    private static final List<String> CONTENT = List.of(
        "seq_no", "production_type", "fixed_cost", "currency", "capacity_unit", "default_defect_rate", "is_effective");

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public SheetImportResult handle(List<SheetRow> rows, ImportContext ctx) {
        SheetImportResult result = new SheetImportResult(sheetName());
        // 每 (material, process, resource_group) 仅一行 → 后写覆盖
        Map<List<Object>, Map<String, Object>> groupKeyOf = new LinkedHashMap<>();
        Map<List<Object>, Map<String, Object>> rowOf = new LinkedHashMap<>();
        for (SheetRow row : rows) {
            result.totalRows++;
            String materialNo = row.getStr("宏丰料号");
            String processNo = row.getStr("组装工序", "工序编号");
            if (materialNo == null || processNo == null) {
                result.recordError(row.rowNo, "宏丰料号/工序编号", "必填项为空");
                continue;
            }
            String resourceGroupNo = "QUOTE_ASSEMBLY";
            List<Object> key = Arrays.asList(materialNo, processNo, resourceGroupNo);
            groupKeyOf.computeIfAbsent(key, k -> {
                Map<String, Object> g = new LinkedHashMap<>();
                g.put("material_no", materialNo);
                g.put("process_no", processNo);
                g.put("resource_group_no", resourceGroupNo);
                return g;
            });
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("seq_no", row.getInt("项次"));
            c.put("production_type", "BATCH_FIXED");
            c.put("fixed_cost", row.getDecimal("组装加工费"));
            c.put("currency", row.getStr("货币"));
            c.put("capacity_unit", row.getStr("计价单位"));
            c.put("default_defect_rate", row.getDecimal("拒收率", "不良率"));
            c.put("is_effective", true);
            rowOf.put(key, c);   // 后写覆盖（同组多行取最后一行）
            result.successRows++;
        }
        for (Map.Entry<List<Object>, Map<String, Object>> e : rowOf.entrySet()) {
            try {
                writer.writeVersionedGroup(new VersionedGroupSpec(
                    "capacity", "calc_version", groupKeyOf.get(e.getKey()), CONTENT, List.of(e.getValue())));
                result.recordWrite("capacity", 1);
            } catch (Exception ex) {
                result.recordError(0, "_group_", ex.getMessage());
            }
        }
        return result;
    }
}
