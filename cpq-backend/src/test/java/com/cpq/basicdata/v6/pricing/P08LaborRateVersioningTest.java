package com.cpq.basicdata.v6.pricing;

import com.cpq.basicdata.v6.parser.ImportContext;
import com.cpq.basicdata.v6.parser.SheetRow;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P08 labor_rate 独立版本化（tesk-0709 §5.1 C 组）：
 * version_no 不再借用 capacity 版本号，按 material_no 独立聚合升版。
 * 附带验证 capacity 去触发列后语义变化：仅金额/状态类内容变化（非 process_no 增减）也应升版。
 */
@QuarkusTest
class P08LaborRateVersioningTest {

    @Inject P08CapacityHandler handler;
    @Inject EntityManager em;

    static final String MAT = "TEST-P08LR-MAT";
    static final UUID UID = UUID.fromString("00000000-0000-0000-0000-000000000008");

    @Transactional void cleanup() {
        em.createNativeQuery("DELETE FROM capacity WHERE material_no=:m").setParameter("m", MAT).executeUpdate();
        em.createNativeQuery("DELETE FROM labor_rate WHERE material_no=:m").setParameter("m", MAT).executeUpdate();
    }
    @BeforeEach void before() { cleanup(); }
    @AfterEach  void after()  { cleanup(); }

    private ImportContext ctx() {
        ImportContext c = new ImportContext();
        c.customerNo = "_GLOBAL_"; c.systemType = "PRICING"; c.importedBy = UID; return c;
    }

    private SheetRow row(String proc, String effective, String labor) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("宏丰料号", MAT); m.put("工序编号", proc); m.put("计算版本", "EXCEL-X");
        m.put("是否有效", effective); m.put("人工标准单价", labor);
        m.put("币种", "CNY"); m.put("计量单位", "小时"); m.put("生产料号", "P-" + MAT);
        return new SheetRow(1, m);
    }

    private String laborVersion() {
        List<?> r = em.createNativeQuery(
            "SELECT version_no FROM labor_rate WHERE material_no=:m AND system_type='PRICING' AND is_current=true LIMIT 1")
            .setParameter("m", MAT).getResultList();
        return r.isEmpty() ? null : String.valueOf(r.get(0));
    }
    private long laborCurrentCount() {
        return ((Number) em.createNativeQuery(
            "SELECT count(*) FROM labor_rate WHERE material_no=:m AND system_type='PRICING' AND is_current=true")
            .setParameter("m", MAT).getSingleResult()).longValue();
    }
    private long laborTotalCount() {
        return ((Number) em.createNativeQuery(
            "SELECT count(*) FROM labor_rate WHERE material_no=:m AND system_type='PRICING'")
            .setParameter("m", MAT).getSingleResult()).longValue();
    }
    private String capVersion() {
        List<?> r = em.createNativeQuery(
            "SELECT calc_version FROM capacity WHERE material_no=:m AND system_type='PRICING' AND is_current=true LIMIT 1")
            .setParameter("m", MAT).getResultList();
        return r.isEmpty() ? null : String.valueOf(r.get(0));
    }

    @Test void firstImport_laborRateVersion2000() {
        handler.handle(List.of(row("OP10", "是", "5.0"), row("OP20", "是", "6.0")), ctx());
        assertEquals("2000", laborVersion(), "labor_rate version_no 首版应系统生成 2000");
        assertEquals(2L, laborCurrentCount());
    }

    @Test void reimportIdentical_doesNotBumpLaborVersion() {
        handler.handle(List.of(row("OP10", "是", "5.0"), row("OP20", "是", "6.0")), ctx());
        handler.handle(List.of(row("OP10", "是", "5.0"), row("OP20", "是", "6.0")), ctx());
        assertEquals("2000", laborVersion(), "内容完全相同重导不应升版");
        assertEquals(2L, laborCurrentCount(), "不应产生重复 current 行");
        assertEquals(2L, laborTotalCount(), "不应残留额外历史行");
    }

    @Test void changeStandardLaborRate_bumpsLaborVersionAndKeepsHistory() {
        handler.handle(List.of(row("OP10", "是", "5.0"), row("OP20", "是", "6.0")), ctx());
        handler.handle(List.of(row("OP10", "是", "9.9"), row("OP20", "是", "6.0")), ctx());  // OP10 单价变化

        assertEquals("2001", laborVersion(), "工序单价变化应升版");
        assertEquals(2L, laborCurrentCount());

        // 旧版本行应保留（is_current=false），而非被删除
        long oldVersionRows = ((Number) em.createNativeQuery(
            "SELECT count(*) FROM labor_rate WHERE material_no=:m AND version_no='2000' AND is_current=false")
            .setParameter("m", MAT).getSingleResult()).longValue();
        assertEquals(2L, oldVersionRows, "旧版本(2000)的行应保留为历史(is_current=false)，不应被删除");
    }

    /**
     * capacity 去触发列验证：旧行为 VERSION_TRIGGER=[process_no]，仅 is_effective 变化（process_no 集合不变）
     * 不会升版；本次改造后 versionTriggerColumns=null 退化为用 contentColumns 作触发列，
     * 任一内容列（含 is_effective）变化即升版。
     */
    @Test void capacityContentChangeWithoutProcessChange_nowBumpsVersion() {
        handler.handle(List.of(row("OP10", "是", "5.0")), ctx());
        assertEquals("2000", capVersion());

        handler.handle(List.of(row("OP10", "否", "5.0")), ctx());  // 仅 is_effective 变化，process_no 集合不变
        assertEquals("2001", capVersion(), "去触发列后，is_effective 变化也应升版（旧语义下不会升版）");
    }
}
