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
 * Q01 元素单价 → unit_price (system=QUOTE, price=ELEMENT, cost=元素价格)。
 *
 * <p>版本化（Task 3）：groupKey=(QUOTE, customer_no, ELEMENT, 元素价格, code)，
 * content=[source_url, source_name, fetch_rule, premium_fee, currency, unit]。
 * <p>**seq_no / customer_name 丢列**（设计 §四未列；已确认无下游消费）。元素单价单值/code。
 */
@ApplicationScoped
public class Q01ElementPriceHandler implements SheetHandler {

    @Inject VersionedV6Writer writer;

    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "cpq.v6import-setbased-writer", defaultValue = "false")
    boolean setBased;

    @Override public String sheetName() { return "元素单价"; }

    private static final List<String> CONTENT = List.of(
        "source_url", "source_name", "fetch_rule", "premium_fee", "currency", "unit");

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public SheetImportResult handle(List<SheetRow> rows, ImportContext ctx) {
        SheetImportResult result = new SheetImportResult(sheetName());
        Map<List<Object>, Map<String, Object>> groupKeyOf = new LinkedHashMap<>();
        Map<List<Object>, List<Map<String, Object>>> contentOf = new LinkedHashMap<>();
        for (SheetRow row : rows) {
            result.totalRows++;
            String code = row.getStr("单个元素", "所有元素", "元素代码", "元素名称");
            if (code == null) { result.recordError(row.rowNo, "元素代码", "列值为空"); continue; }
            List<Object> key = Arrays.asList(code);
            groupKeyOf.computeIfAbsent(key, k -> {
                Map<String, Object> g = new LinkedHashMap<>();
                g.put("system_type", "QUOTE");
                g.put("customer_no", ctx.customerNo);
                g.put("price_type", "ELEMENT");
                g.put("cost_type", "元素价格");
                g.put("code", code);
                return g;
            });
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("source_url", row.getStr("网址"));
            c.put("source_name", row.getStr("网站名称"));
            c.put("fetch_rule", row.getStr("取用规则"));
            c.put("premium_fee", row.getDecimal("升水价", "手续费"));
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
