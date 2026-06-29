package com.cpq.component.service;

import com.cpq.component.service.ComponentDriverService.GvarDefaultTask;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P1-C3 等价护栏（caller 层）：证明 {@link ComponentDriverService#resolveGvarsBatched}（跨行批量预解析）
 * 与逐行 {@link ComponentDriverService#resolveGvarForRow} 对每行产出 <b>逐位相同</b> 的 gvar 值。
 *
 * <p>用现役 KV_TABLE 变量 {@code PROCESS_DEFAULT_YIELD}（key=process_code）构造合成 driverRows：
 * 直接命中 / 重复 key / 缺 key（不解→null）/ 别名命中（process_name→process_code）。
 * 当前线上无 gvar 绑定组件 → 此路径平时为 no-op，本测试是其唯一直接回归保障。
 */
@QuarkusTest
class ComponentDriverGvarBatchEquivTest {

    @Inject ComponentDriverService svc;
    @Inject EntityManager em;

    static final String CODE = "PROCESS_DEFAULT_YIELD";

    @SuppressWarnings("unchecked")
    @Transactional
    List<String> realKeyIds(int limit) {
        return em.createNativeQuery(
                "SELECT key_id FROM global_variable_value WHERE var_code = :c ORDER BY key_id LIMIT :n")
                .setParameter("c", CODE).setParameter("n", limit).getResultList();
    }

    @Test
    void batchEqualsPerRow_throughExpandHelper() {
        List<String> keys = realKeyIds(3);
        Assumptions.assumeTrue(keys.size() >= 2, "需要 PROCESS_DEFAULT_YIELD 至少 2 个真实 key");

        List<Map<String, Object>> driverRows = new ArrayList<>();
        driverRows.add(new LinkedHashMap<>(Map.of("process_code", keys.get(0))));            // 命中
        driverRows.add(new LinkedHashMap<>(Map.of("process_code", keys.get(1))));            // 命中
        driverRows.add(new LinkedHashMap<>(Map.of("other_col", "x")));                       // 缺 key → null
        driverRows.add(new LinkedHashMap<>(Map.of("process_code", keys.get(0))));            // 重复
        driverRows.add(new LinkedHashMap<>(Map.of("process_code", "__NO_SUCH__")));          // miss → null
        driverRows.add(new LinkedHashMap<>(Map.of("process_name", keys.get(1))));            // 别名命中

        GvarDefaultTask task = new GvarDefaultTask(CODE, new LinkedHashMap<>());
        String gk = ComponentDriverService.gvarKey(CODE);

        // 逐行基准
        List<Object> perRow = new ArrayList<>();
        for (Map<String, Object> dr : driverRows) perRow.add(svc.resolveGvarForRow(task, dr));

        // 批量
        List<Map<String, Object>> batched = svc.resolveGvarsBatched(List.of(task), driverRows);

        assertEquals(driverRows.size(), batched.size());
        for (int i = 0; i < driverRows.size(); i++) {
            assertEquals(perRow.get(i), batched.get(i).get(gk),
                    "row " + i + " 批量 gvar 值应=逐行 (" + perRow.get(i) + " vs " + batched.get(i).get(gk) + ")");
        }
        // 锚定：缺 key/miss 行必为 null；别名行与直接命中行一致（同 key_id）
        assertNull(batched.get(2).get(gk), "缺 key 行应 null");
        assertNull(batched.get(4).get(gk), "miss key 行应 null");
        assertEquals(batched.get(1).get(gk), batched.get(5).get(gk), "别名命中应=直接命中(同 key)");
        assertEquals(batched.get(0).get(gk), batched.get(3).get(gk), "重复 key 两行应一致");
    }
}
