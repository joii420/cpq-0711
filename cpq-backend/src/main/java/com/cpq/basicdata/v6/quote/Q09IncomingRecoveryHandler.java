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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Q09 来料回收折扣 → unit_price (price=INCOMING_MATERIAL_RECYCLE, cost=回收折扣)。
 *
 * <p>版本化（Task 3）：groupKey=(QUOTE, customer_no, MATERIAL, 回收折扣, code, finished_material_no)，
 * content=[cost_ratio]。无 seq_no（§9 项次不导入）。
 */
@ApplicationScoped
public class Q09IncomingRecoveryHandler implements SheetHandler {

    @Inject VersionedV6Writer writer;
    @Inject MaterialNoResolver materialNoResolver;
    @Inject MaterialMasterRepository materialMasterRepo;

    @Override public String sheetName() { return "来料回收折扣"; }

    private static final List<String> CONTENT = List.of("cost_ratio");

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public SheetImportResult handle(List<SheetRow> rows, ImportContext ctx) {
        SheetImportResult result = new SheetImportResult(sheetName());
        Map<List<Object>, Map<String, Object>> groupKeyOf = new LinkedHashMap<>();
        Map<List<Object>, List<Map<String, Object>>> contentOf = new LinkedHashMap<>();
        MaterialNoResolver.BatchState batch = new MaterialNoResolver.BatchState();
        Map<String, String[]> mmAcc = new LinkedHashMap<>();   // §P1-A 料号表延后批量(首个非空胜)
        for (SheetRow row : rows) {
            result.totalRows++;
            String inputName = row.exact("投入料号名称");
            String code;
            try {
                code = materialNoResolver.resolve(row.exact("投入料号"), inputName, batch);
            } catch (MaterialNoUnresolvableException ex) {
                result.recordError(row.rowNo, "投入料号", "料号与名称均为空"); continue;
            }
            MaterialMasterRepository.accNameType(mmAcc, code, inputName, "组成件");
            result.recordWrite("material_master", 1);
            String finishedMaterialNo = row.getStr("宏丰料号", "成品料号");
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
        // §P1-A 料号表：一次批量 upsert（去重后；preserve=true 与原逐行等价），置于版本化写入之前。
        if (!mmAcc.isEmpty()) {
            List<MaterialMasterRepository.NameTypeRow> mmRows = new ArrayList<>(mmAcc.size());
            for (Map.Entry<String, String[]> me : mmAcc.entrySet()) {
                mmRows.add(new MaterialMasterRepository.NameTypeRow(me.getKey(), me.getValue()[0], me.getValue()[1]));
            }
            materialMasterRepo.upsertBatchNameType(mmRows, ctx.importedBy, true);
        }

        for (Map.Entry<List<Object>, List<Map<String, Object>>> e : contentOf.entrySet()) {
            try {
                writer.writeVersionedGroup(new VersionedGroupSpec(
                    "unit_price", "version_no", groupKeyOf.get(e.getKey()), CONTENT, e.getValue()));
                result.recordWrite("unit_price", e.getValue().size());
            } catch (Exception ex) {
                result.recordError(0, "_group_", ex.getMessage());
            }
        }
        return result;
    }
}
