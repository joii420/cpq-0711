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

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * P22 电镀成本 → unit_price (PRICING/PLATING) 一行拆两条(电镀加工费+电镀材料费) 整批版本化
 * （tesk-0709 Task 9）。
 *
 * <p>groupKey = {system_type:"PRICING", price_type:"PLATING", code(销售料号)}；同一销售料号的
 * 加工费/材料费两条 cost_type 记录共享一个版本，任一变化整组一起升版。content = [cost_type,
 * pricing_price, currency, unit, defect_rate]；production_no 为描述列。忽略 Excel「版本编号」列，
 * 交给 {@link VersionedV6Writer} 系统自增（2000 起），与 P01/P02/P03 等 Task 9 系列一致。
 *
 * <p>电镀方案引用行（"电镀方案编号"非空）视为非本 Sheet 主体数据，跳过不落 unit_price（沿用原逻辑）。
 * <p>组内去重键 = cost_type：同批同料号若出现多行，同 cost_type 取最后一行（末值覆盖）。
 */
@ApplicationScoped
public class P22PlatingCostHandler implements SheetHandler {

    @Inject VersionedV6Writer writer;

    @Override public String sheetName() { return "电镀成本"; }

    private static final List<String> CONTENT = List.of(
        "cost_type", "pricing_price", "currency", "unit", "defect_rate");
    private static final List<String> DESCRIPTOR = List.of("production_no");

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public SheetImportResult handle(List<SheetRow> rows, ImportContext ctx) {
        SheetImportResult result = new SheetImportResult(sheetName());

        // code(销售料号) -> cost_type(电镀加工费/电镀材料费) -> content row（末值覆盖）
        Map<String, LinkedHashMap<String, Map<String, Object>>> byCode = new LinkedHashMap<>();

        for (SheetRow row : rows) {
            result.totalRows++;
            String code = row.getStr("销售料号", "宏丰料号");
            if (code == null) { result.recordError(row.rowNo, "宏丰料号", "为空"); continue; }
            String platingSchemeNo = row.getStr("电镀方案编号");
            if (platingSchemeNo != null && !platingSchemeNo.isBlank()) {
                result.successRows++; continue;
            }
            BigDecimal processFee = DecimalScale.at(row.getDecimal("电镀加工费"), 6);
            BigDecimal materialFee = DecimalScale.at(row.getDecimal("电镀材料费"), 6);
            String currency = row.getStr("货币");
            String unit = row.getStr("计价单位");
            BigDecimal defectRate = DecimalScale.at(row.getDecimal("不良率"), 4);
            String productionNo = row.getStr("生产料号");

            LinkedHashMap<String, Map<String, Object>> group =
                byCode.computeIfAbsent(code, k -> new LinkedHashMap<>());

            Map<String, Object> c1 = new LinkedHashMap<>();
            c1.put("cost_type", "电镀加工费");
            c1.put("pricing_price", processFee != null ? processFee : BigDecimal.ZERO);
            c1.put("currency", currency);
            c1.put("unit", unit);
            c1.put("defect_rate", defectRate);
            c1.put("production_no", productionNo);
            group.put("电镀加工费", c1);

            Map<String, Object> c2 = new LinkedHashMap<>();
            c2.put("cost_type", "电镀材料费");
            c2.put("pricing_price", materialFee != null ? materialFee : BigDecimal.ZERO);
            c2.put("currency", currency);
            c2.put("unit", unit);
            c2.put("defect_rate", defectRate);
            c2.put("production_no", productionNo);
            group.put("电镀材料费", c2);

            result.successRows++;
        }

        LinkedHashMap<Map<String, Object>, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        for (Map.Entry<String, LinkedHashMap<String, Map<String, Object>>> e : byCode.entrySet()) {
            Map<String, Object> gk = new LinkedHashMap<>();
            gk.put("system_type", "PRICING");
            gk.put("price_type", PricingPriceType.PLATING);
            gk.put("code", e.getKey());
            groups.put(gk, new ArrayList<>(e.getValue().values()));
        }
        try {
            writer.writeVersionedGroups("unit_price", "version_no", CONTENT, null, DESCRIPTOR, groups);
            for (List<Map<String, Object>> g : groups.values()) result.recordWrite("unit_price", g.size());
        } catch (Exception ex) {
            result.recordError(0, "_batch_", ex.getMessage());
        }
        return result;
    }
}
