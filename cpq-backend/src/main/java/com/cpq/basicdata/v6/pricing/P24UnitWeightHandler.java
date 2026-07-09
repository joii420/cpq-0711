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
                // 注意: getStr 用 header.contains(key) 子串匹配, 不能带裸"料号" ——
                // 会命中同 sheet 的"生产料号"列(该列空)导致 material_no 读空。核价单重主列=销售料号。
                String materialNo = row.getStr("销售料号", "宏丰料号");
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
