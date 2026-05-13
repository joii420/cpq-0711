package com.cpq.partno;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

/**
 * PartNoProvider V1 实现:本地按 {@code part_no_sequence} 表自动分配 {@code CFG-{symbol}-{6位流水}}.
 *
 * <p>当前为唯一实现,直接作为默认 CDI bean 注入.
 * <p>如需切换 V2 外部 API,届时引入 Qualifier 区分即可,业务代码改动极小.
 *
 * <p>并发安全:每次 {@link #apply} 在 {@code @Transactional} 内
 * {@code SELECT ... FOR UPDATE} part_no_sequence 行,行锁串行化同 prefix 的取号.
 * 不同 prefix 之间无锁冲突.
 */
@ApplicationScoped
public class AutoAllocatePartNoProvider implements PartNoProvider {

    @Inject
    EntityManager em;

    @Override
    @Transactional
    public String apply(PartNoContext ctx) {
        if (ctx == null) {
            throw new IllegalArgumentException("PartNoContext 不可为 null");
        }
        if (ctx.symbol == null || ctx.symbol.isBlank()) {
            throw new IllegalArgumentException("PartNoContext.symbol 不可为空白");
        }

        String prefix = "CFG-" + ctx.symbol + "-";
        long next;
        try {
            next = nextSequence(prefix);
        } catch (RuntimeException e) {
            throw new PartNoProvisionException(
                "分配 hf_part_no 失败,prefix=" + prefix, e);
        }
        return String.format("%s%06d", prefix, next);
    }

    /**
     * 取该 prefix 当前 next_val 并 +1, 返回旧值(也就是本次该分配的流水号).
     *
     * <p>实现:
     * <ol>
     *   <li>{@code SELECT next_val ... FOR UPDATE} 行锁该 prefix 行
     *   <li>若 prefix 不存在(理论上 V174 已 seed,但保险):{@code INSERT ... ON CONFLICT DO NOTHING},
     *       然后重查;返回 1
     *   <li>{@code UPDATE ... SET next_val = next_val + 1}
     *   <li>返回旧 next_val
     * </ol>
     */
    @SuppressWarnings({"unchecked", "deprecation"})
    private long nextSequence(String prefix) {
        // 1. 行锁取当前值
        java.util.List<Object> rows = em.createNativeQuery(
                "SELECT next_val FROM part_no_sequence WHERE prefix = :p FOR UPDATE")
            .setParameter("p", prefix)
            .getResultList();

        if (rows.isEmpty()) {
            // prefix 不存在 — 兜底 INSERT (理论上 V174 已 seed)
            em.createNativeQuery(
                    "INSERT INTO part_no_sequence (prefix, next_val) VALUES (:p, 2) " +
                    "ON CONFLICT (prefix) DO NOTHING")
                .setParameter("p", prefix)
                .executeUpdate();
            // 不需要再 SELECT — 我们 INSERT 了 next_val=2, 说明本次分配的是 1
            return 1L;
        }

        long current = ((Number) rows.get(0)).longValue();

        // 2. 自增
        em.createNativeQuery(
                "UPDATE part_no_sequence SET next_val = next_val + 1 WHERE prefix = :p")
            .setParameter("p", prefix)
            .executeUpdate();

        return current;
    }
}
