package com.cpq.basicdata.v6.quote;

import com.cpq.basicdata.v6.parser.ImportContext;
import com.cpq.basicdata.v6.parser.SheetHandler;
import com.cpq.basicdata.v6.parser.SheetImportResult;
import com.cpq.basicdata.v6.parser.SheetRow;
import com.cpq.basicdata.v6.repository.MaterialMasterRepository;
import com.cpq.basicdata.v6.service.MaterialNoResolver;
import com.cpq.basicdata.v6.service.MaterialNoUnresolvableException;
import com.cpq.basicdata.v6.service.PartTypeInferenceService;
import com.cpq.basicdata.v6.service.PartTypeInferenceService.InferResult;
import com.cpq.basicdata.v6.service.PartTypeInferenceService.TypeIndex;
import com.cpq.basicdata.v6.service.QuoteMaterialNoAllocator;
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
 * <p>版本化（Task 3）：每个 cost_type 独立成组，
 * groupKey=(QUOTE, customer_no, PLATING, cost_type, code, finished_material_no)，
 * content=[pricing_price, currency, unit, defect_rate]。
 * <ul>
 *   <li>规则：电镀方案编号不为空 → 整行跳过（由系统按电镀方案计算）。</li>
 *   <li>决策⑨：**忽略 Excel「版本编号」列**，version_no 由 writeVersionedGroup 系统生成。</li>
 *   <li>repair-0802：{@code code} = 投入料号(零件料号)、{@code finished_material_no} = 销售料号(成品)，
 *       与 Q06/Q07/Q13 及 unit_price 全表口径一致（见
 *       {@code dev-docs/rule-0724-组件模板配置/4-页签属性与树.md} §零件）。
 *       「投入料号」「投入料号名称」**均非必填**：有码沿用原始码（不 resolve/不铸号）；
 *       只有名称则按 {@link TypeIndex} 推断类型后反查/铸号；**两者皆空回退为销售料号**
 *       （语义=电镀针对成品自身，与 Q15 组装加工费年降的退化范式一致），此时不得报错。</li>
 *   <li>同一销售料号下可有多个投入料号，各自独立成行（groupKey 含两个料号维度）。</li>
 * </ul>
 */
@ApplicationScoped
public class Q17PlatingCostHandler implements SheetHandler {

