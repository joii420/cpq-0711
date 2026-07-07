package com.cpq.basicdata.v6.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

@ApplicationScoped
public class QuoteMaterialNoAllocator {

    @Inject EntityManager em;

    public static class CrossCustomerQuoteNoException extends RuntimeException {
        public CrossCustomerQuoteNoException(String m){ super(m); }
    }

    /** 取或分配客户四位码（首见分配、之后固定）。 */
    @Transactional
    public String getOrAllocateCustomerCode(String customerNo) {
        var found = em.createNativeQuery("SELECT code FROM quote_customer_code WHERE customer_no=:c")
            .setParameter("c", customerNo).getResultList();
        if (!found.isEmpty()) return (String) found.get(0);

        long seq = ((Number) em.createNativeQuery("SELECT nextval('quote_customer_code_seq')").getSingleResult()).longValue();
        if (seq > 9999) throw new IllegalStateException("客户四位码枯竭(>9999): seq=" + seq);
        String code = String.format("%04d", seq);
        em.createNativeQuery("INSERT INTO quote_customer_code(customer_no, code) VALUES(:c,:code) ON CONFLICT (customer_no) DO NOTHING")
            .setParameter("c", customerNo).setParameter("code", code).executeUpdate();
        return (String) em.createNativeQuery("SELECT code FROM quote_customer_code WHERE customer_no=:c")
            .setParameter("c", customerNo).getSingleResult();
    }

    /** 铸新报价料号 + 登记 material_customer_map QUOTE 行（组件行，customer_product_no/production_no=NULL）。 */
    @Transactional
    public String mintAndRegister(String customerNo, String yyMm) {
        String code = getOrAllocateCustomerCode(customerNo);
        int serial = ((Number) em.createNativeQuery(
            "INSERT INTO quote_material_no_seq(customer_code, year_month, last_serial) VALUES(:code,:ym,1) " +
            "ON CONFLICT (customer_code, year_month) DO UPDATE SET last_serial = quote_material_no_seq.last_serial + 1 " +
            "RETURNING last_serial").setParameter("code", code).setParameter("ym", yyMm).getSingleResult()).intValue();
        if (serial > 999999) throw new IllegalStateException("单客户单月流水溢出(>999999): " + code + "-" + yyMm);
        String report = code + "-" + yyMm + String.format("%06d", serial);
        em.createNativeQuery(
            "INSERT INTO material_customer_map(system_type, material_no, customer_no, customer_product_no, production_no, created_at, updated_at) " +
            "VALUES ('QUOTE', :m, :c, NULL, NULL, NOW(), NOW()) ON CONFLICT (material_no) WHERE system_type='QUOTE' DO NOTHING")
            .setParameter("m", report).setParameter("c", customerNo).executeUpdate();
        return report;
    }

    /**
     * 料号有文件值：确保 QUOTE 行存在，幂等；命中别客户→抛 {@link CrossCustomerQuoteNoException}。
     *
     * <p><b>{@code dontRollbackOn} 必须显式声明</b>：本方法默认 {@code TxType.REQUIRED}，
     * 由各发号 handler 在自己的 {@code @Transactional(REQUIRES_NEW)} 事务内调用（本方法是
     * "join" 而非独立开启事务）。若不声明 dontRollbackOn，CDI 事务拦截器在本方法抛未检异常时
     * 会对"当前活跃事务"调用 {@code setRollbackOnly()}——即便调用方（handler 的 per-row 循环）
     * catch 住异常并 continue，外层事务已被标记 rollback-only，方法正常返回后外层
     * {@code @Transactional(REQUIRES_NEW)} 提交时会被静默整体回滚（不抛异常给调用方，
     * 表现为 successRows 与写入计数"看似正确"但 commit 后数据全部消失，比"整表抛错回滚"更隐蔽）。
     * 该异常发生在 INSERT ON CONFLICT DO NOTHING 已成功之后（无 SQL 层 abort），标记为
     * dontRollbackOn 后仅跳过"标记 rollback-only"这一步，不影响别的行的 DB 写入正确性。
     */
    @Transactional(dontRollbackOn = CrossCustomerQuoteNoException.class)
    public void ensureRegistered(String customerNo, String quoteNo) {
        int rows = em.createNativeQuery(
            "INSERT INTO material_customer_map(system_type, material_no, customer_no, customer_product_no, production_no, created_at, updated_at) " +
            "VALUES ('QUOTE', :m, :c, NULL, NULL, NOW(), NOW()) ON CONFLICT (material_no) WHERE system_type='QUOTE' DO NOTHING")
            .setParameter("m", quoteNo).setParameter("c", customerNo).executeUpdate();
        if (rows == 0) {
            String owner = (String) em.createNativeQuery(
                "SELECT customer_no FROM material_customer_map WHERE material_no=:m AND system_type='QUOTE'")
                .setParameter("m", quoteNo).getSingleResult();
            if (!customerNo.equals(owner))
                throw new CrossCustomerQuoteNoException("报价料号跨客户串号: " + quoteNo + " 属 " + owner + " 却在 " + customerNo + " 导入中出现");
        }
    }
}
