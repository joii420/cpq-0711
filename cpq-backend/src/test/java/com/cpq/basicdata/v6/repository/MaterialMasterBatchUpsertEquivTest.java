package com.cpq.basicdata.v6.repository;

import com.cpq.basicdata.v6.repository.MaterialMasterRepository.NameTypeRow;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #2 性能优化等价护栏：证明 {@link MaterialMasterRepository#upsertBatchNameType}（去重批量，preserve=true）
 * 与原 {@link MaterialMasterRepository#upsertByMaterialNo}(...,preserve=true) 逐行顺序调用产生**逐位相同**的
 * (material_name, material_type) 落库结果。
 *
 * <p>覆盖：新建 / 同 material_no 重复(首个非空胜) / name 先空后非空回填 / 已存在行 preserve 保留旧值。
 * 做法：同一组操作序列分别跑「逐行」(SEQ_ 前缀) 与「去重批量」(BAT_ 前缀)，逐 key 比对最终行。
 */
@QuarkusTest
class MaterialMasterBatchUpsertEquivTest {

    @Inject MaterialMasterRepository repo;
    @Inject EntityManager em;

    static final UUID USER = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    static final String SEQ = "EQS";   // 逐行命名空间
    static final String BAT = "EQB";   // 批量命名空间

    /** 一次 upsert 操作（no 为逻辑键，跑时加前缀）。 */
    record Op(String no, String name, String type) {}

    // 代表性序列（顺序敏感）：
    //  X1 预置已存在行 → ops 应被 preserve 全部挡住（保留 Old/OldT）
    //  X2 新建：name 先空后非空 → 首个非空 name 回填
    //  X3 新建：单行
    //  X4 新建：重复，首个非空 name/type 胜，后续被 preserve 挡
    static final List<Op> OPS = List.of(
        new Op("X1", "NameA",  "T1"),
        new Op("X1", "NameA2", "T1b"),
        new Op("X2", null,     "组成件"),
        new Op("X2", "NameB",  "组成件"),
        new Op("X3", "NameC",  "Tc"),
        new Op("X4", "NameD",  "TD"),
        new Op("X4", "NameD2", "TD2"));

    @Transactional
    void cleanup() {
        em.createNativeQuery("DELETE FROM material_master WHERE material_no LIKE :p")
          .setParameter("p", "EQ%").executeUpdate();
    }

    @BeforeEach void before() { cleanup(); }
    @AfterEach  void after()  { cleanup(); }

    /** 预置 X1 已存在行（两命名空间各一），验证 preserve 保留旧值。 */
    private void seedExisting(String prefix) {
        repo.upsertByMaterialNo(prefix + "X1", "Old", null, null, null, "OldT",
            null, null, null, USER, true);
    }

    private Object[] row(String materialNo) {
        Object r = em.createNativeQuery(
            "SELECT material_name, material_type FROM material_master WHERE material_no = :no")
            .setParameter("no", materialNo).getSingleResult();
        return (Object[]) r;
    }

    @Test
    @Transactional
    void batchEqualsSequential_underPreserve() {
        // --- 逐行路径 ---
        seedExisting(SEQ);
        for (Op op : OPS) {
            repo.upsertByMaterialNo(SEQ + op.no(), op.name(), null, null, null, op.type(),
                null, null, null, USER, true);
        }

        // --- 去重批量路径（首个非空归并，与 handler accMaterialMaster 同规则）---
        seedExisting(BAT);
        Map<String, String[]> acc = new LinkedHashMap<>();
        for (Op op : OPS) {
            String[] cur = acc.get(op.no());
            if (cur == null) acc.put(op.no(), new String[]{op.name(), op.type()});
            else { if (cur[0] == null) cur[0] = op.name(); if (cur[1] == null) cur[1] = op.type(); }
        }
        List<NameTypeRow> rows = new ArrayList<>();
        for (Map.Entry<String, String[]> e : acc.entrySet()) {
            rows.add(new NameTypeRow(BAT + e.getKey(), e.getValue()[0], e.getValue()[1]));
        }
        repo.upsertBatchNameType(rows, USER, true);

        // --- 逐 key 比对：逐行 vs 批量必须逐位相同 ---
        for (String k : List.of("X1", "X2", "X3", "X4")) {
            assertArrayEquals(row(SEQ + k), row(BAT + k),
                "key=" + k + " 批量与逐行的 (name,type) 应一致");
        }

        // --- 额外锚定预期值，防两路同时错 ---
        assertArrayEquals(new Object[]{"Old", "OldT"}, row(BAT + "X1"), "X1 已存在 → preserve 保留旧值");
        assertArrayEquals(new Object[]{"NameB", "组成件"}, row(BAT + "X2"), "X2 → 首个非空 name 回填");
        assertArrayEquals(new Object[]{"NameC", "Tc"}, row(BAT + "X3"), "X3 → 单行直写");
        assertArrayEquals(new Object[]{"NameD", "TD"}, row(BAT + "X4"), "X4 → 首个非空 name/type 胜");
    }
}
