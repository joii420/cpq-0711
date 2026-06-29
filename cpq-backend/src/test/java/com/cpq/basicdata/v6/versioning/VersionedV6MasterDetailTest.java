package com.cpq.basicdata.v6.versioning;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.*;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Task 1b（实现计划）：VersionedV6Writer.writeVersionedMasterDetail 主从版本化。
 *
 * <p>覆盖：
 * <ul>
 *   <li>element_bom（子表 uq 含 characteristic）：首版 2000；改子表 → 升版 + 旧版本子表保留（多版本）。</li>
 *   <li>material_bom（子表 uq 不含版本，characteristic=NULL）：NULL 安全幂等（#8）；升版 + 子表 upsert 仅当前版本（#7）。</li>
 *   <li>主表固定列 bom_type NOT NULL 写入（#4）；主/子分离 groupKey（#5）。</li>
 * </ul>
 */
@QuarkusTest
class VersionedV6MasterDetailTest {

    @Inject VersionedV6Writer writer;
    @Inject EntityManager em;

    static final String MAT = "TEST-MD-0001";

    @Transactional
    void cleanup() {
        for (String t : List.of("element_bom_item", "element_bom", "material_bom_item", "material_bom")) {
            em.createNativeQuery("DELETE FROM " + t + " WHERE material_no = :m")
              .setParameter("m", MAT).executeUpdate();
        }
    }

    @BeforeEach void before() { cleanup(); }
    @AfterEach  void after()  { cleanup(); }

    private long count(String table, String extraWhere) {
        String w = "material_no = '" + MAT + "'" + (extraWhere == null ? "" : (" AND " + extraWhere));
        return ((Number) em.createNativeQuery("SELECT count(*) FROM " + table + " WHERE " + w)
            .getSingleResult()).longValue();
    }

    // ---------- element_bom：子表 uq 含 characteristic → 多版本子表保留 ----------

