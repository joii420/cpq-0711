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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Q09 来料回收折扣 → unit_price (price=INCOMING_MATERIAL_RECYCLE, cost=回收折扣)。
 *
 * <p>版本化（Task 3）：groupKey=(QUOTE, customer_no, MATERIAL, 回收折扣, code, finished_material_no)，
 * content=[cost_ratio]。无 seq_no（§9 项次不导入）。
 * <p>update-0723 B5（U10）：有码沿用原始码（不 resolve，行为不变）；只有名称时补名称反查——按
 * {@link TypeIndex} 推断类型，材质走 material_recipe 按名查码（查无报错「未找到材质」），
 * 零件/外购件走 {@link MaterialNoResolver}（按名查 material_master 或发号，共享全导入 BatchState，R2）。
 */
@ApplicationScoped
public class Q09IncomingRecoveryHandler implements SheetHandler {

    @Inject VersionedV6Writer writer;
    @Inject MaterialNoResolver materialNoResolver;
    @Inject MaterialMasterRepository materialMasterRepo;

    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "cpq.v6import-setbased-writer", defaultValue = "false")
    boolean setBased;

    @Override public String sheetName() { return "来料回收折扣"; }

    private static final List<String> CONTENT = List.of("cost_ratio");

    @Override
    @Transactional(Transactional.TxType.MANDATORY)
    public SheetImportResult handle(List<SheetRow> rows, ImportContext ctx) {
        SheetImportResult result = new SheetImportResult(sheetName());
        TypeIndex typeIndex = (TypeIndex) ctx.sharedCache.get("partTypeIndex");
        MaterialNoResolver.BatchState batch = MaterialNoResolver.batchStateFor(ctx);
        Map<String, String[]> mmAcc = new LinkedHashMap<>();
        Map<List<Object>, Map<String, Object>> groupKeyOf = new LinkedHashMap<>();
        Map<List<Object>, List<Map<String, Object>>> contentOf = new LinkedHashMap<>();
        for (SheetRow row : rows) {
            result.totalRows++;
            String raw = row.exact("投入料号");
            String rawName = row.exact("投入料号名称");
            if ((raw == null || raw.isBlank()) && (rawName == null || rawName.isBlank())) {
                result.recordError(row.rowNo, "投入料号", "料号与名称均为空"); continue;
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
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("cost_ratio", row.getDecimal("回收折扣"));
            contentOf.computeIfAbsent(key, k -> new ArrayList<>()).add(c);
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
}