    @Inject VersionedV6Writer writer;
    @Inject MaterialNoResolver materialNoResolver;
    @Inject MaterialMasterRepository materialMasterRepo;

    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "cpq.v6import-setbased-writer", defaultValue = "false")
    boolean setBased;

    @Override public String sheetName() { return "电镀费用"; }

    private static final List<String> CONTENT = List.of("pricing_price", "currency", "unit", "defect_rate");

    @Override
    @Transactional(Transactional.TxType.MANDATORY)
    public SheetImportResult handle(List<SheetRow> rows, ImportContext ctx) {
        SheetImportResult result = new SheetImportResult(sheetName());
        TypeIndex typeIndex = (TypeIndex) ctx.sharedCache.get("partTypeIndex");
        MaterialNoResolver.BatchState batch = MaterialNoResolver.batchStateFor(ctx);
        Map<String, String[]> mmAcc = new LinkedHashMap<>();
        // key=(cost_type, code, finished_material_no) → (groupKey map, content rows)
        Map<List<Object>, Map<String, Object>> groupKeyOf = new LinkedHashMap<>();
        Map<List<Object>, List<Map<String, Object>>> contentOf = new LinkedHashMap<>();

        for (SheetRow row : rows) {
            result.totalRows++;
            String finishedMaterialNo = row.getStr("销售料号", "宏丰料号");
            if (finishedMaterialNo == null) { result.recordError(row.rowNo, "宏丰料号", "为空"); continue; }
            String platingSchemeNo = row.getStr("电镀方案编号");
            if (platingSchemeNo != null && !platingSchemeNo.isBlank()) {
                result.successRows++;   // 整行跳过（成功跳过不算失败）
                continue;
            }
            // repair-0802：投入料号(非必填)三分支。exact 而非 getStr —— 后者 contains 会命中「投入料号名称」。
            String rawNo = row.exact("投入料号");
            String rawName = row.exact("投入料号名称");
            String code;
            if (rawNo != null && !rawNo.isBlank()) {
                code = rawNo;   // 有码：沿用原始码，不 resolve/不铸号（对齐 Q06/Q07 U10 §6.1 第 1 条）
            } else if (rawName != null && !rawName.isBlank()) {
                InferResult infer = typeIndex != null ? typeIndex.infer(null, rawName)
                    : new InferResult(PartTypeInferenceService.ASSEMBLY, PartTypeInferenceService.Source.DEFAULT);
                String characteristic = infer.characteristic();
                if (PartTypeInferenceService.RECIPE.equals(characteristic)) {
                    code = typeIndex.resolveRecipeCode(null, rawName);
                    if (code == null) {
                        result.recordError(row.rowNo, "投入料号名称", "未找到材质「" + rawName + "」");
                        continue;
                    }
                } else {
                    try {
                        code = materialNoResolver.resolve(null, rawName, batch);
                    } catch (MaterialNoUnresolvableException ex) {
                        result.recordError(row.rowNo, "投入料号名称", "无法解析料号"); continue;
                    } catch (QuoteMaterialNoAllocator.CrossCustomerQuoteNoException ex) {
                        result.recordError(row.rowNo, "投入料号名称", "报价料号跨客户串号"); continue;
                    }
                    String materialType = PartTypeInferenceService.OUTSOURCED.equals(characteristic) ? "外购件" : "零件";
                    MaterialMasterRepository.accNameType(mmAcc, code, rawName, materialType);
                    result.recordWrite("material_master", 1);
                }
            } else {
                // 两列皆空（非必填）→ 回退为销售料号，语义=电镀针对成品自身。不报错。
                code = finishedMaterialNo;
            }

            // 忽略 Excel「版本编号」列（决策⑨）
            BigDecimal processFee = row.getDecimal("电镀加工费");
            BigDecimal materialFee = row.getDecimal("电镀材料费");
            String currency = row.getStr("货币");
            String unit = row.getStr("计价单位");
            BigDecimal defectRate = row.getDecimal("不良率");

            accumulate(groupKeyOf, contentOf, ctx, "电镀加工费", code, finishedMaterialNo,
                processFee != null ? processFee : BigDecimal.ZERO, currency, unit, defectRate);
            accumulate(groupKeyOf, contentOf, ctx, "电镀材料费", code, finishedMaterialNo,
                materialFee != null ? materialFee : BigDecimal.ZERO, currency, unit, defectRate);
            result.successRows++;
        }

        if (!mmAcc.isEmpty()) {
            List<MaterialMasterRepository.NameTypeRow> mmRows = new ArrayList<>(mmAcc.size());
            for (Map.Entry<String, String[]> e : mmAcc.entrySet()) {
                mmRows.add(new MaterialMasterRepository.NameTypeRow(e.getKey(), e.getValue()[0], e.getValue()[1]));
            }
            materialMasterRepo.upsertBatchNameType(mmRows, ctx.importedBy, true, ctx.pendingQuotationId);
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

    private void accumulate(Map<List<Object>, Map<String, Object>> groupKeyOf,
                            Map<List<Object>, List<Map<String, Object>>> contentOf,
                            ImportContext ctx, String costType, String code, String finishedMaterialNo,
                            BigDecimal pricingPrice, String currency, String unit, BigDecimal defectRate) {
        List<Object> key = Arrays.asList(costType, code, finishedMaterialNo);
        groupKeyOf.computeIfAbsent(key, k -> {
            Map<String, Object> g = new LinkedHashMap<>();
            g.put("system_type", "QUOTE");
            g.put("customer_no", ctx.customerNo);
            g.put("price_type", "PLATING");
            g.put("cost_type", costType);
            g.put("code", code);
            g.put("finished_material_no", finishedMaterialNo);
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
