package com.cpq.basicdata.v6.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.util.UUID;

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

    /** 铸新报价料号 + 登记 material_customer_map QUOTE 行（组件行，customer_product_no/production_no=NULL）。
     *  @deprecated 未传 pendingQuotationId 的旧签名（选配 3D 配置器等非 Excel 导入路径沿用，语义不变、
     *  立即全局生效）；task-0721 B2 Excel 导入路径请用 {@link #mintAndRegister(String, String, UUID)}。 */
    @Transactional
    public String mintAndRegister(String customerNo, String yyMm) {
        return mintAndRegister(customerNo, yyMm, null);
    }

    /**
     * task-0721 B2：pending 归属重载 —— {@code pendingQuotationId} 非 null 时新占号行落
     * {@code pending_quotation_id=本单}（延迟生效，闸门/AC-4 前不可被其它报价单引用；见 backtask B2 mcm 段）。
     */
    @Transactional
    public String mintAndRegister(String customerNo, String yyMm, UUID pendingQuotationId) {
        String code = getOrAllocateCustomerCode(customerNo);
        int serial = ((Number) em.createNativeQuery(
            "INSERT INTO quote_material_no_seq(customer_code, year_month, last_serial) VALUES(:code,:ym,1) " +
            "ON CONFLICT (customer_code, year_month) DO UPDATE SET last_serial = quote_material_no_seq.last_serial + 1 " +
            "RETURNING last_serial").setParameter("code", code).setParameter("ym", yyMm).getSingleResult()).intValue();
        if (serial > 999999) throw new IllegalStateException("单客户单月流水溢出(>999999): " + code + "-" + yyMm);
        String report = code + "-" + yyMm + String.format("%06d", serial);
        em.createNativeQuery(
            "INSERT INTO material_customer_map(system_type, material_no, customer_no, customer_product_no, production_no, pending_quotation_id, created_at, updated_at) " +
            "VALUES ('QUOTE', :m, :c, NULL, NULL, :pq, NOW(), NOW()) ON CONFLICT (material_no) WHERE system_type='QUOTE' DO NOTHING")
            .setParameter("m", report).setParameter("c", customerNo).setParameter("pq", pendingQuotationId).executeUpdate();
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
    /** @deprecated 未传 pendingQuotationId 的旧签名；task-0721 B2 Excel 导入路径请用
     *  {@link #ensureRegistered(String, String, UUID)}。 */
    @Transactional(dontRollbackOn = CrossCustomerQuoteNoException.class)
    public void ensureRegistered(String customerNo, String quoteNo) {
        ensureRegistered(customerNo, quoteNo, null);
    }

    /**
     * task-0721 B2：pending 归属重载 —— 首次登记（`rows>0`，即 ON CONFLICT DO NOTHING 未命中已有行）时
     * 落 {@code pending_quotation_id=本单}；命中已有行（跨客户串号判定）不改动其 pending 归属
     * （沿用既有行的生效状态，不因"引用一下"就把已生效行拉回 pending）。
     */
    @Transactional(dontRollbackOn = CrossCustomerQuoteNoException.class)
    public void ensureRegistered(String customerNo, String quoteNo, UUID pendingQuotationId) {
        int rows = em.createNativeQuery(
            "INSERT INTO material_customer_map(system_type, material_no, customer_no, customer_product_no, production_no, pending_quotation_id, created_at, updated_at) " +
            "VALUES ('QUOTE', :m, :c, NULL, NULL, :pq, NOW(), NOW()) ON CONFLICT (material_no) WHERE system_type='QUOTE' DO NOTHING")
            .setParameter("m", quoteNo).setParameter("c", customerNo).setParameter("pq", pendingQuotationId).executeUpdate();
        if (rows == 0) {
            String owner = (String) em.createNativeQuery(
                "SELECT customer_no FROM material_customer_map WHERE material_no=:m AND system_type='QUOTE'")
                .setParameter("m", quoteNo).getSingleResult();
            if (!customerNo.equals(owner))
                throw new CrossCustomerQuoteNoException("报价料号跨客户串号: " + quoteNo + " 属 " + owner + " 却在 " + customerNo + " 导入中出现");
        }
    }
}
