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
 * <p>groupKey = {system_type:"PRICING", price_type:"PLATING", finished_material_no(销售料号)}；
 * 同一销售料号下的全部投入料号 × 加工费/材料费两个 cost_type 共享一个版本组，任一变化整组一起升版
 * （与 P15 来料加工费同构）。content = [code(投入料号), cost_type, pricing_price, currency, unit,
 * defect_rate]；production_no 为描述列。忽略 Excel「版本编号」列，交给 {@link VersionedV6Writer}
 * 系统自增（2000 起）。
 *
 * <p>repair-0802：「投入料号」「投入料号名称」均非必填。code 取投入料号（零件料号），空则回退为
 * 销售料号（语义=电镀针对成品自身）；名称列不落库、不参与解析（与 P15/P16/P17 的「品名」一致，
 * 渲染取名由视图 JOIN material_master 负责）。
 *
 * <p>电镀方案引用行（"电镀方案编号"非空）视为非本 Sheet 主体数据，跳过不落 unit_price（沿用原逻辑）。
 * <p>组内去重键 = (code, cost_type)：同批同料号同投入料号若出现多行，取最后一行（末值覆盖）。
 */
@ApplicationScoped
public class P22PlatingCostHandler implements SheetHandler {

    @Inject VersionedV6Writer writer;

    @Override public String sheetName() { return "电镀成本"; }

    /**
     * 模板表头（task-0728 · B4）：逐列抄自权威导入文件的第 1 行，<b>列序原样保留</b>——
     * {@code SheetRow.getStr} 按「列序 + contains」匹配，换序会读错列。
     */
    private static final List<String> TEMPLATE_HEADERS = List.of(
        "生产料号", "销售料号", "投入料号", "投入料号名称", "电镀方案编号", "版本编号",
        "电镀加工费", "电镀材料费", "货币", "计价单位", "不良率（%）");

    @Override public List<String> templateHeaders() { return TEMPLATE_HEADERS; }

    private static final List<String> CONTENT = List.of(
        "code", "cost_type", "pricing_price", "currency", "unit", "defect_rate");
    private static final List<String> DESCRIPTOR = List.of("production_no");

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public SheetImportResult handle(List<SheetRow> rows, ImportContext ctx) {
        SheetImportResult result = new SheetImportResult(sheetName());

        // finished_material_no(销售料号) -> "code|cost_type" -> content row（末值覆盖）
        Map<String, LinkedHashMap<String, Map<String, Object>>> byFinishedMaterial = new LinkedHashMap<>();

        for (SheetRow row : rows) {
            result.totalRows++;
            String finishedMaterialNo = row.getStr("销售料号", "宏丰料号");
            if (finishedMaterialNo == null) { result.recordError(row.rowNo, "销售料号", "为空"); continue; }
            String platingSchemeNo = row.getStr("电镀方案编号");
            if (platingSchemeNo != null && !platingSchemeNo.isBlank()) {
                result.successRows++; continue;
            }
            // repair-0802：投入料号非必填。核价侧不做名称反查/铸号（与 P15/P16/P17 的「品名」列一致），
            // 空则回退为销售料号（语义=电镀针对成品自身）。exact 而非 getStr——后者 contains 会命中「投入料号名称」。
            String rawInputNo = row.exact("投入料号");
            String code = (rawInputNo != null && !rawInputNo.isBlank()) ? rawInputNo : finishedMaterialNo;

            BigDecimal processFee = DecimalScale.at(row.getDecimal("电镀加工费"), 12);
            BigDecimal materialFee = DecimalScale.at(row.getDecimal("电镀材料费"), 12);
            String currency = row.getStr("货币");
            String unit = row.getStr("计价单位");
            BigDecimal defectRate = DecimalScale.at(row.getDecimal("不良率"), 12);
            String productionNo = row.getStr("生产料号");

            LinkedHashMap<String, Map<String, Object>> group =
                byFinishedMaterial.computeIfAbsent(finishedMaterialNo, k -> new LinkedHashMap<>());

            Map<String, Object> c1 = new LinkedHashMap<>();
            c1.put("code", code);
            c1.put("cost_type", "电镀加工费");
            c1.put("pricing_price", processFee != null ? processFee : BigDecimal.ZERO);
            c1.put("currency", currency);
            c1.put("unit", unit);
            c1.put("defect_rate", defectRate);
            c1.put("production_no", productionNo);
            group.put(code + "|电镀加工费", c1);

            Map<String, Object> c2 = new LinkedHashMap<>();
            c2.put("code", code);
            c2.put("cost_type", "电镀材料费");
            c2.put("pricing_price", materialFee != null ? materialFee : BigDecimal.ZERO);
            c2.put("currency", currency);
            c2.put("unit", unit);
            c2.put("defect_rate", defectRate);
            c2.put("production_no", productionNo);
            group.put(code + "|电镀材料费", c2);

            result.successRows++;
        }

        LinkedHashMap<Map<String, Object>, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        for (Map.Entry<String, LinkedHashMap<String, Map<String, Object>>> e : byFinishedMaterial.entrySet()) {
            Map<String, Object> gk = new LinkedHashMap<>();
            gk.put("system_type", "PRICING");
            gk.put("price_type", PricingPriceType.PLATING);
            gk.put("finished_material_no", e.getKey());
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
