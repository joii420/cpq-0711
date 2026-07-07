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

        String code = String.format("%04d", ((Number) em.createNativeQuery("SELECT nextval('quote_customer_code_seq')").getSingleResult()).longValue());
        if (code.compareTo("9999") > 0) throw new IllegalStateException("客户四位码枯竭(>9999): " + code);
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

    /** 料号有文件值：确保 QUOTE 行存在，幂等；命中别客户→抛 CrossCustomerQuoteNoException。 */
    @Transactional
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
