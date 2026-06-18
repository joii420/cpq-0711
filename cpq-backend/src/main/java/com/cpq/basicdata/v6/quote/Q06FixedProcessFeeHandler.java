package com.cpq.basicdata.v6.quote;

import com.cpq.basicdata.v6.parser.ImportContext;
import com.cpq.basicdata.v6.parser.SheetHandler;
import com.cpq.basicdata.v6.parser.SheetImportResult;
import com.cpq.basicdata.v6.parser.SheetRow;
import com.cpq.basicdata.v6.repository.MaterialMasterRepository;
import com.cpq.basicdata.v6.service.MaterialNoResolver;
import com.cpq.basicdata.v6.service.MaterialNoUnresolvableException;
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
 * Q06 来料固定加工费 → unit_price (price=INCOMING_MATERIAL_PROCESS, cost=来料加工费)。
 *
 * <p>版本化（实现计划 Task 3）：按 groupKey = (system_type=QUOTE, customer_no, price_type=INCOMING_MATERIAL_PROCESS,
 * cost_type=来料加工费, code, finished_material_no) 分组，组内多行（按 seq_no 区分）整组走
 * {@link VersionedV6Writer#writeVersionedGroup}：指纹相同复用版本、不同则 version_no max+1 + is_current 翻转。
 * <p>列保全：本 sheet 写的列（code/finished_material_no + 8 个 content 列）全部落在 groupKey∪content 内，无丢列。
 */
@ApplicationScoped
public class Q06FixedProcessFeeHandler implements SheetHandler {

    @Inject VersionedV6Writer writer;
    @Inject MaterialNoResolver materialNoResolver;
    @Inject MaterialMasterRepository materialMasterRepo;

    @Override public String sheetName() { return "来料固定加工费"; }

    private static final List<String> CONTENT = List.of(
        "seq_no", "base_value", "cost_ratio", "currency", "unit",
        "is_fluctuate_with_material", "material_increase_ratio", "material_fixed_increase");

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public SheetImportResult handle(List<SheetRow> rows, ImportContext ctx) {
        SheetImportResult result = new SheetImportResult(sheetName());

        // 1) 按 groupKey 聚合：key=(code, finished_material_no) → (groupKey map, content rows)
        Map<List<Object>, Map<String, Object>> groupKeyOf = new LinkedHashMap<>();
        Map<List<Object>, List<Map<String, Object>>> contentOf = new LinkedHashMap<>();
        MaterialNoResolver.BatchState batch = new MaterialNoResolver.BatchState();
        for (SheetRow row : rows) {
            result.totalRows++;
            String inputName = row.exact("投入料号名称");
            String code;
            try {
                code = materialNoResolver.resolve(row.exact("投入料号"), inputName, batch);
            } catch (MaterialNoUnresolvableException ex) {
                result.recordError(row.rowNo, "投入料号", "料号与名称均为空"); continue;
            }
            materialMasterRepo.upsertByMaterialNo(code, inputName,
                null, null, null, "组成件", null, null, null, ctx.importedBy, true);
            result.recordWrite("material_master", 1);
            String finishedMaterialNo = row.getStr("宏丰料号", "成品料号");
            List<Object> key = Arrays.asList(code, finishedMaterialNo);

            groupKeyOf.computeIfAbsent(key, k -> {
                Map<String, Object> g = new LinkedHashMap<>();
                g.put("system_type", "QUOTE");
                g.put("customer_no", ctx.customerNo);
                g.put("price_type", "INCOMING_MATERIAL_PROCESS");
                g.put("cost_type", "来料加工费");
                g.put("code", code);
                g.put("finished_material_no", finishedMaterialNo);
                return g;
            });

            Map<String, Object> c = new LinkedHashMap<>();
            c.put("seq_no", row.getInt("项次"));
            c.put("base_value", row.getDecimal("基准值"));
            c.put("cost_ratio", row.getDecimal("比例"));
            c.put("currency", row.getStr("货币"));
            c.put("unit", row.getStr("计价单位"));
            c.put("is_fluctuate_with_material", row.getBool("是否随材料价格波动"));
            c.put("material_increase_ratio", row.getDecimal("材料结算涨幅比例"));
            c.put("material_fixed_increase", row.getDecimal("材料固定的涨幅值"));
            contentOf.computeIfAbsent(key, k -> new ArrayList<>()).add(c);
            result.successRows++;
        }

        // 2) 每组走版本化写入
        for (Map.Entry<List<Object>, List<Map<String, Object>>> e : contentOf.entrySet()) {
            try {
                writer.writeVersionedGroup(new VersionedGroupSpec(
                    "unit_price", "version_no", groupKeyOf.get(e.getKey()), CONTENT, e.getValue()));
                result.recordWrite("unit_price", e.getValue().size());
            } catch (Exception ex) {
                result.recordError(0, "_group_", ex.getMessage());
            }
        }
        return result;
    }
}
