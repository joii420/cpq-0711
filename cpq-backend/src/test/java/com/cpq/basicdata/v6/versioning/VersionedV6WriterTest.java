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

    @Transactional
    void cleanup() {
        em.createNativeQuery("DELETE FROM unit_price WHERE finished_material_no = :f")
          .setParameter("f", FMN).executeUpdate();
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
}
