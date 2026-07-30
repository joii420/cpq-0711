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
 * Q09 来料回收折扣 → unit_price (price=INCOMING_MATERIAL_RECYCLE, cost=回收折扣)。
 *
 * <p>版本化（Task 3）：groupKey=(QUOTE, customer_no, MATERIAL, 回收折扣, code, finished_material_no)，
 * content=[seq_no, cost_ratio, pricing_price, currency, unit]。
 * <p>update-0723 B5（U10）：有码沿用原始码（不 resolve，行为不变）；只有名称时补名称反查——按
 * {@link TypeIndex} 推断类型，材质走 material_recipe 按名查码（查无报错「未找到材质」），
 * 零件/外购件走 {@link MaterialNoResolver}（按名查 material_master 或发号，共享全导入 BatchState，R2）。
 *
 * <p><b>task-0730：新增 项次 / 值 / 货币 / 计价单位 四列</b>
 * <ul>
 *   <li>{@code 项次 → seq_no}：<b>不必填、不补号</b>——空即 NULL 落库。</li>
 *   <li>{@code 值 → pricing_price} 与 {@code 回收折扣（%） → cost_ratio} <b>并存</b>（可同时有值），
 *       但<b>必填其一</b>：两者皆空即拒绝该行（Phase 1 {@code QuoteImportValidator} 已预检，此处
 *       同款兜底，保证 handler 被直接调用时语义一致）。</li>
 *   <li><b>组内 upsert（末值胜）</b>：去重键 = {@code COALESCE(seq_no, 0)}，精确镜像
 *       {@code uq_unit_price} 在本组内退化后的维度（version_no/code/finished_material_no/
 *       customer_no/cost_type 组内恒定，supplier_no/operation_no/discount_order/item_seq/
 *       effective_date 恒未设置=NULL）。同键后行覆盖前行、只落一条，<b>不报唯一键冲突</b>——
 *       对齐 {@code IncomingOtherMergeHandler} 的 EXCLUDED 覆盖语义。注意 NULL 与 0 视为同一键
 *       （与 uq 的 COALESCE 表达式一致），否则"项次留空多行"仍会撞键。</li>
 * </ul>
 */
@ApplicationScoped
public class Q09IncomingRecoveryHandler implements SheetHandler {

