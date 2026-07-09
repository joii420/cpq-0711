package com.cpq.basicdata.v6.pricing;

import com.cpq.basicdata.v6.parser.ImportContext;
import com.cpq.basicdata.v6.parser.SheetHandler;
import com.cpq.basicdata.v6.parser.SheetImportResult;
import com.cpq.basicdata.v6.parser.SheetRow;
import com.cpq.basicdata.v6.repository.MaterialCustomerMapRepository;
import com.cpq.basicdata.v6.repository.MaterialMasterRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.util.List;

/**
 * P05 宏丰-客户料号对应关系 → material_customer_map + material_master 双表同步。
 * <p>customer_no 从 Excel 行读取（核价唯一一个 Sheet 提供 customer_no 列）。
 */
@ApplicationScoped
public class P05CustomerMapHandler implements SheetHandler {

    @Inject MaterialCustomerMapRepository mapRepo;
    @Inject MaterialMasterRepository masterRepo;

    @Override public String sheetName() { return "宏丰-客户料号对应关系"; }

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public SheetImportResult handle(List<SheetRow> rows, ImportContext ctx) {
        SheetImportResult result = new SheetImportResult(sheetName());
        for (SheetRow row : rows) {
            result.totalRows++;
            try {
                String materialNo = row.getStr("销售料号", "宏丰料号");
                String productionNo = row.getStr("生产料号");
                String customerNo = row.getStr("客户编号");
                String customerProductNo = row.getStr("客户产品编号");
                if (materialNo == null || customerNo == null || customerProductNo == null) {
                    result.recordError(row.rowNo, "销售料号/客户编号/客户产品编号", "必填项为空");
                    continue;
                }

                // 1. material_master upsert (name/spec/dimension/old_material_no)
                masterRepo.upsertByMaterialNo(
                    materialNo,
                    row.getStr("品名"),
                    row.getStr("规格"),
                    row.getStr("尺寸"),
                    row.getStr("旧料号"),
                    null, null, null, null,
                    ctx.importedBy);
                result.recordWrite("material_master", 1);

                // 2. material_customer_map upsert
                mapRepo.upsert(materialNo, customerNo,
                    row.getStr("客户名称"),
                    null,
                    customerProductNo,
                    null,
                    row.getInt("项次"),
                    null, null, null, null,
                    productionNo,
                    ctx.importedBy);
                result.successRows++;
                result.recordWrite("material_customer_map", 1);
            } catch (Exception e) {
                result.recordError(row.rowNo, "_row_", e.getMessage());
            }
        }
        return result;
    }
}
