package com.cpq.basicdata.v6.quote;

import com.cpq.basicdata.v6.parser.ImportContext;
import com.cpq.basicdata.v6.parser.SheetImportResult;
import com.cpq.basicdata.v6.parser.SheetRow;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ① 客户料号映射 replace-per-customer：脏导入留下的多余映射行在清洗后重导必须被清掉。
 *
 * <p>新契约（报价料号统一 Spec1）：material_customer_map QUOTE 行 material_no 全局唯一，
 * 同一报价料号只能挂一个客户产品编号（1:1，冲突键 = material_no，见 upsertQuote）。原用例
 * 「同一宏丰料号挂 3 个客户产品编号」在新语义下已结构性非法（会被 upsert 折叠成 1 行而非报错
 * 3 行）；改写为等价意图的合法数据：脏导入 3 个不同报价料号各自挂一个客户产品编号，
 * 清洗重导后只剩 1 个报价料号 —— 验证 replace-per-customer 仍会清掉重导文件里不再出现的
 * 陈旧映射行。
 */
@QuarkusTest
class Q02CustomerMapReplaceTest {

    @Inject Q02CustomerMapHandler handler;
    @Inject EntityManager em;

    static final String CUST = "TST-Q02REPLACE";
    static final String HF_A = "TST5121115551";
    static final String HF_B = "TST5121115552";
    static final String HF_C = "TST5121115553";

    @Transactional
    void cleanup() {
        em.createNativeQuery("DELETE FROM material_customer_map WHERE customer_no=:c")
            .setParameter("c", CUST).executeUpdate();
        em.createNativeQuery("DELETE FROM material_master WHERE material_no IN (:a,:b,:c)")
            .setParameter("a", HF_A).setParameter("b", HF_B).setParameter("c", HF_C).executeUpdate();
    }
    @BeforeEach void before() { cleanup(); }
    @AfterEach  void after()  { cleanup(); }

