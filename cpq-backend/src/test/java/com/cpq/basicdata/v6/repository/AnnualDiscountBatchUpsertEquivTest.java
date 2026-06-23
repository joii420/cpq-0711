package com.cpq.basicdata.v6.repository;

import com.cpq.basicdata.v6.repository.AnnualDiscountRepository.DiscountRow;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.*;

import java.math.BigDecimal;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P1-Q19 等价护栏：证明 {@link AnnualDiscountRepository#upsertBatch}（去重 + 末值非空胜归并）与原
 * 逐行 {@link AnnualDiscountRepository#upsertOne}（= 原 Q19 SQL 逐字提取）顺序调用产生 **逐位相同** 的
 * annual_discount 落库结果。
 *
 * <p>覆盖：新建 / 同冲突键重复(逐字段末值非空胜) / 某字段先非空后 null 不覆盖 / 已存在行被 COALESCE 合并。
 */
@QuarkusTest
class AnnualDiscountBatchUpsertEquivTest {

    @Inject AnnualDiscountRepository repo;
    @Inject EntityManager em;

    static final UUID USER = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    static final String BT = "INCOMING";
    static final String DS = "来料年降";
    static final String SEQ = "EQAD_S";   // 逐行命名空间
    static final String BAT = "EQAD_B";   // 批量命名空间

    static BigDecimal d(String s) { return s == null ? null : new BigDecimal(s).stripTrailingZeros(); }

    /** 操作序列（no 逻辑键加前缀；order=1 固定，制造同冲突键重复）。 */
    record Op(String no, int order, String ratio, String fixed, String cur, String unit, Integer times) {}

    // 代表性序列（顺序敏感）：
    //  K1 预置已存在行(order=1) → ops 各字段 COALESCE 合并
    //  K2 新建 order=1：先(ratio=0.9,cur=USD) 后(ratio=null,cur=null,fixed=5) → 逐字段末值非空胜
    //  K3 新建 order=2：单行
    static final List<Op> OPS = List.of(
        new Op("K2", 1, "0.9", null, "USD", "件", 3),
        new Op("K2", 1, null, "5",  null,  null, null),
        new Op("K1", 1, "0.8", null, "CNY", null, null),
        new Op("K3", 2, "0.7", "2",  "EUR", "kg", 9));

    @Transactional
    void cleanup() {
        em.createNativeQuery("DELETE FROM annual_discount WHERE material_no LIKE :p")
          .setParameter("p", "EQAD%").executeUpdate();
    }

    @BeforeEach void before() { cleanup(); }
    @AfterEach  void after()  { cleanup(); }

    /** 预置 K1 已存在行(order=1, ratio=0.1, fixed=1, cur=JPY, unit=set, times=1)，验证后续 COALESCE 合并。 */
    private void seedExisting(String prefix) {
        repo.upsertOne(BT, DS, new DiscountRow(prefix + "K1", 1, d("0.1"), d("1"), "JPY", "set", 1), USER);
    }

    /** 返回 [discount_ratio, fixed_discount_value, currency, unit, discount_times]。 */
    private Object[] row(String materialNo, int order) {
        Object r = em.createNativeQuery(
            "SELECT discount_ratio, fixed_discount_value, currency, unit, discount_times " +
            "FROM annual_discount WHERE biz_type=:bt AND material_no=:m AND discount_strategy=:ds AND discount_order=:o")
            .setParameter("bt", BT).setParameter("m", materialNo).setParameter("ds", DS).setParameter("o", order)
            .getSingleResult();
        return (Object[]) r;
    }

    private static BigDecimal num(Object o) { return o == null ? null : new BigDecimal(o.toString()).stripTrailingZeros(); }

    @Test
    @Transactional
    void batchEqualsSequential() {
        DiscountRow[] seqRows = new DiscountRow[OPS.size()];
        // --- 逐行路径 ---
        seedExisting(SEQ);
        for (int i = 0; i < OPS.size(); i++) {
            Op op = OPS.get(i);
            DiscountRow r = new DiscountRow(SEQ + op.no(), op.order(), d(op.ratio()), d(op.fixed()),
                op.cur(), op.unit(), op.times());
            seqRows[i] = r;
            repo.upsertOne(BT, DS, r, USER);
        }

        // --- 去重批量路径（accDiscount 末值非空胜，与 handler 同规则）---
        seedExisting(BAT);
        Map<String, DiscountRow> acc = new LinkedHashMap<>();
        for (Op op : OPS) {
            AnnualDiscountRepository.accDiscount(acc, new DiscountRow(BAT + op.no(), op.order(),
                d(op.ratio()), d(op.fixed()), op.cur(), op.unit(), op.times()));
        }
        repo.upsertBatch(BT, DS, new ArrayList<>(acc.values()), USER);

        // --- 逐 key 比对：逐行 vs 批量必须逐位相同 ---
        for (String[] k : List.of(new String[]{"K1", "1"}, new String[]{"K2", "1"}, new String[]{"K3", "2"})) {
            int order = Integer.parseInt(k[1]);
            Object[] s = row(SEQ + k[0], order);
            Object[] b = row(BAT + k[0], order);
            assertEquals(num(s[0]), num(b[0]), k[0] + " discount_ratio");
            assertEquals(num(s[1]), num(b[1]), k[0] + " fixed_discount_value");
            assertEquals(s[2], b[2], k[0] + " currency");
            assertEquals(s[3], b[3], k[0] + " unit");
            assertEquals(s[4], b[4], k[0] + " discount_times");
        }

        // --- 额外锚定预期值，防两路同时错 ---
        // K2: ratio 末值非空=0.9(第二行 ratio null 不覆盖); fixed 末值非空=5; cur=USD(第二行 null 不覆盖); times=3
        Object[] k2 = row(BAT + "K2", 1);
        assertEquals(d("0.9"), num(k2[0]), "K2 ratio 末值非空胜");
        assertEquals(d("5"),   num(k2[1]), "K2 fixed 末值非空胜");
        assertEquals("USD",    k2[2],      "K2 currency 末值非空胜");
        assertEquals(3,        ((Number) k2[4]).intValue(), "K2 times 保留");
        // K1: 已存在(0.1/1/JPY/set/1) 被 op(0.8/null/CNY/null/null) COALESCE → 0.8/1/CNY/set/1
        Object[] k1 = row(BAT + "K1", 1);
        assertEquals(d("0.8"), num(k1[0]), "K1 ratio 被覆盖");
        assertEquals(d("1"),   num(k1[1]), "K1 fixed 保留旧(EXCLUDED null)");
        assertEquals("CNY",    k1[2],      "K1 currency 被覆盖");
        assertEquals("set",    k1[3],      "K1 unit 保留旧(EXCLUDED null)");
    }
}
