package com.cpq.basicdata.v6.quote;

import com.cpq.basicdata.v6.parser.ImportContext;
import com.cpq.basicdata.v6.parser.SheetHandler;
import com.cpq.basicdata.v6.parser.SheetImportResult;
import com.cpq.basicdata.v6.parser.SheetRow;
import com.cpq.basicdata.v6.repository.MaterialCustomerMapRepository;
import com.cpq.basicdata.v6.repository.MaterialMasterRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Q02 客户料号与宏丰料号的关系 → material_customer_map。
 *
 * <p>方案 §2「→ 料号表（material_master）同步」: 宏丰料号(成品料号)同步 upsert 至 material_master，
 * 否则成品只进客户映射表、不进料号主数据表，报价候选查询
 * (FROM material_master WHERE material_no IN hfPairs) 命中 0 → 报价单提示「该客户暂无基础数据料号」。
 */
@ApplicationScoped
public class Q02CustomerMapHandler implements SheetHandler {

    @Inject MaterialCustomerMapRepository repo;
    @Inject MaterialMasterRepository materialMasterRepo;

    @Override public String sheetName() { return "客户料号与宏丰料号的关系"; }

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public SheetImportResult handle(List<SheetRow> rows, ImportContext ctx) {
        SheetImportResult result = new SheetImportResult(sheetName());
        // ① replace-per-customer：本 sheet 是该客户客户料号映射的权威全集。
        // 仅当有数据行时先清掉该客户旧映射，再 upsert 本次行，避免上一次（含脏数据）
        // 导入残留的多余 customer_product_no 在候选查询里扇出（77→85 bug）。
        // 空 sheet 不删，防止误清（缺该 sheet 的部分导入不触发本 handler）。
        if (ctx.customerNo != null && !ctx.customerNo.isBlank() && !rows.isEmpty()) {
            int removed = repo.deleteByCustomerNo(ctx.customerNo);
            result.recordWrite("material_customer_map.deleted", removed);
        }
        // §P1-A 成品料号 material_master 同步延后批量：去重后一次 upsertBatchMaterialNoOnly。
        LinkedHashSet<String> mmAcc = new LinkedHashSet<>();
        for (SheetRow row : rows) {
            result.totalRows++;
            try {
                String materialNo = row.getStr("宏丰料号");
                String customerProductNo = row.getStr("客户产品编号");
                if (materialNo == null || customerProductNo == null) {
                    result.recordError(row.rowNo, "宏丰料号/客户产品编号", "必填项为空");
                    continue;
                }
                repo.upsert(
                    materialNo,
                    ctx.customerNo,
                    row.getStr("客户名称"),
                    row.getStr("客户料号名称"),
                    customerProductNo,
                    row.getStr("客户图号"),
                    null,                            // seq_no 报价表无项次列
                    row.getStr("付款方式"),
                    row.getStr("基础货币"),
                    row.getStr("报价货币"),
                    row.getDecimal("汇率"),
                    ctx.importedBy);
                result.successRows++;
                result.recordWrite("material_customer_map", 1);
                // 方案 §2「→ 料号表（material_master）同步」: 宏丰料号(成品)按 upsert 写入料号主数据表。
                // 仅同步 material_no（本 sheet 无宏丰料号本身的名称列，客户料号名称属客户维度，不写主数据），
                // preserveDescriptive=true 避免覆盖已有成品/BOM 父件的名称等描述字段。
                mmAcc.add(materialNo);
                result.recordWrite("material_master", 1);
            } catch (Exception e) {
                result.recordError(row.rowNo, "_row_", e.getMessage());
            }
        }
        if (!mmAcc.isEmpty()) {
            materialMasterRepo.upsertBatchMaterialNoOnly(new ArrayList<>(mmAcc), ctx.importedBy);
        }
        return result;
    }
}
