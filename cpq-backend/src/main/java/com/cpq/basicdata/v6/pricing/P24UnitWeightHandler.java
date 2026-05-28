package com.cpq.basicdata.v6.pricing;

import com.cpq.basicdata.v6.parser.ImportContext;
import com.cpq.basicdata.v6.parser.SheetHandler;
import com.cpq.basicdata.v6.parser.SheetImportResult;
import com.cpq.basicdata.v6.parser.SheetRow;
import com.cpq.basicdata.v6.repository.MaterialMasterRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.util.List;

/** P24 单重 → material_master.unit_weight upsert by material_no。 */
@ApplicationScoped
public class P24UnitWeightHandler implements SheetHandler {

    @Inject MaterialMasterRepository repo;

    @Override public String sheetName() { return "单重"; }

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public SheetImportResult handle(List<SheetRow> rows, ImportContext ctx) {
        SheetImportResult result = new SheetImportResult(sheetName());
        for (SheetRow row : rows) {
            result.totalRows++;
            try {
                String materialNo = row.getStr("宏丰料号", "料号");
                java.math.BigDecimal unitWeight = row.getDecimal("单重");
                if (materialNo == null) { result.recordError(row.rowNo, "宏丰料号", "为空"); continue; }
                repo.upsertByMaterialNo(materialNo, null, null, null, null, null, null,
                    unitWeight, null, ctx.importedBy);
                result.successRows++;
                result.recordWrite("material_master", 1);
            } catch (Exception e) {
                result.recordError(row.rowNo, "_row_", e.getMessage());
            }
        }
        return result;
    }
}
