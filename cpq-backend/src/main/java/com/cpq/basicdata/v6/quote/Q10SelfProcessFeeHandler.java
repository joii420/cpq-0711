package com.cpq.basicdata.v6.quote;

import com.cpq.basicdata.v6.parser.ImportContext;
import com.cpq.basicdata.v6.parser.SheetHandler;
import com.cpq.basicdata.v6.parser.SheetImportResult;
import com.cpq.basicdata.v6.parser.SheetRow;
import com.cpq.basicdata.v6.repository.MaterialMasterRepository;
import com.cpq.basicdata.v6.service.MaterialNoResolver;
import com.cpq.basicdata.v6.service.MaterialNoUnresolvableException;
import com.cpq.basicdata.v6.versioning.VersionedGroupSpec;
import com.cpq.basicdata.v6.versioning.VersionedV6Writer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Q10 自制加工费 → unit_price (price=PROCESS, cost=自制加工费)。
 *
 * <p>版本化（Task 3）：groupKey=(QUOTE, customer_no, MATERIAL, 自制加工费, code, finished_material_no, operation_no)，
 * content=[seq_no, pricing_price, cost_ratio, currency, unit]。
 */
@ApplicationScoped
public class Q10SelfProcessFeeHandler implements SheetHandler {

    @Inject VersionedV6Writer writer;
    @Inject MaterialNoResolver materialNoResolver;
    @Inject MaterialMasterRepository materialMasterRepo;

    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "cpq.v6import-setbased-writer", defaultValue = "false")
    boolean setBased;

    @Override public String sheetName() { return "自制加工费"; }

    private static final List<String> CONTENT = List.of(
        "seq_no", "pricing_price", "cost_ratio", "currency", "unit");

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
        // §10 规则3 fail-fast：已用「宏丰料号」兜底过 code 的成品集合（本次导入内）。
        // unit_price 唯一键不含 operation_no → 同一成品多条无投入料号行会塌缩撞键，故只允许一条。
        Set<String> noInputFallbackFinished = new HashSet<>();
        for (SheetRow row : rows) {
            result.totalRows++;
            String inputName = row.exact("投入料号名称");
            String finishedMaterialNo = row.getStr("宏丰料号", "成品料号");
            String code;
            try {
                code = materialNoResolver.resolve(row.exact("投入料号"), inputName, batch);
            } catch (MaterialNoUnresolvableException ex) {
                // §10 规则3：投入料号 + 投入料号名称都空 → code 兜底为宏丰料号(成品料号)，
                // 语义为「针对该成品整体的自制加工费」（非针对具体投入件）。
                if (finishedMaterialNo == null) {
                    result.recordError(row.rowNo, "投入料号",
                        "投入料号、投入料号名称、宏丰料号均为空，无法确定料号"); continue;
                }
                if (!noInputFallbackFinished.add(finishedMaterialNo)) {
                    result.recordError(row.rowNo, "投入料号",
                        "成品 " + finishedMaterialNo + " 存在多条无投入料号的自制加工费，数据非法"); continue;
                }
                code = finishedMaterialNo;
            }
            final String resolvedCode = code;   // catch 可重新赋值 → lambda 捕获需 final 副本
            MaterialMasterRepository.accNameType(mmAcc, resolvedCode, inputName, "组成件");
            result.recordWrite("material_master", 1);
            String operationNo = row.getStr("工序编号");
            List<Object> key = Arrays.asList(resolvedCode, finishedMaterialNo, operationNo);
            groupKeyOf.computeIfAbsent(key, k -> {
                Map<String, Object> g = new LinkedHashMap<>();
                g.put("system_type", "QUOTE");
                g.put("customer_no", ctx.customerNo);
                g.put("price_type", "PROCESS");
                g.put("cost_type", "自制加工费");
                g.put("code", resolvedCode);
                g.put("finished_material_no", finishedMaterialNo);
                g.put("operation_no", operationNo);
                return g;
            });
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("seq_no", row.getInt("项次（一级）", "项次"));
            c.put("pricing_price", row.getDecimal("值"));   // 固定金额写值；比例费用留 NULL（D1）
            c.put("cost_ratio", row.getDecimal("比例"));
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
