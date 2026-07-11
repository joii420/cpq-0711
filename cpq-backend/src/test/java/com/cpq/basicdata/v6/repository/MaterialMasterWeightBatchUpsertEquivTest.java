package com.cpq.basicdata.v6.repository;

import com.cpq.basicdata.v6.repository.MaterialMasterRepository.WeightRow;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.*;

import java.math.BigDecimal;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P1-A 类② 等价护栏:证明 {@link MaterialMasterRepository#upsertBatchWithWeight}(去重批量,末值非空胜)
 * 与原 Q18 逐行 {@link MaterialMasterRepository#upsertByMaterialNo}(no, null×5, weight, null, USER)
 * (10 参 = preserveDescriptive=false)逐行顺序调用产生 **逐位相同** 的 unit_weight 落库结果,
 * 且不改 name/type(EXCLUDED 恒 NULL → 保留 existing)。
 *
 * <p>覆盖:已存在行被末值非空覆盖 / 先 null 后非空 / 非空后尾随 null 不覆盖 / 单行 / <b>仅 null 权重也建行</b>。
 */
@QuarkusTest
class MaterialMasterWeightBatchUpsertEquivTest {

    @Inject MaterialMasterRepository repo;
    @Inject EntityManager em;

    static final UUID USER = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    static final String SEQ = "EQWS";   // 逐行命名空间
    static final String BAT = "EQWB";   // 批量命名空间

    /** 一次 upsert 操作(no 为逻辑键,跑时加前缀;w 可为 null)。 */
    record Op(String no, BigDecimal w) {}

    static BigDecimal d(String s) { return new BigDecimal(s).stripTrailingZeros(); }

    // 代表性序列(顺序敏感):
    //  W1 预置已存在行(weight=5,name/type 非空)→ ops [null,7] → 末值非空胜=7,name/type 保留
    //  W2 新建:null,非空,null → 末值非空胜=7(尾随 null 不覆盖)
    //  W3 新建:单行=3
    //  W4 新建:仅 null → 必须建行(unit_weight 留 null)
    //  W5 新建:3,8 → 末值非空胜=8
    static final List<Op> OPS = List.of(
        new Op("W1", null),
        new Op("W1", d("7")),
        new Op("W2", null),
        new Op("W2", d("7")),
        new Op("W2", null),
        new Op("W3", d("3")),
        new Op("W4", null),
        new Op("W5", d("3")),
        new Op("W5", d("8")));

    @Transactional
    void cleanup() {
        em.createNativeQuery("DELETE FROM material_master WHERE material_no LIKE :p")
          .setParameter("p", "EQW%").executeUpdate();
    }

    @BeforeEach void before() { cleanup(); }
    @AfterEach  void after()  { cleanup(); }

    /** 预置 W1 已存在行(带 name/type + weight=5),验证末值非空覆盖 weight 且 name/type 被保留。 */
    private void seedExisting(String prefix) {
        repo.upsertByMaterialNo(prefix + "W1", "WN", null, null, null, "WT",
            null, d("5"), null, null, USER);   // 11 参 = preserve=false,与 Q18 同
    }

    /** 返回 [unit_weight, material_name, material_type]。 */
    private Object[] row(String materialNo) {
        Object r = em.createNativeQuery(
            "SELECT unit_weight, material_name, material_type FROM material_master WHERE material_no = :no")
            .setParameter("no", materialNo).getSingleResult();
        return (Object[]) r;
    }

    @Test
    @Transactional
    void batchEqualsSequential_weightLastNonNull() {
        // --- 逐行路径(Q18 原语义:11 参 preserve=false)---
        seedExisting(SEQ);
        for (Op op : OPS) {
            repo.upsertByMaterialNo(SEQ + op.no(), null, null, null, null, null, null,
                op.w(), null, null, USER);
        }

        // --- 去重批量路径(末值非空胜 + 仅 null 也建行,与 Q18 handler 累积同规则)---
        seedExisting(BAT);
        Map<String, BigDecimal> acc = new LinkedHashMap<>();
        for (Op op : OPS) {
            if (!acc.containsKey(op.no()) || op.w() != null) acc.put(op.no(), op.w());
        }
        List<WeightRow> rows = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> e : acc.entrySet()) {
            rows.add(new WeightRow(BAT + e.getKey(), e.getValue()));
        }
        repo.upsertBatchWithWeight(rows, USER);

        // --- 逐 key 比对:逐行 vs 批量必须逐位相同(unit_weight + name + type)---
        for (String k : List.of("W1", "W2", "W3", "W4", "W5")) {
            Object[] s = row(SEQ + k);
            Object[] b = row(BAT + k);
            assertEquals(asNum(s[0]), asNum(b[0]), "key=" + k + " unit_weight 批量应=逐行");
            assertEquals(s[1], b[1], "key=" + k + " material_name 应一致");
            assertEquals(s[2], b[2], "key=" + k + " material_type 应一致");
        }

        // --- 额外锚定预期值,防两路同时错 ---
        assertEquals(d("7"), asNum(row(BAT + "W1")[0]), "W1 已存在 weight=5 被末值非空 7 覆盖");
        assertEquals("WN", row(BAT + "W1")[1], "W1 name 保留(EXCLUDED NULL)");
        assertEquals("WT", row(BAT + "W1")[2], "W1 type 保留(EXCLUDED NULL)");
        assertEquals(d("7"), asNum(row(BAT + "W2")[0]), "W2 末值非空胜=7(尾随 null 不覆盖)");
        assertEquals(d("3"), asNum(row(BAT + "W3")[0]), "W3 单行=3");
        assertNull(row(BAT + "W4")[0], "W4 仅 null 权重 → 建行且 unit_weight 留 null");
        assertEquals(d("8"), asNum(row(BAT + "W5")[0]), "W5 末值非空胜=8");
    }

    /** unit_weight 列以 numeric 返回,统一成 BigDecimal 并归一 scale 后比较。 */
    private static BigDecimal asNum(Object o) {
        if (o == null) return null;
        return new BigDecimal(o.toString()).stripTrailingZeros();
    }
}
