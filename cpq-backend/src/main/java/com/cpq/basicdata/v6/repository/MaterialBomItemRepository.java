package com.cpq.basicdata.v6.repository;

import com.cpq.basicdata.v6.entity.MaterialBomItem;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@ApplicationScoped
public class MaterialBomItemRepository implements PanacheRepositoryBase<MaterialBomItem, UUID> {

    @Inject
    EntityManager em;

    public List<MaterialBomItem> findByParent(String systemType, String customerNo,
                                              String materialNo, String characteristic) {
        return list("systemType = ?1 AND customerNo = ?2 AND materialNo = ?3 " +
                    "AND COALESCE(characteristic,'') = COALESCE(?4,'') ORDER BY seqNo",
                    systemType, customerNo, materialNo, characteristic);
    }

    /**
     * 按 customerNo / materialNo / systemType 过滤，返回分页查询对象。
     * systemType IN 子句由调用方（Service）已转换为具体值列表字符串并通过 JPQL IN 传递。
     *
     * @param customerNo  必填
     * @param materialNo  可为 null，不过滤
     * @param systemTypes 可为 null，不过滤；已展开的合法 systemType 值集合（如 ["QUOTE","BOTH"]）
     */
    public PanacheQuery<MaterialBomItem> queryItems(String customerNo, String materialNo,
                                                   List<String> systemTypes) {
        Sort sort = Sort.by("customerNo").and("materialNo").and("seqNo", Sort.Direction.Ascending)
                        .and("itemSeq", Sort.Direction.Ascending);

        StringBuilder where = new StringBuilder("customerNo = :customerNo");
        Map<String, Object> params = new HashMap<>();
        params.put("customerNo", customerNo);

        if (materialNo != null && !materialNo.isBlank()) {
            where.append(" AND materialNo = :materialNo");
            params.put("materialNo", materialNo);
        }
        if (systemTypes != null && !systemTypes.isEmpty()) {
            where.append(" AND systemType IN :systemTypes");
            params.put("systemTypes", systemTypes);
        }
        return find(where.toString(), sort, params);
    }

    /**
     * 查询 material_bom_item 表中所有不重复的 customer_no，按字母排序。
     */
    @SuppressWarnings("unchecked")
    public List<String> findDistinctCustomerNos() {
        return em.createNativeQuery(
                "SELECT DISTINCT customer_no FROM material_bom_item WHERE is_current = true ORDER BY customer_no")
                .getResultList();
    }

    /**
     * 查询指定 customerNo 下所有不重复的 material_no，支持前缀/模糊搜索，限制返回数量。
     *
     * @param customerNo 必填
     * @param q          可为 null；不为空时做 LIKE 过滤（前缀或包含）
     * @param limit      最多返回条数（1~1000）
     */
    @SuppressWarnings("unchecked")
    public List<String> findDistinctMaterialNos(String customerNo, String q, int limit) {
        StringBuilder sql = new StringBuilder(
                "SELECT DISTINCT material_no FROM material_bom_item WHERE customer_no = :customerNo AND is_current = true");
        if (q != null && !q.isBlank()) {
            sql.append(" AND LOWER(material_no) LIKE :q");
        }
        sql.append(" ORDER BY material_no LIMIT :limit");

        var query = em.createNativeQuery(sql.toString())
                .setParameter("customerNo", customerNo)
                .setParameter("limit", limit);
        if (q != null && !q.isBlank()) {
            query.setParameter("q", "%" + q.toLowerCase() + "%");
        }
        return query.getResultList();
    }
}