    private ImportContext ctx() {
        ImportContext c = new ImportContext();
        c.customerNo = CUST; c.systemType = "QUOTE"; c.importedBy = null; return c;
    }
    private SheetRow row(int seq, String hf, String cpn) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("宏丰料号", hf);
        m.put("客户产品编号", cpn);
        m.put("基础货币", "RMB");
        m.put("报价货币", "RMB");
        m.put("汇率", "1");
        return new SheetRow(seq, m);
    }
    @Transactional
    long count() {
        return ((Number) em.createNativeQuery(
                "SELECT count(*) FROM material_customer_map WHERE customer_no=:c")
                .setParameter("c", CUST).getSingleResult()).longValue();
    }

    @Transactional
    @Test
    void reimport_clean_file_removes_stale_rows() {
        // 第一次脏导入：3 个不同报价料号，各自挂一个客户产品编号
        handler.handle(List.of(row(1, HF_A, "DIRTY-A"), row(2, HF_B, "DIRTY-B"), row(3, HF_C, "DIRTY-C")), ctx());
        assertEquals(3, count(), "脏导入后应有 3 行");

        // 用户清洗文件后重导：只剩 1 个报价料号
        handler.handle(List.of(row(1, HF_A, "CLEAN-A")), ctx());
        assertEquals(1, count(), "清洗后重导应只剩 1 行（陈旧的 HF_B/HF_C 映射行被清掉）");
    }

    /**
     * ② 重导自死锁修复回归：原 bug 场景恰是"第二次导入的行 material_no 与第一次相同"——
     * ① 的 DELETE 未提交时，旧 per-row 子事务方案会在 INSERT 时被 Postgres MVCC 阻塞等
     * 外层 XID 结束，外层又同步等子事务返回 → 自锁，直到 Narayana 60s 事务超时才失败。
     * 本用例用完全相同的批次连续 handle 两次，断言第二次远快于 60s 完成且数据正确落库，
     * 证明单事务 + 内存去重方案已根治该死锁（而非只是恰好没撞上）。
     */
    @Transactional
    @Test
    void reimport_identical_batch_completes_without_deadlock() {
        List<SheetRow> rows = List.of(row(1, HF_A, "CPN-A"), row(2, HF_B, "CPN-B"), row(3, HF_C, "CPN-C"));

        handler.handle(rows, ctx());
        assertEquals(3, count(), "首次导入应落 3 行");

        long startNanos = System.nanoTime();
        SheetImportResult r2 = handler.handle(rows, ctx());
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;

        assertTrue(elapsedMs < 10_000,
            "重导完全相同批次应在数秒内完成，不应卡到 Narayana 60s 事务超时；实际耗时=" + elapsedMs + "ms");
        assertEquals(0, r2.failedRows, "重导相同批次不应产生错误行");
        assertEquals(3, count(), "重导后仍应是清空重建的 3 行");
    }

    /**
     * 同一报价料号（material_no）在本 sheet 内出现两次（不同客户产品编号）：uq_mcm_quote_no
     * 部分唯一索引正是 upsertQuote 的 ON CONFLICT target，同一事务内自身写入始终可见，第二次
     * upsert 天然走 DO UPDATE 折叠（后行覆盖前行），不会抛 unique_violation，也不需要（不应该）
     * 内存去重报错——早期版本曾对此也做去重报错，但会把 P1-A 集成测试里"同一料号 sheet 内
     * 多次出现、末值生效"这个历来合法用法误判为错误（见
     * {@code MaterialMasterBatchImportIntegrationTest} 的 P1 dup 场景），已回退，改为此用例
     * 固化"不报错、后行生效"的正确契约。
     */
    @Transactional
    @Test
    void duplicateMaterialNoWithinSheet_lastRowWinsWithoutError() {
        SheetImportResult r = handler.handle(List.of(
            row(1, HF_A, "CPN-FIRST"),
            row(2, HF_A, "CPN-SECOND")
        ), ctx());

        assertEquals(0, r.failedRows, "同一报价料号 sheet 内重复不应记错误（ON CONFLICT 自然折叠）");
        assertEquals(1, count(), "同一报价料号折叠后应只有 1 行");

        String cpn = (String) em.createNativeQuery(
                "SELECT customer_product_no FROM material_customer_map WHERE material_no=:m")
            .setParameter("m", HF_A).getSingleResult();
        assertEquals("CPN-SECOND", cpn, "后行覆盖前行：customer_product_no 应生效为最后一次写入的值");
    }

    /**
     * ②-a 内存去重：不同报价料号（material_no）映射到同一客户产品编号（customer_product_no）。
     * 对应 DB 侧 uq_mcm_quote_cust_prod 部分唯一索引——该索引不是 upsertQuote 的 ON CONFLICT
     * target，若不预先去重，第二条 INSERT 会直接抛 DB 层 unique_violation，把单事务整体
     * 毒成 aborted。必须在写库前就消灭这种冲突。
     */
    @Transactional
    @Test
    void dedup_duplicateCustomerProductNoWithinSheet_recordsErrorAndKeepsLastRow() {
        SheetImportResult r = handler.handle(List.of(
            row(1, HF_A, "SAME-CPN"),
            row(2, HF_B, "SAME-CPN")
        ), ctx());

        assertEquals(1, r.failedRows, "sheet 内客户料号重复映射应记 1 条错误");
        assertEquals(1, r.errors.get(0).rowNo, "错误应指向被覆盖的第 1 行");
        assertTrue(r.errors.get(0).message.contains("同一客户料号映射多个报价料号"),
            "错误信息应指出客户料号 1:1 冲突");
        assertEquals(1, count(), "去重后应只落 1 行（不应抛 unique_violation 或写出 2 行）");

        String materialNo = (String) em.createNativeQuery(
                "SELECT material_no FROM material_customer_map WHERE customer_no=:c")
            .setParameter("c", CUST).getSingleResult();
        assertEquals(HF_B, materialNo, "去重应保留 sheet 内后出现的一行（后写覆盖前写语义）");
    }

    /**
     * 跨客户守卫（upsertQuote WHERE 客户守卫返回 0 行 → recordError）在去掉 per-row 子事务、
     * 改为单事务直连之后仍应正常工作——该分支本就不是异常路径（0 行受影响不是 exception），
     * 单事务内天然安全，不依赖子事务隔离。
     */
    @Transactional
    @Test
    void crossCustomerGuard_stillRecordsErrorUnderSingleTransaction() {
        String otherCust = CUST + "-OTHER";
        try {
            handler.handle(List.of(row(1, HF_A, "CPN-OWNED-BY-CUST")), ctx());

            ImportContext otherCtx = new ImportContext();
            otherCtx.customerNo = otherCust; otherCtx.systemType = "QUOTE"; otherCtx.importedBy = null;
            SheetImportResult r2 = handler.handle(List.of(row(1, HF_A, "CPN-STEAL")), otherCtx);

            assertEquals(1, r2.failedRows, "跨客户串号应记 1 条错误");
            assertTrue(r2.errors.get(0).message.contains("跨客户"), "错误信息应含跨客户");

            Object[] row = (Object[]) em.createNativeQuery(
                    "SELECT customer_no, customer_product_no FROM material_customer_map WHERE material_no=:m")
                .setParameter("m", HF_A).getSingleResult();
            assertEquals(CUST, row[0], "跨客户导入不应覆盖归属客户");
            assertEquals("CPN-OWNED-BY-CUST", row[1], "跨客户导入不应覆盖 customer_product_no");
        } finally {
            em2CleanupOtherCustomer(otherCust);
        }
    }

    @Transactional
    void em2CleanupOtherCustomer(String otherCust) {
        em.createNativeQuery("DELETE FROM material_customer_map WHERE customer_no=:c")
            .setParameter("c", otherCust).executeUpdate();
    }
}
