package com.cpq.basicdata.v6.pricing;

import com.cpq.basicdata.v6.parser.ImportContext;
import com.cpq.basicdata.v6.parser.SheetHandler;
import com.cpq.basicdata.v6.parser.SheetImportResult;
import com.cpq.basicdata.v6.parser.SheetRow;
import com.cpq.basicdata.v6.util.DecimalScale;
import com.cpq.basicdata.v6.versioning.VersionedV6Writer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.util.*;

/**
 * P02 材料核价价格表 → unit_price (PRICING/MATERIAL_PRICE/材料核价价格) 整组版本化。
 * <p>版本按 材料料号 分组，忽略 Excel「材料价格版本」列，由 {@link VersionedV6Writer} 系统自增
 * （首版 2000，任一内容列变化即整组升版）。详见 §5.1 A 组。
 */
@ApplicationScoped
public class P02MaterialPricingPriceHandler implements SheetHandler {

    @Inject VersionedV6Writer writer;

    @Override public String sheetName() { return "材料核价价格表"; }

    /**
     * 模板表头（task-0728 · B4）：逐列抄自权威导入文件的第 1 行，<b>列序原样保留</b>——
     * {@code SheetRow.getStr} 按「列序 + contains」匹配，换序会读错列。
     */
    private static final List<String> TEMPLATE_HEADERS = List.of(
        "材料料号", "品名", "规格", "尺寸", "核价单价", "市场参考价", "参考价来源网址", "网站名称", "参考价取用规则", "币种", "计量单位", "回收折扣（%）",
        "材料价格版本");

    @Override public List<String> templateHeaders() { return TEMPLATE_HEADERS; }

    private static final List<String> CONTENT = List.of(
        "pricing_price", "market_ref_price", "source_url", "source_name",
        "fetch_rule", "currency", "unit", "recovery_discount");

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public SheetImportResult handle(List<SheetRow> rows, ImportContext ctx) {
        SheetImportResult result = new SheetImportResult(sheetName());
        Map<String, List<Map<String, Object>>> byCode = new LinkedHashMap<>();
        for (SheetRow row : rows) {
            result.totalRows++;
            String code = row.getStr("材料料号");
            if (code == null) { result.recordError(row.rowNo, "材料料号", "为空"); continue; }
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("pricing_price", DecimalScale.at(row.getDecimal("核价单价"), 6));
            c.put("market_ref_price", DecimalScale.at(row.getDecimal("市场参考价"), 6));
            c.put("source_url", row.getStr("参考价来源网址", "网址"));
            c.put("source_name", row.getStr("网站名称"));
            c.put("fetch_rule", row.getStr("参考价取用规则", "取用规则"));
            c.put("currency", row.getStr("币种"));
            c.put("unit", row.getStr("计量单位"));
            c.put("recovery_discount", DecimalScale.at(row.getDecimal("回收折扣"), 4));
            byCode.computeIfAbsent(code, k -> new ArrayList<>()).add(c);
            result.successRows++;
        }
        LinkedHashMap<Map<String, Object>, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        for (Map.Entry<String, List<Map<String, Object>>> e : byCode.entrySet()) {
            Map<String, Object> gk = new LinkedHashMap<>();
            gk.put("system_type", "PRICING");
            gk.put("price_type", PricingPriceType.MATERIAL_PRICE);
            gk.put("cost_type", "材料核价价格");
            gk.put("code", e.getKey());
            groups.put(gk, e.getValue());
        }
        try {
            writer.writeVersionedGroups("unit_price", "version_no", CONTENT, null, groups);
            for (List<Map<String, Object>> g : groups.values()) result.recordWrite("unit_price", g.size());
        } catch (Exception ex) {
            result.recordError(0, "_batch_", ex.getMessage());
        }
        return result;
    }
}
