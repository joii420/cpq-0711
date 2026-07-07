package com.cpq.basicdata.v6.repository;

import com.cpq.basicdata.v6.repository.MaterialCustomerMapRepository.MapRow;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 报价料号统一 Spec 1 · Task 3 护栏：MaterialCustomerMapRepository 的 QUOTE 专用写路径。
 *
 * <p>覆盖：
 * <ul>
 *   <li>{@link MaterialCustomerMapRepository#deleteQuoteMappingsByCustomerNo} 精确收窄
 *       （只删 system_type='QUOTE' 且 customer_product_no 非空的客户料号映射行，
 *       不动 PRICING 行，也不动 customer_product_no=NULL 的组件登记行 —— 修 M4/M8）</li>
 *   <li>{@link MaterialCustomerMapRepository#upsertQuote} 命中已登记的组件行(customer_product_no=NULL)
 *       时把 customer_product_no 回填进 SET</li>
 *   <li>{@link MaterialCustomerMapRepository#upsertQuote} 客户守卫：跨客户命中返回 0，不覆盖别的客户</li>
 *   <li>{@link MaterialCustomerMapRepository#upsertQuote} 撞 uq_mcm_quote_cust_prod
 *       （同 customer_no+customer_product_no、不同 material_no）直接抛异常，不吞不静默</li>
 * </ul>
 *
 * <p>每步 DB 操作经由 {@link Db} 独立事务提交（而非把整个测试方法包一个大事务），
 * 避免"撞唯一约束抛异常"把测试方法自身事务标记为 rollback-only 后续断言/commit 出问题。
 */
@QuarkusTest
class MaterialCustomerMapRepositoryTest {

    @Inject
    Db db;

    static final UUID USER = UUID.fromString("00000000-0000-0000-0000-0000000000bb");

    @BeforeEach
    void before() {
        db.cleanup();
    }

    @AfterEach
    void after() {
        db.cleanup();
    }

    private static MapRow row(String materialNo, String customerNo, String customerProductNo) {
        return new MapRow(materialNo, customerNo, "客户名", "客户料件名", customerProductNo,
            null, null, null, null, null, null);
    }

    @Test
    void deleteQuoteMappingsByCustomerNo_onlyDeletesQuoteCustomerMappingRows() {
        String cust = "MCMR-C1";
        db.insertRaw("QUOTE", "MCMR-Q1", cust, "CP1");      // 客户料号映射行 → 应删
        db.insertRaw("QUOTE", "MCMR-Q2", cust, null);       // 组件登记行(customer_product_no=NULL) → 应留
        db.insertRaw("PRICING", "MCMR-P1", cust, "CP2");    // PRICING 行 → 应留

        int removed = db.deleteQuoteMappings(cust);

        assertEquals(1, removed, "只应删除 1 条 QUOTE 客户料号映射行");
        assertEquals(0L, db.countByMaterialNo("MCMR-Q1"), "QUOTE 客户料号映射行应被删除");
        assertEquals(1L, db.countByMaterialNo("MCMR-Q2"), "QUOTE 组件登记行(NULL)不应被删除");
        assertEquals(1L, db.countByMaterialNo("MCMR-P1"), "PRICING 行不应被删除");
    }

    @Test
    void upsertQuote_backfillsCustomerProductNoOnRegisteredRow() {
        String cust = "MCMR-C2";
        db.insertRaw("QUOTE", "MCMR-Q3", cust, null); // 已登记的组件行

        int affected = db.upsertQuote(row("MCMR-Q3", cust, "CPX"));

        assertEquals(1, affected, "命中同客户的组件登记行应成功回填");
        Object[] r = db.selectByMaterialNo("MCMR-Q3");
        assertEquals(cust, r[1], "customer_no 不应被改动");
        assertEquals("CPX", r[2], "customer_product_no 应被回填");
    }

    @Test
    void upsertQuote_crossCustomerGuard_returnsZeroAndDoesNotOverwrite() {
        String custA = "MCMR-C3", custB = "MCMR-C4";
        db.insertRaw("QUOTE", "MCMR-Q4", custA, null);

        int affected = db.upsertQuote(row("MCMR-Q4", custB, "CPZ"));

        assertEquals(0, affected, "跨客户命中必须返回 0（客户守卫拦截）");
        Object[] r = db.selectByMaterialNo("MCMR-Q4");
        assertEquals(custA, r[1], "客户守卫应阻止跨客户覆盖 customer_no");
        assertNull(r[2], "被拦截的更新不应写入 customer_product_no");
    }

    @Test
    void upsertQuote_duplicateCustomerProductNo_throwsUniqueViolation() {
        String cust = "MCMR-C5";
        db.insertRaw("QUOTE", "MCMR-Q5", cust, "CPY");

        // 不同 material_no(报价料号/report)，但同 customer_no + customer_product_no → 撞 uq_mcm_quote_cust_prod
        assertThrows(RuntimeException.class, () -> db.upsertQuote(row("MCMR-Q6", cust, "CPY")));
    }

    /** 独立事务边界的 DB 操作 helper：每个方法各自开关事务，隔离"撞约束抛异常"对测试方法本身事务的污染。 */
    @ApplicationScoped
    public static class Db {

        @Inject
        MaterialCustomerMapRepository repo;

        @Inject
        EntityManager em;

        @Transactional
        public void insertRaw(String systemType, String materialNo, String customerNo, String customerProductNo) {
            em.createNativeQuery(
                "INSERT INTO material_customer_map (system_type, material_no, customer_no, customer_product_no, " +
                "  created_at, updated_at, updated_by) " +
                "VALUES (:st, :m, :c, :cp, NOW(), NOW(), :by)")
                .setParameter("st", systemType)
                .setParameter("m", materialNo)
                .setParameter("c", customerNo)
                .setParameter("cp", customerProductNo)
                .setParameter("by", USER)
                .executeUpdate();
        }

        @Transactional
        public int upsertQuote(MapRow r) {
            return repo.upsertQuote(r, USER);
        }

        @Transactional
        public int deleteQuoteMappings(String customerNo) {
            return repo.deleteQuoteMappingsByCustomerNo(customerNo);
        }

        @Transactional
        public Object[] selectByMaterialNo(String materialNo) {
            return (Object[]) em.createNativeQuery(
                "SELECT system_type, customer_no, customer_product_no FROM material_customer_map WHERE material_no=:m")
                .setParameter("m", materialNo)
                .getSingleResult();
        }

        @Transactional
        public long countByMaterialNo(String materialNo) {
            return ((Number) em.createNativeQuery(
                "SELECT COUNT(*) FROM material_customer_map WHERE material_no=:m")
                .setParameter("m", materialNo)
                .getSingleResult()).longValue();
        }

        @Transactional
        public void cleanup() {
            em.createNativeQuery(
                "DELETE FROM material_customer_map WHERE customer_no LIKE 'MCMR-%' OR material_no LIKE 'MCMR-%'")
                .executeUpdate();
        }
    }
}