    @Inject VersionedV6Writer writer;
    @Inject MaterialNoResolver materialNoResolver;
    @Inject MaterialMasterRepository materialMasterRepo;

    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "cpq.v6import-setbased-writer", defaultValue = "false")
    boolean setBased;

    @Override public String sheetName() { return "来料回收折扣"; }

    private static final List<String> CONTENT =
        List.of("seq_no", "cost_ratio", "pricing_price", "currency", "unit");

    /** 组内去重键：镜像 {@code uq_unit_price} 的 {@code COALESCE(seq_no,0)}——NULL 与 0 同键。 */
    private static Integer dedupKey(Integer seqNo) {
        return seqNo == null ? Integer.valueOf(0) : seqNo;
    }

    @Override
    @Transactional(Transactional.TxType.MANDATORY)
    public SheetImportResult handle(List<SheetRow> rows, ImportContext ctx) {
        SheetImportResult result = new SheetImportResult(sheetName());
        TypeIndex typeIndex = (TypeIndex) ctx.sharedCache.get("partTypeIndex");
        MaterialNoResolver.BatchState batch = MaterialNoResolver.batchStateFor(ctx);
        Map<String, String[]> mmAcc = new LinkedHashMap<>();
        Map<List<Object>, Map<String, Object>> groupKeyOf = new LinkedHashMap<>();
        // 组 → (去重键 COALESCE(seq_no,0) → content 行)；同键后行覆盖前行（末值胜 = 组内 upsert）
        Map<List<Object>, LinkedHashMap<Integer, Map<String, Object>>> contentOf = new LinkedHashMap<>();
        for (SheetRow row : rows) {
            result.totalRows++;
            String raw = row.exact("投入料号");
            String rawName = row.exact("投入料号名称");
            if ((raw == null || raw.isBlank()) && (rawName == null || rawName.isBlank())) {
                result.recordError(row.rowNo, "投入料号", "料号与名称均为空"); continue;
            }
            // task-0730：值与回收折扣（%）必填其一（并存允许，同时为空拒绝）。Phase 1 已预检，此处兜底。
            BigDecimal costRatio = row.getDecimal("回收折扣");
            BigDecimal pricingPrice = row.getDecimal("值");
            if (costRatio == null && pricingPrice == null) {
                result.recordError(row.rowNo, "值/回收折扣（%）", "必填其一，不能同时为空"); continue;
            }
            String code;
            if (raw != null && !raw.isBlank()) {
                code = raw;   // 有码：沿用原始码，不 resolve/不铸号（行为不变）
            } else {
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
                        result.recordError(row.rowNo, "投入料号名称", "料号与名称均为空"); continue;
                    } catch (QuoteMaterialNoAllocator.CrossCustomerQuoteNoException ex) {
                        result.recordError(row.rowNo, "投入料号名称", "报价料号跨客户串号"); continue;
                    }
                    String materialType = PartTypeInferenceService.OUTSOURCED.equals(characteristic) ? "外购件" : "零件";
                    MaterialMasterRepository.accNameType(mmAcc, code, rawName, materialType);
                    result.recordWrite("material_master", 1);
                }
            }
            String finishedMaterialNo = row.getStr("销售料号", "宏丰料号", "成品料号");
            List<Object> key = Arrays.asList(code, finishedMaterialNo);
            groupKeyOf.computeIfAbsent(key, k -> {
                Map<String, Object> g = new LinkedHashMap<>();
                g.put("system_type", "QUOTE");
                g.put("customer_no", ctx.customerNo);
                g.put("price_type", "INCOMING_MATERIAL_RECYCLE");
                g.put("cost_type", "回收折扣");
                g.put("code", code);
                g.put("finished_material_no", finishedMaterialNo);
                return g;
            });
            Integer seqNo = row.getInt("项次");            // 不必填、不补号：空即 NULL
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("seq_no", seqNo);
            c.put("cost_ratio", costRatio);
            c.put("pricing_price", pricingPrice);
            c.put("currency", row.getStr("货币"));
            c.put("unit", row.getStr("计价单位", "单位"));
            // 末值胜：同 (code, finished_material_no, COALESCE(seq_no,0)) 的后行覆盖前行
            contentOf.computeIfAbsent(key, k -> new LinkedHashMap<>()).put(dedupKey(seqNo), c);
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
            for (Map.Entry<List<Object>, LinkedHashMap<Integer, Map<String, Object>>> e : contentOf.entrySet())
                groups.put(groupKeyOf.get(e.getKey()), new ArrayList<>(e.getValue().values()));
            try {
                writer.writeVersionedGroups("unit_price", "version_no", CONTENT, null, List.of(), groups, ctx.pendingQuotationId);
                for (List<Map<String, Object>> groupRows : groups.values())
                    result.recordWrite("unit_price", groupRows.size());
            } catch (Exception ex) {
                result.recordError(0, "_batch_", ex.getMessage());
            }
        } else {
            for (Map.Entry<List<Object>, LinkedHashMap<Integer, Map<String, Object>>> e : contentOf.entrySet()) {
                List<Map<String, Object>> newRows = new ArrayList<>(e.getValue().values());
                try {
                    writer.writeVersionedGroup(new VersionedGroupSpec(
                        "unit_price", "version_no", groupKeyOf.get(e.getKey()), CONTENT, newRows, null, ctx.pendingQuotationId));
                    result.recordWrite("unit_price", newRows.size());
                } catch (Exception ex) {
                    result.recordError(0, "_group_", ex.getMessage());
                }
            }
        }
        return result;
    }
}
