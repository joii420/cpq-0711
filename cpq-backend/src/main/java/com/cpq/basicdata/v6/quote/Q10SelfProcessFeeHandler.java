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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Q10 自制加工费 → unit_price (price=PROCESS, cost=自制加工费)。
 *
 * <p>版本化（Task 3）：groupKey=(QUOTE, customer_no, MATERIAL, 自制加工费, code, finished_material_no, operation_no)，
 * content=[seq_no, pricing_price, cost_ratio, currency, unit]。
 */
@ApplicationScoped
public class Q10SelfProcessFeeHandler implements SheetHandler {

    @Inject VersionedV6Writer writer;

    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "cpq.v6import-setbased-writer", defaultValue = "false")
    boolean setBased;

    @Override public String sheetName() { return "自制加工费"; }

    private static final List<String> CONTENT = List.of(
        "seq_no", "pricing_price", "cost_ratio", "currency", "unit");

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public SheetImportResult handle(List<SheetRow> rows, ImportContext ctx) {
        SheetImportResult result = new SheetImportResult(sheetName());
        Map<List<Object>, Map<String, Object>> groupKeyOf = new LinkedHashMap<>();
        Map<List<Object>, List<Map<String, Object>>> contentOf = new LinkedHashMap<>();
        // §10 规则3 fail-fast：已用「宏丰料号」兜底过 code 的成品集合（本次导入内）。
        // unit_price 唯一键不含 operation_no → 同一成品多条无投入料号行会塌缩撞键，故只允许一条。
        Set<String> noInputFallbackFinished = new HashSet<>();
        for (SheetRow row : rows) {
            result.totalRows++;
            // task-0717 扩围:投入料号=材质料号,恒按材质处理——原始码作 code,不 resolve/不铸号、
            // 不登记 material_customer_map、不登记 material_master(名走 material_recipe 兜底)。
            // §10 规则3 保留：投入料号为空是既有业务功能（整体加工费兜底成品料号），不受本次扩围影响，
            // 触发条件从「resolve() 抛异常」改为直接判断 raw.isBlank()（不再依赖已删除的按名 resolve/铸号机制）。
            String raw = row.exact("投入料号");
            String finishedMaterialNo = row.getStr("销售料号", "宏丰料号", "成品料号");
            String code;
            if (raw != null && !raw.isBlank()) {
                code = raw;                          // 投入料号=材质,原始码,不 resolve/不铸号/不登记
            } else {
                if (finishedMaterialNo == null) {
                    result.recordError(row.rowNo, "投入料号", "投入料号、宏丰料号均为空，无法确定料号"); continue;
                }
                if (!noInputFallbackFinished.add(finishedMaterialNo)) {
                    result.recordError(row.rowNo, "投入料号",
                        "成品 " + finishedMaterialNo + " 存在多条无投入料号的自制加工费，数据非法"); continue;
                }
                code = finishedMaterialNo;
            }
            final String resolvedCode = code;        // 保留,下游 lambda 捕获用
            String operationNo = row.getStr("工序编号");
            List<Object> key = Arrays.asList(resolvedCode, finishedMaterialNo, operationNo);
            groupKeyOf.computeIfAbsent(key, k -> {
                Map<String, Object> g = new LinkedHashMap<>();
                g.put("system_type", "QUOTE");
                g.put("customer_no", ctx.customerNo);
                g.put("price_type", "PROCESS");
                g.put("cost_type", "自制加工费");
                g.put("code", resolvedCode);
                g.put("finished_material_no", finishedMaterialNo);
                g.put("operation_no", operationNo);
                return g;
            });
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("seq_no", row.getInt("项次（一级）", "项次"));
            c.put("pricing_price", row.getDecimal("值"));   // 固定金额写值；比例费用留 NULL（D1）
            c.put("cost_ratio", row.getDecimal("比例"));
            c.put("currency", row.getStr("货币"));
            c.put("unit", row.getStr("计价单位"));
            contentOf.computeIfAbsent(key, k -> new ArrayList<>()).add(c);
            result.successRows++;
        }
        if (setBased) {
            LinkedHashMap<Map<String, Object>, List<Map<String, Object>>> groups = new LinkedHashMap<>();
            for (Map.Entry<List<Object>, List<Map<String, Object>>> e : contentOf.entrySet())
                groups.put(groupKeyOf.get(e.getKey()), e.getValue());
            try {
                writer.writeVersionedGroups("unit_price", "version_no", CONTENT, null, groups);
                for (List<Map<String, Object>> groupRows : groups.values())
                    result.recordWrite("unit_price", groupRows.size());
            } catch (Exception ex) {
                result.recordError(0, "_batch_", ex.getMessage());
            }
        } else {
            for (Map.Entry<List<Object>, List<Map<String, Object>>> e : contentOf.entrySet()) {
                try {
                    writer.writeVersionedGroup(new VersionedGroupSpec(
                        "unit_price", "version_no", groupKeyOf.get(e.getKey()), CONTENT, e.getValue()));
                    result.recordWrite("unit_price", e.getValue().size());
                } catch (Exception ex) {
                    result.recordError(0, "_group_", ex.getMessage());
                }
            }
        }
        return result;
    }
}