    private Map<String, Object> ebGk() {  // 主/子身份（element_bom_item 含 system_type/customer_no/material_no）
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("system_type", "QUOTE"); m.put("customer_no", "C1"); m.put("material_no", MAT);
        return m;
    }
    private Map<String, Object> ebItem(int seq, String comp, String content) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("seq_no", seq); m.put("component_no", comp); m.put("content", new BigDecimal(content));
        return m;
    }
    private String writeEb(List<Map<String, Object>> items) {
        return writer.writeVersionedMasterDetail(
            "element_bom", "characteristic", ebGk(), Map.of("bom_type", "MATERIAL"),
            "element_bom_item", "characteristic", ebGk(),
            List.of("seq_no", "component_no", "content"), items);
    }

    @Test @Transactional
    void elementBom_firstWrite_at2000_withBomType() {
        String v = writeEb(List.of(ebItem(1, "Ag", "75"), ebItem(2, "Ni", "25")));
        assertEquals("2000", v);
        assertEquals(1L, count("element_bom", "characteristic='2000' AND is_current=true"));
        assertEquals(2L, count("element_bom_item", "characteristic='2000' AND is_current=true"));
        assertEquals(1L, count("element_bom", "characteristic='2000' AND bom_type='MATERIAL'")); // #4 NOT NULL
    }

    @Test @Transactional
    void elementBom_childChange_bumpsAndKeepsHistory() {
        writeEb(List.of(ebItem(1, "Ag", "75"), ebItem(2, "Ni", "25")));
        String v2 = writeEb(List.of(ebItem(1, "Ag", "70"), ebItem(2, "Ni", "30")));
        assertEquals("2001", v2);
        assertEquals(2L, count("element_bom_item", "is_current=true"));   // 仅新版本生效
        assertEquals(4L, count("element_bom_item", null));               // 旧 2 行 is_current=false 保留
        assertEquals(1L, count("element_bom", "is_current=true"));       // 主表仅最新生效
        assertEquals(2L, count("element_bom", null));                    // 主表两版本保留
    }

    @Test @Transactional
    void elementBom_sameContent_reusesVersion_noWrite() {
        writeEb(List.of(ebItem(1, "Ag", "75")));
        String v2 = writeEb(List.of(ebItem(1, "Ag", "75.00")));   // 75.00 vs 75 归一化等价
        assertEquals("2000", v2);
        assertEquals(1L, count("element_bom_item", null));        // 不翻倍
        assertEquals(1L, count("element_bom", null));
    }

    /**
     * 批量插入等价性特征测试（#1 性能优化护栏）：子行列集不一致时，
     * 每行必须仍只写入自己拥有的列、其余列保持 NULL —— 批量化（按列集签名分组）不得改变此行为。
     * r1 不含 component_usage_type，r2 含 → 两种插入签名。
     */
    @Test @Transactional
    void elementBom_mixedColumnChildRows_eachRowKeepsOwnColumns() {
        Map<String, Object> r1 = ebItem(1, "Ag", "75");                 // {seq_no, component_no, content}
        Map<String, Object> r2 = ebItem(2, "Ni", "25");
        r2.put("component_usage_type", "TYPE-X");                        // 仅 r2 多一列
        writer.writeVersionedMasterDetail(
            "element_bom", "characteristic", ebGk(), Map.of("bom_type", "MATERIAL"),
            "element_bom_item", "characteristic", ebGk(),
            List.of("seq_no", "component_no", "content", "component_usage_type"), List.of(r1, r2));
        assertEquals(2L, count("element_bom_item", "is_current=true"));
        assertEquals(1L, count("element_bom_item", "component_no='Ag' AND component_usage_type IS NULL"));
        assertEquals(1L, count("element_bom_item", "component_no='Ni' AND component_usage_type='TYPE-X'"));
    }

    // ---------- material_bom：子表 uq 无版本 + characteristic=NULL（Q03）→ NULL 安全 + upsert ----------

    private Map<String, Object> mbMasterGk(String bomType) {  // 主表身份含 bom_type 维度（#5）
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("system_type", "QUOTE"); m.put("customer_no", "C1"); m.put("material_no", MAT);
        m.put("bom_type", bomType);
        return m;
    }
    private Map<String, Object> mbChildGk(Object characteristic) {  // 子表身份：characteristic 判别（Map.of 不接受 null）
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("system_type", "QUOTE"); m.put("customer_no", "C1"); m.put("material_no", MAT);
        m.put("characteristic", characteristic);
        return m;
    }
    private Map<String, Object> mbItem(int seq, String comp, String qty) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("seq_no", seq); m.put("component_no", comp); m.put("composition_qty", new BigDecimal(qty));
        return m;
    }
    private String writeMb(List<Map<String, Object>> items) {
        return writer.writeVersionedMasterDetail(
            "material_bom", "bom_version", mbMasterGk("MATERIAL"), Map.of(),  // bom_type 已在 masterGroupKey
            "material_bom_item", null, mbChildGk(null),
            List.of("seq_no", "component_no", "composition_qty"), items);
    }

    @Test @Transactional
    void materialBom_nullCharacteristic_idempotent() {
        writeMb(List.of(mbItem(1, "X", "1"), mbItem(2, "Y", "2")));
        String v2 = writeMb(List.of(mbItem(1, "X", "1"), mbItem(2, "Y", "2")));  // 同内容
        assertEquals("2000", v2, "characteristic=NULL 组同内容应复用（NULL 安全匹配 #8）");
        assertEquals(2L, count("material_bom_item", "is_current=true"));  // #7：子表无版本，仅当前
        assertEquals(2L, count("material_bom_item", null));               // 未翻倍
        assertEquals(1L, count("material_bom", null));                    // 主表未升版
    }

    @Test @Transactional
    void materialBom_childChange_bumpsMaster_childCurrentOnly() {
        writeMb(List.of(mbItem(1, "X", "1"), mbItem(2, "Y", "2")));
        String v3 = writeMb(List.of(mbItem(1, "X", "1"), mbItem(2, "Z", "9")));  // 改一行
        assertEquals("2001", v3);
        assertEquals(1L, count("material_bom", "is_current=true"));   // 主表最新生效
        assertEquals(2L, count("material_bom", null));               // 主表两版本保留（bom_version 2000/2001）
        assertEquals(2L, count("material_bom_item", "is_current=true"));  // 子表 upsert 覆盖为当前 2 行
        assertEquals(2L, count("material_bom_item", null));          // 子表仅当前版本（§5.3，uq 无版本不撞键）
    }
}
