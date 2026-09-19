package com.cpq.priceadjust.ac260918;

import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

import java.util.ArrayList;
import java.util.List;

/**
 * 原生 SQL 小工具：每次调用都在<b>独立新事务</b>里执行（读到的是已提交数据，写入立即提交），
 * 与被测服务的 REQUIRES_NEW / 异步线程之间不共享未提交状态（既有测试的踩坑经验，见
 * {@code PriceAdjustJobExecutionServiceBatchFallbackTest} 类注释）。
 *
 * <p>🚫 不绑定 null 参数（Hibernate 原生查询对 null 推断不出类型），需要 NULL 时在 SQL 文本里写字面量。
 */
public final class Rp0918aDb {

    private final EntityManager em;

    public Rp0918aDb(EntityManager em) {
        this.em = em;
    }

    public int exec(String sql, Object... nameValuePairs) {
        return QuarkusTransaction.requiringNew().call(() -> bind(em.createNativeQuery(sql), nameValuePairs).executeUpdate());
    }

    public Object scalar(String sql, Object... nameValuePairs) {
        return QuarkusTransaction.requiringNew().call(() -> {
            List<?> r = bind(em.createNativeQuery(sql), nameValuePairs).getResultList();
            if (r.isEmpty()) return null;
            Object first = r.get(0);
            if (first instanceof Object[] arr) return arr.length == 0 ? null : arr[0];
            return first;
        });
    }

    public long count(String sql, Object... nameValuePairs) {
        Object v = scalar(sql, nameValuePairs);
        return v == null ? 0L : ((Number) v).longValue();
    }

    public String text(String sql, Object... nameValuePairs) {
        Object v = scalar(sql, nameValuePairs);
        return v == null ? null : v.toString();
    }

    /** 多列结果；单列结果也包成 Object[1]，调用方统一按下标取。 */
    public List<Object[]> rows(String sql, Object... nameValuePairs) {
        return QuarkusTransaction.requiringNew().call(() -> {
            List<?> r = bind(em.createNativeQuery(sql), nameValuePairs).getResultList();
            List<Object[]> out = new ArrayList<>(r.size());
            for (Object o : r) out.add(o instanceof Object[] arr ? arr : new Object[]{o});
            return out;
        });
    }

    public List<Object> column(String sql, Object... nameValuePairs) {
        List<Object> out = new ArrayList<>();
        for (Object[] row : rows(sql, nameValuePairs)) out.add(row[0]);
        return out;
    }

    private static Query bind(Query q, Object... kv) {
        if (kv.length % 2 != 0) throw new IllegalArgumentException("参数必须成对：name, value");
        for (int i = 0; i < kv.length; i += 2) {
            Object value = kv[i + 1];
            if (value == null) {
                throw new IllegalArgumentException("参数 " + kv[i] + " 为 null —— 请在 SQL 里写字面量 NULL");
            }
            q.setParameter((String) kv[i], value);
        }
        return q;
    }
}
