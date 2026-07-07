package com.cpq.basicdata.v6.quote;

import com.cpq.basicdata.v6.parser.ImportContext;
import com.cpq.basicdata.v6.parser.SheetHandler;
import com.cpq.basicdata.v6.parser.SheetImportResult;
import com.cpq.basicdata.v6.parser.SheetRow;
import com.cpq.basicdata.v6.repository.MaterialCustomerMapRepository;
import com.cpq.basicdata.v6.repository.MaterialMasterRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
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

    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "cpq.v6import-setbased-writer", defaultValue = "false")
    boolean setBased;

    @Override public String sheetName() { return "客户料号与宏丰料号的关系"; }

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public SheetImportResult handle(List<SheetRow> rows, ImportContext ctx) {
        SheetImportResult result = new SheetImportResult(sheetName());
        // ① replace-per-customer：本 sheet 是该客户客户料号映射的权威全集。
        // 仅当有数据行时先清掉该客户旧的 QUOTE 客户料号映射行（不动 PRICING 行/组件登记行），
        // 避免上一次（含脏数据）导入残留的多余 customer_product_no 在候选查询里扇出（77→85 bug）。
        // 空 sheet 不删，防止误清（缺该 sheet 的部分导入不触发本 handler）。
        if (ctx.customerNo != null && !ctx.customerNo.isBlank() && !rows.isEmpty()) {
            int removed = repo.deleteQuoteMappingsByCustomerNo(ctx.customerNo);
            result.recordWrite("material_customer_map.deleted", removed);
        }
        // §P1-A 成品料号 material_master 同步延后批量：去重后一次 upsertBatchMaterialNoOnly。
        LinkedHashSet<String> mmAcc = new LinkedHashSet<>();
        // Task 5：Q02 是报价客户料号登记，写路径统一改走 QUOTE（upsertQuote，冲突键=material_no 部分索引
        // + 客户守卫 + customer_product_no 直接 SET）。setBased 分支不再走批量 upsertBatch（本 spec 不需要
        // QUOTE 批量；正确性优先），两分支收敛到同一逐行 writeRow。
        for (SheetRow row : rows) {
            writeRow(row, ctx, result, mmAcc);
        }
        if (!mmAcc.isEmpty()) {
            materialMasterRepo.upsertBatchMaterialNoOnly(new ArrayList<>(mmAcc), ctx.importedBy);
        }
        return result;
    }

    /**
     * 单行 QUOTE 客户料号登记：relabel 读列 → 组装 MapRow → 隔离子事务 upsertQuote →
     * per-row 异常处理（spec §3 Chain-4）：单行失败（跨客户串号 / 客户料号 1:1 冲突）只
     * {@code recordError}，不毒死整 sheet 其余行。
     *
     * <p>upsertQuote 用 {@link QuarkusTransaction#requiringNew()} 包一个独立子事务：本 handler
     * 整体是 {@code @Transactional(REQUIRES_NEW)}，若某行撞 {@code uq_mcm_quote_cust_prod}
     * 直接在外层事务里抛异常，会把外层事务标记 rollback-only、后续所有行的写入在 commit 时全部
     * 丢失。子事务隔离后，撞约束的那一行独立回滚，不影响本 sheet 其余行的写入。
     */
    private void writeRow(SheetRow row, ImportContext ctx, SheetImportResult result, LinkedHashSet<String> mmAcc) {
        result.totalRows++;
        try {
            String materialNo = row.getStr("报价料号", "宏丰料号");
            String customerProductNo = row.getStr("客户产品编号");
            if (materialNo == null || customerProductNo == null) {
                result.recordError(row.rowNo, "报价料号/客户产品编号", "必填项为空");
                return;
            }
            MaterialCustomerMapRepository.MapRow mapRow = new MaterialCustomerMapRepository.MapRow(
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
                "QUOTE",
                null);
            int affected;
            try {
                affected = QuarkusTransaction.requiringNew()
                    .call(() -> repo.upsertQuote(mapRow, ctx.importedBy));
            } catch (RuntimeException e) {
                if (isUniqueViolation(e)) {
                    result.recordError(row.rowNo, "客户料号", "客户料号1:1冲突");
                    return;
                }
                throw e;
            }
            if (affected == 0) {
                result.recordError(row.rowNo, "报价料号", "跨客户串号");
                return;
            }
            result.successRows++;
            result.recordWrite("material_customer_map", 1);
            // 方案 §2「→ 料号表（material_master）同步」: 报价料号(成品)按 upsert 写入料号主数据表。
            // 仅同步 material_no（本 sheet 无宏丰料号本身的名称列，客户料号名称属客户维度，不写主数据），
            // preserveDescriptive=true 避免覆盖已有成品/BOM 父件的名称等描述字段。
            mmAcc.add(materialNo);
            result.recordWrite("material_master", 1);
        } catch (Exception e) {
            result.recordError(row.rowNo, "_row_", e.getMessage());
        }
    }

    /** 沿 cause 链找 {@link java.sql.SQLException} sqlState=23505（unique_violation），不依赖具体驱动/ORM 包装类型。 */
    private static boolean isUniqueViolation(Throwable t) {
        while (t != null) {
            if (t instanceof java.sql.SQLException se && "23505".equals(se.getSQLState())) return true;
            t = t.getCause();
        }
        return false;
    }
}
