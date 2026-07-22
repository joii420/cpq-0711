package com.cpq.basicdata.v6.quote;

import com.cpq.basicdata.v6.parser.ImportContext;
import com.cpq.basicdata.v6.parser.SheetHandler;
import com.cpq.basicdata.v6.parser.SheetImportResult;
import com.cpq.basicdata.v6.parser.SheetRow;
import com.cpq.basicdata.v6.repository.MaterialMasterRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Q18 单重 → material_master.unit_weight upsert by material_no。 */
@ApplicationScoped
public class Q18UnitWeightHandler implements SheetHandler {

    @Inject MaterialMasterRepository repo;

    @Override public String sheetName() { return "单重"; }

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public SheetImportResult handle(List<SheetRow> rows, ImportContext ctx) {
        SheetImportResult result = new SheetImportResult(sheetName());
        // §P1-A unit_weight 延后批量：material_no -> 末值非空胜（仅 null 权重也保留以建行），
        // 等价逐行 upsertByMaterialNo(unitWeight)（unit_weight 恒 COALESCE(EXCLUDED, existing)）。
        Map<String, java.math.BigDecimal> mmAcc = new LinkedHashMap<>();
        for (SheetRow row : rows) {
            result.totalRows++;
            try {
                String materialNo = row.getStr("销售料号", "料号", "宏丰料号");
                java.math.BigDecimal unitWeight = row.getDecimal("单重");
                if (materialNo == null) { result.recordError(row.rowNo, "料号", "为空"); continue; }
                if (!mmAcc.containsKey(materialNo) || unitWeight != null) mmAcc.put(materialNo, unitWeight);
                result.successRows++;
                result.recordWrite("material_master", 1);
            } catch (Exception e) {
                result.recordError(row.rowNo, "_row_", e.getMessage());
            }
        }
        if (!mmAcc.isEmpty()) {
            List<MaterialMasterRepository.WeightRow> mmRows = new ArrayList<>(mmAcc.size());
            for (Map.Entry<String, java.math.BigDecimal> me : mmAcc.entrySet()) {
                mmRows.add(new MaterialMasterRepository.WeightRow(me.getKey(), me.getValue()));
            }
            // task-0721 B9：pending 模式暂存，核价通过时才覆盖式 upsert 进 material_master。
            repo.upsertBatchWithWeight(mmRows, ctx.importedBy, ctx.pendingQuotationId);
        }
        return result;
    }
}
