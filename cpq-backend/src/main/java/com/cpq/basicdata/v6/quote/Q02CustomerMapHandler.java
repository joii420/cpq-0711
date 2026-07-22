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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Q02 客户料号与宏丰料号的关系 → material_customer_map。
 *
 * <p>方案 §2「→ 料号表（material_master）同步」: 宏丰料号(成品料号)同步 upsert 至 material_master，
 * 否则成品只进客户映射表、不进料号主数据表，报价候选查询
 * (FROM material_master WHERE material_no IN hfPairs) 命中 0 → 报价单提示「该客户暂无基础数据料号」。
 *
 * <p><b>重导自死锁修复（报价料号统一 Spec 1）</b>：早期版本对每行 upsert 用
 * {@code QuarkusTransaction.requiringNew()} 包一个独立子事务，意图是隔离
 * {@code uq_mcm_quote_cust_prod} unique_violation 不毒死外层整个事务。但外层
 * {@code @Transactional(REQUIRES_NEW)} 在 per-row 循环期间持有 ① 的 DELETE 未提交，
 * 若某行的 material_no 恰是刚被 DELETE 但未提交的行，子事务的 INSERT 会被 Postgres
 * MVCC 阻塞等外层 XID 结束（{@code Lock:transactionid}），外层又在同一线程同步等子事务
 * 返回 → 应用层自锁死循环，最终被 Narayana 60s 事务超时打断（"重导同一批客户料号"是
 * 日常场景，已用 {@code pg_blocking_pids} 锁链实锤）。
 *
 * <p>修法：去掉 per-row 子事务，全部 upsert 回到外层单一事务；DB 层唯一约束改为
 * <b>写库前在内存里消灭冲突</b>（见 {@link #handle}），保证正常路径下 0 例
 * unique_violation —— Postgres 单事务内一旦有语句抛错，整个事务即被标记 aborted，
 * 后续语句甚至 COMMIT 都会被静默转成 ROLLBACK，子事务隔离表面"救"了单行，实则制造了
 * 更隐蔽的死锁坑，必须从根上预防而非事后捕获。
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
        // ① replace-per-customer：本 sheet 是权威全集。
        // task-0721 B2：pendingQuotationId 非 null（真实 Excel 导入路径）时改按 pq 收窄清理范围——
        // 只清本单自己上一次(同 pq)导入残留的 pending 映射行(重导覆盖，backtask B2 第 4 点)，不再整
        // 客户清空(会误删该客户其它已审核/其它报价单的 pending 行，破坏他单隔离 AC-3)。
        // pendingQuotationId 为 null（无 pending 上下文的调用方，如既有单测/未来非报价导入路径）时
        // 回退旧行为：整客户清空，保持向后兼容零回归（Q02CustomerMapReplaceTest 固化）。
        // 空 sheet 不删，防止误清(缺该 sheet 的部分导入不触发本 handler)。
        if (ctx.customerNo != null && !ctx.customerNo.isBlank() && !rows.isEmpty()) {
            int removed = ctx.pendingQuotationId != null
                ? repo.deleteQuotePendingMappingsByCustomerNo(ctx.customerNo, ctx.pendingQuotationId)
                : repo.deleteQuoteMappingsByCustomerNo(ctx.customerNo);
            result.recordWrite("material_customer_map.deleted", removed);
        }
        // §P1-A 成品料号 material_master 同步延后批量：去重后一次 upsertBatchMaterialNoOnly。
        LinkedHashSet<String> mmAcc = new LinkedHashSet<>();

        // ②-0 逐行必填项校验 + relabel 读列，提取去重用的 key（报价料号 / 客户产品编号）。
        // 必填项缺失的行直接 recordError，不进入后续去重/写入。
        List<ParsedRow> parsed = new ArrayList<>();
        for (SheetRow row : rows) {
            result.totalRows++;
            String materialNo = row.getStr("销售料号", "报价料号", "宏丰料号");
            String customerProductNo = row.getStr("客户产品编号");
            if (materialNo == null || customerProductNo == null) {
                result.recordError(row.rowNo, "报价料号/客户产品编号", "必填项为空");
                continue;
            }
            parsed.add(new ParsedRow(row, materialNo, customerProductNo));
        }

        // ②-a 按客户产品编号（customer_product_no）预防性去重：对应 DB 侧 uq_mcm_quote_cust_prod
        // 部分唯一索引 (system_type, customer_no, customer_product_no) WHERE system_type='QUOTE'
        // AND customer_product_no IS NOT NULL（ctx.customerNo 在本 handle() 调用内恒定，等价于
        // 按 customer_product_no 分组）。该索引**不是** upsertQuote 的 ON CONFLICT target
        // （target 是 material_no，见 uq_mcm_quote_no），若两行 material_no 不同却撞了同一
        // customer_product_no，第二条 INSERT 会直接抛 DB 层 unique_violation，把单事务整体
        // 毒成 aborted，必须在写库前消灭，不能指望 DB 报错后再补救。
        //
        // 注意：**同一 material_no 的重复行不在此列**——它们由 upsertQuote 的
        // ON CONFLICT(material_no) 在同一事务内自然折叠（当前事务内自身写入始终可见，后行覆盖
        // 前行的字段生效，见 MaterialCustomerMapRepository#upsertQuote），既不会触发
        // unique_violation 也不会产生跨事务 MVCC 等待，因此不需要、也不应该预先去重报错——
        // 早期版本曾对 material_no 重复也做内存去重报错，但这会把"同一报价料号在 sheet 内
        // 多次出现、后值生效"这个历来受支持的合法用法（见 MaterialMasterBatchImportIntegrationTest
        // 的 P1 dup 场景）误判为错误，已回退。
        Map<String, String> finalMaterialNoByCpn = new LinkedHashMap<>();
        for (ParsedRow pr : parsed) {
            finalMaterialNoByCpn.put(pr.customerProductNo, pr.materialNo); // 后行覆盖前行 = 组内最终归属
        }
        List<ParsedRow> finalRows = new ArrayList<>();
        for (ParsedRow pr : parsed) {
            if (!pr.materialNo.equals(finalMaterialNoByCpn.get(pr.customerProductNo))) {
                // 本行的 material_no 不是该 customer_product_no 最终归属的那个——若放行写库，
                // 会在 uq_mcm_quote_cust_prod 上与最终归属行冲突，抛 unique_violation。
                result.recordError(pr.row.rowNo, "客户料号", "同一客户料号映射多个报价料号");
                continue;
            }
            finalRows.add(pr); // 原顺序遍历 parsed 天然保持原 sheet 顺序，无需额外排序
        }

        // Task 5：Q02 是报价客户料号登记，写路径统一改走 QUOTE（upsertQuote，冲突键=material_no 部分索引
        // + 客户守卫 + customer_product_no 直接 SET）。setBased 分支不再走批量 upsertBatch（本 spec 不需要
        // QUOTE 批量；正确性优先），两分支收敛到同一逐行 writeRow。
        for (ParsedRow pr : finalRows) {
            writeRow(pr, ctx, result, mmAcc);
        }
        if (!mmAcc.isEmpty()) {
            materialMasterRepo.upsertBatchMaterialNoOnly(new ArrayList<>(mmAcc), ctx.importedBy);
        }
        return result;
    }

    /** 去重用的中间态：原始行 + 已提取的去重 key（报价料号 / 客户产品编号）。 */
    private static final class ParsedRow {
        final SheetRow row;
        final String materialNo;
        final String customerProductNo;
        ParsedRow(SheetRow row, String materialNo, String customerProductNo) {
            this.row = row; this.materialNo = materialNo; this.customerProductNo = customerProductNo;
        }
    }

    /**
     * 单行 QUOTE 客户料号登记：组装 MapRow → 外层单事务直接 upsertQuote → per-row 异常处理
     * （spec §3 Chain-4）：单行失败（跨客户串号）只 {@code recordError}，不影响 sheet 其余行——
     * 跨客户串号靠 {@link MaterialCustomerMapRepository#upsertQuote} 的 WHERE 客户守卫返回
     * 0 行判定，不是异常，单事务内天然安全，不需要子事务隔离。
     *
     * <p>{@code uq_mcm_quote_cust_prod} 的冲突源已在 {@link #handle} 的 ②-a 内存去重阶段消灭
     * （{@code uq_mcm_quote_no} 本身就是 upsertQuote 的 ON CONFLICT target，天然不会抛异常），
     * 正常路径下 upsertQuote 不应再抛 unique_violation。若仍然抛出，说明去重逻辑有遗漏——
     * 见下方 catch 块。
     */
    private void writeRow(ParsedRow pr, ImportContext ctx, SheetImportResult result, LinkedHashSet<String> mmAcc) {
        SheetRow row = pr.row;
        try {
            MaterialCustomerMapRepository.MapRow mapRow = new MaterialCustomerMapRepository.MapRow(
                pr.materialNo,
                ctx.customerNo,
                row.getStr("客户名称"),
                row.getStr("客户料号名称"),
                pr.customerProductNo,
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
                affected = repo.upsertQuote(mapRow, ctx.importedBy, ctx.pendingQuotationId);
            } catch (RuntimeException e) {
                if (isUniqueViolation(e)) {
                    // 不应发生：见类注释 + 本方法 javadoc。这里不能 recordError 后静默继续——
                    // Postgres 单事务内一旦有语句抛错，整个事务已被标记 aborted，后续所有行的
                    // 写入（包括本行之前已"成功"的行）在 commit 时会被静默转成 ROLLBACK，
                    // 若此处吞掉异常继续跑，会产生"部分行假成功、commit 后全部丢失"的更隐蔽
                    // 数据丢失（正是引入 per-row 子事务、进而导致死锁的历史原因）。因此让异常
                    // 穿透，使整个 handle() 失败、事务完整回滚，同时暴露去重逻辑的遗漏。
                    throw new IllegalStateException(
                        "Q02 内存去重遗漏冲突（rowNo=" + row.rowNo + "）：materialNo=" + pr.materialNo
                            + ", customerProductNo=" + pr.customerProductNo, e);
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
            mmAcc.add(pr.materialNo);
            result.recordWrite("material_master", 1);
        } catch (IllegalStateException e) {
            throw e; // 去重遗漏的诊断性异常：穿透，不当作 per-row 错误吞掉（见上）。
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
