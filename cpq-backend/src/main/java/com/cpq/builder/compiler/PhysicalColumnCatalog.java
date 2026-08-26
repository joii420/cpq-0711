package com.cpq.builder.compiler;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import java.util.*;

/**
 * 物理表列名目录（task-260819 B-5 支撑设施）。
 *
 * <p>编译器决定「is_current / system_type / customer_no 三件套要不要出现在某张表的收窄条件里」
 * 不能凭表名猜（AC-7② 实测：{@code material_master} 没有 is_current/system_type 两列，写了会
 * 运行期报错）——必须真查 {@code information_schema.columns}。
 *
 * <p>N+1 自检：调用方在一次 compile() 开始时把本次涉及的全部物理表名一次性传入，本类一条
 * {@code column_name = ANY(:tbls)} SQL 查完，不随节点/列/边数增长，也不在循环体里逐表查询。
 */
@ApplicationScoped
public class PhysicalColumnCatalog {

    @Inject
    EntityManager em;

    @SuppressWarnings("unchecked")
    public Map<String, Set<String>> columnsOf(Collection<String> tables) {
        List<String> distinct = tables.stream()
                .filter(t -> t != null && !t.isBlank())
                .distinct()
                .toList();
        if (distinct.isEmpty()) return Map.of();

        List<Object[]> rows = em.createNativeQuery(
                "SELECT table_name, column_name FROM information_schema.columns " +
                "WHERE table_schema='public' AND table_name = ANY(:tbls)")
                .setParameter("tbls", distinct.toArray(new String[0]))
                .getResultList();

        Map<String, Set<String>> out = new HashMap<>();
        for (Object[] r : rows) {
            out.computeIfAbsent((String) r[0], k -> new HashSet<>()).add((String) r[1]);
        }
        return out;
    }
}
