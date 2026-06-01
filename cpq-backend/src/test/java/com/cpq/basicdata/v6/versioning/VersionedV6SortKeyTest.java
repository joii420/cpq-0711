package com.cpq.basicdata.v6.versioning;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 回归测试（实现计划 Task 1a，校验发现 #1）：锁定 VersionedV6Writer.rowsEqual 的 multiset 行为。
 *
 * <p>背景：rowsEqual 早已用 tally() multiset 计数比较（顺序无关 + 同首列多行安全），
 * 而非按 contentColumns.get(0) 单列排序。本测试断言该正确行为，防止未来回退成单列排序
 * （单列排序在「同首列多行」时会误判内容不同 → 凭空升版翻倍）。
 */
@QuarkusTest
class VersionedV6SortKeyTest {

    @Inject VersionedV6Writer writer;
    @Inject EntityManager em;

    static final String FMN = "TEST-SORT-0001";

    @Transactional
    void cleanup() {
        em.createNativeQuery("DELETE FROM unit_price WHERE finished_material_no = :f")
          .setParameter("f", FMN).executeUpdate();
    }

    @BeforeEach void before() { cleanup(); }
    @AfterEach  void after()  { cleanup(); }

    private VersionedGroupSpec spec(List<Map<String, Object>> rows) {
        return new VersionedGroupSpec("unit_price", "version_no",
            new LinkedHashMap<>(Map.of(
                "system_type", "QUOTE", "customer_no", "C1",
                "price_type", "MATERIAL", "cost_type", "来料其他费用",
                "finished_material_no", FMN, "code", FMN)),
            // 首列 cost_ratio 在组内重复（两行都是 10），考验 multiset vs 首列排序；
            // 行靠 seq_no 区分（seq_no 在 uq_unit_price 内 → 两行可共存不撞键，cost_ratio 不在 uq 内）。
            List.of("cost_ratio", "seq_no"), rows);
    }

    private Map<String, Object> r(String ratio, int seq) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("cost_ratio", new java.math.BigDecimal(ratio));
        m.put("seq_no", seq);
        return m;
    }

    @Test @Transactional
    void sameContentDifferentOrder_sameFirstCol_reusesVersion() {
        // 两行首列 cost_ratio 同为 10，靠 seq_no(1/2) 区分；若按 contentColumns.get(0) 单列排序会在
        // cost_ratio 平手时错配 → 误判内容不同 → 升版翻倍。multiset 计数则顺序无关、判等正确。
        writer.writeVersionedGroup(spec(List.of(r("10", 1), r("10", 2))));
        String v2 = writer.writeVersionedGroup(spec(List.of(r("10", 2), r("10", 1)))); // 顺序颠倒、内容相同
        assertEquals("2000", v2, "同首列多行、仅顺序不同应判'相同'不升版（multiset 行为）");
        Number total = (Number) em.createNativeQuery(
            "SELECT count(*) FROM unit_price WHERE finished_material_no=:f")
            .setParameter("f", FMN).getSingleResult();
        assertEquals(2L, total.longValue(), "不应凭空升版翻倍");
    }
}
