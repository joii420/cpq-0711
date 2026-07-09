package com.cpq.basicdata.v6.repository;

import com.cpq.basicdata.v6.repository.ElementRecoveryDiscountRepository.Update;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.*;

import java.math.BigDecimal;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P2-Q05 等价护栏：证明 {@link ElementRecoveryDiscountRepository#countCurrentMatches} +
 * {@link ElementRecoveryDiscountRepository#batchUpdate}（去重末值胜）与原逐行
 * {@link ElementRecoveryDiscountRepository#updateOne} 顺序调用产生 **逐位相同** 的 element_bom_item
 * 落库状态；且 countCurrentMatches 的每键计数 = 逐行 updated 计数。
 *
 * <p>覆盖：多匹配(2 行 is_current)/单匹配/未匹配(0)/批内重复末值胜/null 覆盖/is_current=false 不动。
 */
@QuarkusTest
class ElementRecoveryBatchUpdateEquivTest {

    @Inject ElementRecoveryDiscountRepository repo;
    @Inject EntityManager em;

    static final UUID USER = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    static final String CS = "TESTQ05S";   // 逐行命名空间(按 customer_no 隔离)
    static final String CB = "TESTQ05B";   // 批量命名空间

    static BigDecimal d(String s) { return s == null ? null : new BigDecimal(s); }

    record Op(String m, String cn, String rd) {}
    // 顺序敏感：M1/C1 先 1.0 后 2.0(末值胜) / M1/C2 置 null / M2/C1 未匹配(无 seed)
    static final List<Op> OPS = List.of(
        new Op("M1", "C1", "1.0"),
        new Op("M1", "C1", "2.0"),
        new Op("M1", "C2", null),
        new Op("M2", "C1", "5.0"));

    @Transactional
    void cleanup() {
        em.createNativeQuery("DELETE FROM element_bom_item WHERE customer_no IN (:a,:b)")
          .setParameter("a", CS).setParameter("b", CB).executeUpdate();
    }

    @BeforeEach void before() { cleanup(); }
    @AfterEach  void after()  { cleanup(); }

    private void seed(String cust, String m, String ch, String cn, boolean cur) {
        em.createNativeQuery(
            "INSERT INTO element_bom_item (system_type, customer_no, material_no, characteristic, " +
            "  component_no, recovery_discount, is_current, created_at, updated_at) " +
            "VALUES ('QUOTE', :c, :m, :ch, :cn, 9, :cur, NOW(), NOW())")
            .setParameter("c", cust).setParameter("m", m).setParameter("ch", ch)
            .setParameter("cn", cn).setParameter("cur", cur).executeUpdate();
    }

    private void seedNamespace(String cust) {
        seed(cust, "M1", "A", "C1", true);    // (M1,C1) is_current 行1
        seed(cust, "M1", "B", "C1", true);    // (M1,C1) is_current 行2 → 多匹配 count=2
        seed(cust, "M1", "A", "C2", true);    // (M1,C2) count=1
        seed(cust, "M1", "C", "C1", false);   // is_current=false → 永不匹配
    }

    /** 返回 customer 下所有行的 [characteristic|component_no -> recovery_discount] 稳定快照串。 */
    @Transactional
    String snapshot(String cust) {
        Object r = em.createNativeQuery(
            "SELECT COALESCE(string_agg(material_no||'/'||characteristic||'/'||COALESCE(component_no,'')||'='||" +
            "COALESCE(recovery_discount::text,'NULL'), ';' ORDER BY material_no, characteristic, component_no),'') " +
            "FROM element_bom_item WHERE customer_no = :c").setParameter("c", cust).getSingleResult();
        return String.valueOf(r);
    }

    @Test
    @Transactional
    void batchEqualsSequential() {
        // --- 逐行路径 ---
        // 注：seed 未设 material_part_no(NULL→COALESCE ''),故全程传 null 即等价中性
        //    (task-0708 料号语义把键从 2 维扩到 3 维；本等价护栏用统一 null 保持不变)。
        seedNamespace(CS);
        for (Op op : OPS) repo.updateOne(CS, op.m(), null, op.cn(), d(op.rd()), USER);

        // --- 批量路径(去重末值胜 + countMatches + batchUpdate) ---
        seedNamespace(CB);
        Map<String, Update> dedup = new LinkedHashMap<>();
        for (Op op : OPS) dedup.put(ElementRecoveryDiscountRepository.key(op.m(), null, op.cn()),
                new Update(op.m(), null, op.cn(), d(op.rd())));
        List<String[]> keys = new ArrayList<>();
        for (Update u : dedup.values()) keys.add(new String[]{u.materialNo(), u.materialPartNo(), u.componentNo()});
        Map<String, Integer> cnt = repo.countCurrentMatches(CB, keys);
        repo.batchUpdate(CB, new ArrayList<>(dedup.values()), USER);

        // --- 落库状态逐位一致(把命名空间前缀归一后比对) ---
        assertEquals(snapshot(CS).replace(CS, "X"), snapshot(CB).replace(CB, "X"),
            "批量与逐行 element_bom_item 落库状态应逐位一致");

        // --- countMatches = 逐行 updated 计数 ---
        assertEquals(2, cnt.get(ElementRecoveryDiscountRepository.key("M1", null, "C1")), "(M1,C1) 多匹配=2");
        assertEquals(1, cnt.get(ElementRecoveryDiscountRepository.key("M1", null, "C2")), "(M1,C2)=1");
        assertNull(cnt.get(ElementRecoveryDiscountRepository.key("M2", null, "C1")), "(M2,C1) 未匹配 → 不在 map");

        // --- 锚定预期 final 值 ---
        assertEquals("M1/A/C1=2.0000;M1/A/C2=NULL;M1/B/C1=2.0000;M1/C/C1=9.0000",
            snapshot(CB).replace(CB, ""), "末值2.0覆盖多匹配 / C2置null / is_current=false行(9)不动");
    }
}
