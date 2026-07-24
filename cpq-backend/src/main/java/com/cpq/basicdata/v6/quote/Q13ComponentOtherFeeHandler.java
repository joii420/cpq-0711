package com.cpq.basicdata.v6.quote;

import com.cpq.basicdata.v6.parser.ImportContext;
import com.cpq.basicdata.v6.parser.SheetHandler;
import com.cpq.basicdata.v6.parser.SheetImportResult;
import com.cpq.basicdata.v6.parser.SheetRow;
import com.cpq.basicdata.v6.repository.MaterialMasterRepository;
import com.cpq.basicdata.v6.service.MaterialNoResolver;
import com.cpq.basicdata.v6.service.MaterialNoUnresolvableException;
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
 * Q13 组成件其他费用 → unit_price (price=COMPONENT_OTHER, cost=要素名称动态)。
 *
 * <p>版本化（Task 3）：groupKey=(QUOTE, customer_no, COMPONENT, cost_type=要素名称, code,
 * finished_material_no, operation_no, supplier_no)，content=[item_seq, pricing_price, currency, unit]。
 * <p>行集维度 = item_seq（项次要素，在 uq_unit_price 内）；**seq_no 丢列**（已确认无下游消费）。
 *
 * <p><b>update-0723 B5/B6</b>：
 * <ul>
 *   <li>本 sheet 即「外购件」权威 sheet（{@link com.cpq.basicdata.v6.service.PartTypeInferenceService}
 *       U1），Phase 1 已做类型冲突检测（B2 §3.2），本 handler 不再需要 repair-2 时代
 *       "命中材质料号集则按材质处理" 的运行期特判 —— 若真有冲突，Phase 1 已整单拦截，走不到这里。</li>
 *   <li>item_seq 错位修正（U0 #4 必修 bug）：新模板删除「工序编号/组装工序/要素编号」三列后，
 *       「项次」只出现 2 次（不再是 3 次），要素项次改读第 2 个「项次」
 *       （{@code getIntNth("项次", 3)} → {@code getIntNth("项次", 2)}）。</li>
 *   <li>material_type 由「组成件」改「外购件」（B6，与 characteristic=OUTSOURCED 对应）。</li>
 *   <li>operation_no：新模板已删除「工序编号」列，{@code row.getStr("工序编号")} 自然恒返回
 *       null（无需额外代码改动，getStr 找不到匹配列即返回 null）。</li>
 * </ul>
 */
@ApplicationScoped
public class Q13ComponentOtherFeeHandler implements SheetHandler {

    @Inject VersionedV6Writer writer;
    @Inject MaterialNoResolver materialNoResolver;
    @Inject MaterialMasterRepository materialMasterRepo;

    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "cpq.v6import-setbased-writer", defaultValue = "false")
    boolean setBased;

    @Override public String sheetName() { return "组成件其他费用"; }

    private static final List<String> CONTENT = List.of(
        "item_seq", "pricing_price", "currency", "unit");

    @Override
    @Transactional(Transactional.TxType.MANDATORY)
    public SheetImportResult handle(List<SheetRow> rows, ImportContext ctx) {
        SheetImportResult result = new SheetImportResult(sheetName());
        Map<List<Object>, Map<String, Object>> groupKeyOf = new LinkedHashMap<>();
        Map<List<Object>, List<Map<String, Object>>> contentOf = new LinkedHashMap<>();
        // R2：全 handler 共享一个 BatchState（见 MaterialNoResolver#batchStateFor）。
        MaterialNoResolver.BatchState batch = MaterialNoResolver.batchStateFor(ctx);
        Map<String, String[]> mmAcc = new LinkedHashMap<>();   // §P1-A 料号表延后批量(首个非空胜)

        for (SheetRow row : rows) {
            result.totalRows++;
            String costType = row.getStr("要素名称");
            if (costType == null) { result.recordError(row.rowNo, "要素名称", "为空"); continue; }
            String componentName = row.exact("组成件名称");
            String rawComp = row.exact("组成件料号");
            String code;
            try {
                code = materialNoResolver.resolve(rawComp, componentName, batch);
            } catch (MaterialNoUnresolvableException ex) {
                result.recordError(row.rowNo, "组成件料号", "料号与名称均为空"); continue;
            } catch (QuoteMaterialNoAllocator.CrossCustomerQuoteNoException ex) {
                result.recordError(row.rowNo, "组成件料号", "报价料号跨客户串号"); continue;
            }
            // B6：本 sheet 即外购件权威集，material_type 存汉字「外购件」（原「组成件」已改）。
            MaterialMasterRepository.accNameType(mmAcc, code, componentName, "外购件");
            result.recordWrite("material_master", 1);

            String finishedMaterialNo = row.getStr("销售料号", "宏丰料号", "成品料号");
            String operationNo = row.getStr("工序编号");   // 新模板已删除该列，恒为 null（U0 #4）
            String supplierNo = row.getStr("供应商编号");
            List<Object> key = Arrays.asList(costType, code, finishedMaterialNo, operationNo, supplierNo);
            groupKeyOf.computeIfAbsent(key, k -> {
                Map<String, Object> g = new LinkedHashMap<>();
                g.put("system_type", "QUOTE");
                g.put("customer_no", ctx.customerNo);
                g.put("price_type", "COMPONENT_OTHER");
                g.put("cost_type", costType);
                g.put("code", code);
                g.put("finished_material_no", finishedMaterialNo);
                g.put("operation_no", operationNo);
                g.put("supplier_no", supplierNo);
                return g;
            });
            Map<String, Object> c = new LinkedHashMap<>();
            // U0 #4 必修 bug：新模板「项次」仅出现 2 次（工序编号/组装工序/要素编号三列已删），
            // 要素项次改读第 2 个「项次」（原 getIntNth("项次", 3) 在新模板恒取空）。
            c.put("item_seq", row.getIntNth("项次", 2));
            c.put("pricing_price", row.getDecimal("值"));   // 固定金额写值，空值留 NULL（D1）
            c.put("currency", row.getStr("货币"));
            c.put("unit", row.getStr("计价单位"));
            contentOf.computeIfAbsent(key, k -> new ArrayList<>()).add(c);
            result.successRows++;
        }
        // §P1-A 料号表：一次批量 upsert（去重后；preserve=true 与原逐行等价），置于版本化写入之前。
        if (!mmAcc.isEmpty()) {
            List<MaterialMasterRepository.NameTypeRow> mmRows = new ArrayList<>(mmAcc.size());
            for (Map.Entry<String, String[]> me : mmAcc.entrySet()) {
                mmRows.add(new MaterialMasterRepository.NameTypeRow(me.getKey(), me.getValue()[0], me.getValue()[1]));
            }
            // task-0721 B9：pending 模式暂存。
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
