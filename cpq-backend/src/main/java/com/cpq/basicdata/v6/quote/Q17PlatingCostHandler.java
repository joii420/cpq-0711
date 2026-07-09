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

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Q17 电镀费用 → unit_price 一行拆两条 (cost_type=电镀加工费 + 电镀材料费)。
 *
 * <p>版本化（Task 3）：每个 cost_type 独立成组，groupKey=(QUOTE, customer_no, MATERIAL, cost_type, code)，
 * content=[pricing_price, currency, unit, defect_rate]。
 * <ul>
 *   <li>规则：电镀方案编号不为空 → 整行跳过（由系统按电镀方案计算）。</li>
 *   <li>决策⑨：**忽略 Excel「版本编号」列**，version_no 由 writeVersionedGroup 系统生成。</li>
 *   <li>假设每个 code 一行（电镀费用单值）；同 code 多行会因无 seq 维度撞唯一键（数据质量问题）。</li>
 * </ul>
 */
@ApplicationScoped
public class Q17PlatingCostHandler implements SheetHandler {

    @Inject VersionedV6Writer writer;

    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "cpq.v6import-setbased-writer", defaultValue = "false")
    boolean setBased;

    @Override public String sheetName() { return "电镀费用"; }

    private static final List<String> CONTENT = List.of("pricing_price", "currency", "unit", "defect_rate");

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public SheetImportResult handle(List<SheetRow> rows, ImportContext ctx) {
        SheetImportResult result = new SheetImportResult(sheetName());
        // key=(cost_type, code) → (groupKey map, content rows)
        Map<List<Object>, Map<String, Object>> groupKeyOf = new LinkedHashMap<>();
        Map<List<Object>, List<Map<String, Object>>> contentOf = new LinkedHashMap<>();

        for (SheetRow row : rows) {
            result.totalRows++;
            String code = row.getStr("销售料号", "宏丰料号");
            if (code == null) { result.recordError(row.rowNo, "宏丰料号", "为空"); continue; }
            String platingSchemeNo = row.getStr("电镀方案编号");
            if (platingSchemeNo != null && !platingSchemeNo.isBlank()) {
                result.successRows++;   // 整行跳过（成功跳过不算失败）
                continue;
            }
            // 忽略 Excel「版本编号」列（决策⑨）
            BigDecimal processFee = row.getDecimal("电镀加工费");
            BigDecimal materialFee = row.getDecimal("电镀材料费");
            String currency = row.getStr("货币");
            String unit = row.getStr("计价单位");
            BigDecimal defectRate = row.getDecimal("不良率");

            accumulate(groupKeyOf, contentOf, ctx, "电镀加工费", code,
                processFee != null ? processFee : BigDecimal.ZERO, currency, unit, defectRate);
            accumulate(groupKeyOf, contentOf, ctx, "电镀材料费", code,
                materialFee != null ? materialFee : BigDecimal.ZERO, currency, unit, defectRate);
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

    private void accumulate(Map<List<Object>, Map<String, Object>> groupKeyOf,
                            Map<List<Object>, List<Map<String, Object>>> contentOf,
                            ImportContext ctx, String costType, String code,
                            BigDecimal pricingPrice, String currency, String unit, BigDecimal defectRate) {
        List<Object> key = Arrays.asList(costType, code);
        groupKeyOf.computeIfAbsent(key, k -> {
            Map<String, Object> g = new LinkedHashMap<>();
            g.put("system_type", "QUOTE");
            g.put("customer_no", ctx.customerNo);
            g.put("price_type", "PLATING");
            g.put("cost_type", costType);
            g.put("code", code);
            return g;
        });
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("pricing_price", pricingPrice);
        c.put("currency", currency);
        c.put("unit", unit);
        c.put("defect_rate", defectRate);
        contentOf.computeIfAbsent(key, k -> new ArrayList<>()).add(c);
    }
}
