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
        requireNonBlank(customerNo, "lookup: customerNo 不能为空");
        requireNonBlank(structureVersion, "lookup: structureVersion 不能为空");
        requireNonBlank(fingerprint, "lookup: fingerprint 不能为空");
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
     *
     * 注意：败者回读依赖 READ COMMITTED 隔离级别 —— 胜者的 INSERT 提交后，败者事务内的
     * 后续查询才能读到胜者写入的行；PostgreSQL 默认隔离级别即为 READ COMMITTED。
     *
     * <p><b>事务不变量</b>：本方法须以 REQUIRED（默认）加入调用方（configure）事务，使签名
     * INSERT 与调用方的业务落库原子提交；勿改 REQUIRES_NEW，否则回读到的号可能指向尚未提交的数据。
     */
    @Transactional
    public String insertOrReadExisting(String customerNo, String structureVersion, String fp, String text,
                                        String quotePartNo, String productType) {
        requireNonBlank(customerNo, "insertOrReadExisting: customerNo 不能为空");
        requireNonBlank(structureVersion, "insertOrReadExisting: structureVersion 不能为空");
        requireNonBlank(fp, "insertOrReadExisting: fp 不能为空");
        requireNonBlank(quotePartNo, "insertOrReadExisting: quotePartNo 不能为空");
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

    /**
     * 参数非空校验：把脏输入从「NOT NULL 违例导致外层 configure 事务 rollback-only 连坐整单回滚」
     * 降级为「可定位的参数错误」。
     */
    private static void requireNonBlank(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
    }
}
