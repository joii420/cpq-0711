package com.cpq.configure.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.util.List;

/**
 * 销售侧客户维度指纹去重登记仓储。
 *
 * 与生产侧 {@code material_master.config_fingerprint}（全局唯一）不同：
 * 按 (customer_no, structure_version, config_fingerprint) 客户维度唯一，
 * 用于同一客户同一选配复用同一报价料号（quote_part_no）。
 */
@ApplicationScoped
public class SalesSignatureRepository {

    @Inject
    EntityManager em;

    /** 命中返回 quote_part_no，否则 null。 */
    public String lookup(String customerNo, String structureVersion, String fingerprint) {
        List<?> r = em.createNativeQuery(
                "SELECT quote_part_no FROM sel_part_signature WHERE customer_no=:c AND structure_version=:v AND config_fingerprint=:f")
                .setParameter("c", customerNo)
                .setParameter("v", structureVersion)
                .setParameter("f", fingerprint)
                .setMaxResults(1)
                .getResultList();
        return r.isEmpty() ? null : (String) r.get(0);
    }

    /**
     * 登记；并发败者（ON CONFLICT DO NOTHING 返回 0 行）时返回既有 quote_part_no（回读先赢者），
     * 否则返回传入的 quotePartNo（本次即为先赢者）。
     */
    @Transactional
    public String insertOrReadExisting(String customerNo, String structureVersion, String fp, String text,
                                        String quotePartNo, String productType) {
        int n = em.createNativeQuery(
                "INSERT INTO sel_part_signature (customer_no,structure_version,config_fingerprint,config_signature_text,quote_part_no,product_type) " +
                "VALUES (:c,:v,:f,:t,:q,:pt) ON CONFLICT (customer_no,structure_version,config_fingerprint) DO NOTHING")
                .setParameter("c", customerNo)
                .setParameter("v", structureVersion)
                .setParameter("f", fp)
                .setParameter("t", text)
                .setParameter("q", quotePartNo)
                .setParameter("pt", productType)
                .executeUpdate();
        if (n == 1) {
            return quotePartNo; // 先赢者
        }
        return lookup(customerNo, structureVersion, fp); // 败者回读先赢者号
    }
}
