package com.cpq.globalvariable;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P1-C3 等价护栏：证明 {@link GlobalVariableService#resolveValues}（KvTable 单列 IN 批量）与逐元素
 * {@link GlobalVariableService#resolveValue} <b>逐位相同</b>（顺序对齐）。
 *
 * <p>用现役 KV_TABLE LOOKUP 变量 {@code PROCESS_DEFAULT_YIELD}（单 key=process_code）的真实 key_id
 * 做只读对比，外加一个必不存在的 miss key（验缺失→null）+ 一个重复 key（验 distinct 后分发回各位）。
 */
@QuarkusTest
class GlobalVariableResolveValuesBatchEquivTest {

    @Inject GlobalVariableService svc;
    @Inject EntityManager em;

    static final String CODE = "PROCESS_DEFAULT_YIELD";
    static final String KEYCOL = "process_code";

    @SuppressWarnings("unchecked")
    @Transactional
    List<String> realKeyIds(int limit) {
        return em.createNativeQuery(
                "SELECT key_id FROM global_variable_value WHERE var_code = :c ORDER BY key_id LIMIT :n")
                .setParameter("c", CODE).setParameter("n", limit).getResultList();
    }

    private static int cmp(BigDecimal a, BigDecimal b) {
        if (a == null && b == null) return 0;
        if (a == null || b == null) return 1;
        return a.compareTo(b);
    }

    @Test
    void batchEqualsPerRow_kvTable() {
        List<String> keyIds = realKeyIds(5);
        Assumptions.assumeTrue(keyIds.size() >= 2,
                "需要 PROCESS_DEFAULT_YIELD 至少 2 个真实 key 才能验批量");

        // 输入：真实 keys + miss + 重复首 key
        List<String> probe = new ArrayList<>(keyIds);
        probe.add("__NO_SUCH_KEY__");
        probe.add(keyIds.get(0));
        List<Map<String, Object>> kvs = new ArrayList<>();
        for (String k : probe) kvs.add(Map.of(KEYCOL, k));

        List<BigDecimal> perRow = new ArrayList<>();
        for (Map<String, Object> kv : kvs) perRow.add(svc.resolveValue(CODE, kv));

        List<BigDecimal> batch = svc.resolveValues(CODE, kvs);

        assertEquals(perRow.size(), batch.size(), "批量结果数应等于输入数");
        for (int i = 0; i < perRow.size(); i++) {
            assertEquals(0, cmp(perRow.get(i), batch.get(i)),
                    "idx " + i + " key=" + probe.get(i) + " 批量应=逐行 (" + perRow.get(i) + " vs " + batch.get(i) + ")");
        }
        assertNull(batch.get(keyIds.size()), "miss key 应解析为 null");
        assertEquals(0, cmp(batch.get(0), batch.get(batch.size() - 1)), "重复 key 两位结果应一致");

        // 空输入边界
        assertTrue(svc.resolveValues(CODE, List.of()).isEmpty(), "空输入返空");
    }
}
