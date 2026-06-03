package com.cpq.basicdata.v6.versioning;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class VersionedV6WriterTest {

    @Inject VersionedV6Writer writer;
    @Inject EntityManager em;

    static final String FMN = "TEST-VER-0001";
    static final String CAP_MAT = "TEST-VER-CAP-01";

    @Transactional
    void cleanup() {
        em.createNativeQuery("DELETE FROM unit_price WHERE finished_material_no = :f")
          .setParameter("f", FMN).executeUpdate();
        em.createNativeQuery("DELETE FROM capacity WHERE material_no = :m")
          .setParameter("m", CAP_MAT).executeUpdate();
    }

    @BeforeEach void before() { cleanup(); }
    @AfterEach  void after()  { cleanup(); }

    private VersionedGroupSpec spec(List<Map<String, Object>> rows) {
        return new VersionedGroupSpec(
            "unit_price", "version_no",
            new java.util.LinkedHashMap<>(Map.of(
                "system_type", "QUOTE", "price_type", "MATERIAL",
                "cost_type", "自制加工费", "finished_material_no", FMN, "code", FMN)),
            List.of("operation_no", "seq_no"),
            rows);
    }

    private List<Map<String, Object>> rows(String... ops) {
        java.util.ArrayList<Map<String, Object>> r = new java.util.ArrayList<>();
        int seq = 1;
        for (String op : ops) {
            r.add(new java.util.LinkedHashMap<>(Map.of("operation_no", op, "seq_no", seq++)));
        }
        return r;
    }

    @Test @Transactional
    void firstWrite_startsAt2000() {
        String v = writer.writeVersionedGroup(spec(rows("OP1", "OP2")));
        assertEquals("2000", v);
        Number cnt = (Number) em.createNativeQuery(
            "SELECT count(*) FROM unit_price WHERE finished_material_no=:f AND is_current=TRUE")
            .setParameter("f", FMN).getSingleResult();
        assertEquals(2L, cnt.longValue());
    }

    @Test @Transactional
    void sameContent_reusesVersion_noNewRows() {
        writer.writeVersionedGroup(spec(rows("OP1", "OP2")));
        String v2 = writer.writeVersionedGroup(spec(rows("OP1", "OP2")));
        assertEquals("2000", v2);
        Number total = (Number) em.createNativeQuery(
            "SELECT count(*) FROM unit_price WHERE finished_material_no=:f")
            .setParameter("f", FMN).getSingleResult();
        assertEquals(2L, total.longValue(), "内容相同不应新增版本行");
    }

    @Test @Transactional
    void differentContent_bumpsVersion_flipsIsCurrent() {
        writer.writeVersionedGroup(spec(rows("OP1", "OP2")));
        String v2 = writer.writeVersionedGroup(spec(rows("OP1", "OP2", "OP3")));
        assertEquals("2001", v2);
        Number current = (Number) em.createNativeQuery(
            "SELECT count(*) FROM unit_price WHERE finished_material_no=:f AND is_current=TRUE")
            .setParameter("f", FMN).getSingleResult();
        assertEquals(3L, current.longValue(), "当前生效=新版本3行");
        Number old = (Number) em.createNativeQuery(
            "SELECT count(*) FROM unit_price WHERE finished_material_no=:f AND is_current=FALSE")
            .setParameter("f", FMN).getSingleResult();
        assertEquals(2L, old.longValue(), "旧版本2行被下线");
    }

    @Test @Transactional
    void emptyNewRows_rejected() {
        VersionedGroupSpec s = spec(rows()); // newRows 为空
        assertThrows(IllegalArgumentException.class, () -> writer.writeVersionedGroup(s));
    }

    @Test @Transactional
    void duplicateFirstContentCol_stillReused() {
        // 两行 operation_no 相同(seq_no 不同 1/2):验证 multiset 比较在重复值下仍判"相同复用"
        String v1 = writer.writeVersionedGroup(spec(rows("OP1", "OP1")));
        assertEquals("2000", v1);
        String v2 = writer.writeVersionedGroup(spec(rows("OP1", "OP1")));
        assertEquals("2000", v2);
        Number total = (Number) em.createNativeQuery(
            "SELECT count(*) FROM unit_price WHERE finished_material_no=:f")
            .setParameter("f", FMN).getSingleResult();
        assertEquals(2L, total.longValue(), "内容相同(含重复首列)不应新增版本行");
    }

    @Test @Transactional
    void ignoresNonNumericVersion() {
        String v1 = writer.writeVersionedGroup(spec(rows("OP1")));
        assertEquals("2000", v1);
        em.createNativeQuery("INSERT INTO unit_price (system_type,price_type,version_no,code,cost_type,finished_material_no,operation_no,seq_no,is_current) "
            + "VALUES ('QUOTE','MATERIAL','V_DEFAULT',:f,'自制加工费',:f,'OPX',9,false)")
          .setParameter("f", FMN).executeUpdate();
        String v2 = writer.writeVersionedGroup(spec(rows("OP1", "OP2")));
        assertEquals("2001", v2, "忽略 V_DEFAULT,基于数字版本 max+1");
    }

    @Test @Transactional
    void thirdSameContent_reusesLatest() {
        writer.writeVersionedGroup(spec(rows("OP1")));
        String v2 = writer.writeVersionedGroup(spec(rows("OP1", "OP2")));
        assertEquals("2001", v2);
        String v3 = writer.writeVersionedGroup(spec(rows("OP1", "OP2")));
        assertEquals("2001", v3, "第三次内容同最新 current,复用 2001 而非回退 2000");
    }

    // ===== capacity + versionTriggerColumns（仅 process_no/seq_no 触发升版） =====

    /** capacity 料号级组：groupKey=(material_no, resource_group_no)，triggerCols=[process_no, seq_no]。 */
    private VersionedGroupSpec capSpec(List<Map<String, Object>> rows) {
        return new VersionedGroupSpec(
            "capacity", "calc_version",
            new java.util.LinkedHashMap<>(Map.of(
                "material_no", CAP_MAT, "resource_group_no", "QUOTE_ASSEMBLY")),
            List.of("process_no", "seq_no", "fixed_cost"),
            rows,
            List.of("process_no", "seq_no"));
    }

    /** 构造一组工序行：proc=工序编码，fee=金额。 */
    private List<Map<String, Object>> capRows(String[] procs, String[] fees) {
        java.util.ArrayList<Map<String, Object>> r = new java.util.ArrayList<>();
        for (int i = 0; i < procs.length; i++) {
            java.util.LinkedHashMap<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("process_no", procs[i]);
            m.put("seq_no", i + 1);
            m.put("fixed_cost", new java.math.BigDecimal(fees[i]));
            m.put("production_type", "BATCH_FIXED");   // capacity.production_type NOT NULL + CHECK 约束
            r.add(m);
        }
        return r;
    }
    private String capVersion() {
        List<?> r = em.createNativeQuery(
            "SELECT calc_version FROM capacity WHERE material_no=:m AND is_current=true LIMIT 1")
            .setParameter("m", CAP_MAT).getResultList();
        return r.isEmpty() ? null : String.valueOf(r.get(0));
    }
    private long capTotal() {
        return ((Number) em.createNativeQuery("SELECT count(*) FROM capacity WHERE material_no=:m")
            .setParameter("m", CAP_MAT).getSingleResult()).longValue();
    }
    private long capCurrent() {
        return ((Number) em.createNativeQuery(
            "SELECT count(*) FROM capacity WHERE material_no=:m AND is_current=true")
            .setParameter("m", CAP_MAT).getSingleResult()).longValue();
    }

    @Test @Transactional
    void cap_changeProcessCode_bumpsVersion() {
        writer.writeVersionedGroup(capSpec(capRows(new String[]{"Z350", "Z029"}, new String[]{"20", "14"})));
        String v2 = writer.writeVersionedGroup(capSpec(capRows(new String[]{"Z400", "Z029"}, new String[]{"20", "14"})));
        assertEquals("2001", v2, "工序编码变 → 升版");
        assertEquals(2L, capCurrent(), "新版当前 2 行");
        assertEquals(4L, capTotal(), "旧版 2 行保留为历史(is_current=false)");
    }

    @Test @Transactional
    void cap_changeProcessCount_bumpsVersion() {
        writer.writeVersionedGroup(capSpec(capRows(new String[]{"Z350", "Z029"}, new String[]{"20", "14"})));
        String v2 = writer.writeVersionedGroup(capSpec(capRows(new String[]{"Z350"}, new String[]{"20"})));
        assertEquals("2001", v2, "工序减少 → 升版");
        assertEquals(1L, capCurrent(), "新版当前仅 1 行(减掉的工序退出 current)");
    }

    @Test @Transactional
    void cap_changeFeeOnly_inPlaceUpdate_noVersionBump() {
        writer.writeVersionedGroup(capSpec(capRows(new String[]{"Z350", "Z029"}, new String[]{"20", "14"})));
        String v2 = writer.writeVersionedGroup(capSpec(capRows(new String[]{"Z350", "Z029"}, new String[]{"25", "14"})));
        assertEquals("2000", v2, "仅金额变 → 版本号不变");
        assertEquals(2L, capCurrent(), "当前仍 2 行(原地更新,无新历史)");
        assertEquals(2L, capTotal(), "总行数不变(无历史行堆积)");
        java.math.BigDecimal fee = (java.math.BigDecimal) em.createNativeQuery(
            "SELECT fixed_cost FROM capacity WHERE material_no=:m AND process_no='Z350' AND is_current=true")
            .setParameter("m", CAP_MAT).getSingleResult();
        assertEquals(0, fee.compareTo(new java.math.BigDecimal("25")), "金额已原地更新为 25");
    }

    @Test @Transactional
    void cap_identical_reusesVersion() {
        writer.writeVersionedGroup(capSpec(capRows(new String[]{"Z350", "Z029"}, new String[]{"20", "14"})));
        String v2 = writer.writeVersionedGroup(capSpec(capRows(new String[]{"Z350", "Z029"}, new String[]{"20", "14"})));
        assertEquals("2000", v2, "完全相同 → 复用版本");
        assertEquals(2L, capTotal(), "无新写入");
    }

    // ===== 护栏：system_type 维度表 groupKey 必含 system_type =====

    @Test
    void writeVersionedGroup_missingSystemType_throws() {
        Map<String, Object> gk = new java.util.LinkedHashMap<>();
        gk.put("scheme_no", "GUARD-TEST");          // 故意不放 system_type
        VersionedGroupSpec spec = new VersionedGroupSpec(
            "plating_scheme", "scheme_version", gk,
            java.util.List.of("seq_no"),
            java.util.List.of(java.util.Map.of("seq_no", 1)));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> writer.writeVersionedGroup(spec));
        assertTrue(ex.getMessage().contains("system_type"),
            "缺 system_type 应在入口抛错，实际: " + ex.getMessage());
    }

    @Test
    void writeVersionedMasterDetail_missingSystemType_throws() {
        Map<String, Object> gk = new java.util.LinkedHashMap<>();
        gk.put("customer_no", "_GLOBAL_");
        gk.put("material_no", "GUARD-TEST");
        gk.put("bom_type", "MATERIAL");             // 故意不放 system_type
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> writer.writeVersionedMasterDetail(
                "material_bom", "bom_version", gk, java.util.Map.of(),
                "material_bom_item", null, gk,
                java.util.List.of("seq_no"),
                java.util.List.of(java.util.Map.of("seq_no", 1))));
        assertTrue(ex.getMessage().contains("system_type"),
            "缺 system_type 应在入口抛错，实际: " + ex.getMessage());
    }
}
