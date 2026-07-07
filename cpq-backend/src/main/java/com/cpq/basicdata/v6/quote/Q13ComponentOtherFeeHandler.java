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
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public SheetImportResult handle(List<SheetRow> rows, ImportContext ctx) {
        SheetImportResult result = new SheetImportResult(sheetName());
        Map<List<Object>, Map<String, Object>> groupKeyOf = new LinkedHashMap<>();
        Map<List<Object>, List<Map<String, Object>>> contentOf = new LinkedHashMap<>();
        MaterialNoResolver.BatchState batch = new MaterialNoResolver.BatchState();
        batch.customerNo = ctx.customerNo;
        batch.yyMm = java.time.YearMonth.now().format(java.time.format.DateTimeFormatter.ofPattern("yyMM"));
        Map<String, String[]> mmAcc = new LinkedHashMap<>();   // §P1-A 料号表延后批量(首个非空胜)
        for (SheetRow row : rows) {
            result.totalRows++;
            String costType = row.getStr("要素名称");
            if (costType == null) { result.recordError(row.rowNo, "要素名称", "为空"); continue; }
            String componentName = row.exact("组成件名称");
            String code;
            try {
                code = materialNoResolver.resolve(row.exact("组成件料号"), componentName, batch);
            } catch (MaterialNoUnresolvableException ex) {
                result.recordError(row.rowNo, "组成件料号", "料号与名称均为空"); continue;
            } catch (QuoteMaterialNoAllocator.CrossCustomerQuoteNoException ex) {
                result.recordError(row.rowNo, "组成件料号", "报价料号跨客户串号"); continue;
            }
            MaterialMasterRepository.accNameType(mmAcc, code, componentName, "组成件");
            result.recordWrite("material_master", 1);
            String finishedMaterialNo = row.getStr("宏丰料号", "成品料号");
            String operationNo = row.getStr("工序编号");
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
            c.put("item_seq", row.getIntNth("项次", 3));  // 第3个"项次"=要素项次(裸重复表头按列序)
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
            materialMasterRepo.upsertBatchNameType(mmRows, ctx.importedBy, true);
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
