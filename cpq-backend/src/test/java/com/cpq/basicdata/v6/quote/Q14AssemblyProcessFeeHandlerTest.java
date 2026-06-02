package com.cpq.basicdata.v6.quote;

import com.cpq.basicdata.v6.parser.ImportContext;
import com.cpq.basicdata.v6.parser.SheetRow;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Task 7 集成测试：Q14 组装加工费 → capacity 版本化（calc_version 系统生成 + is_effective 保全）。 */
@QuarkusTest
class Q14AssemblyProcessFeeHandlerTest {

    @Inject Q14AssemblyProcessFeeHandler handler;
    @Inject EntityManager em;

    static final String MAT = "TEST-Q14-MAT";
    static final UUID UID = UUID.fromString("00000000-0000-0000-0000-000000000014");

    @Transactional void cleanup() {
        em.createNativeQuery("DELETE FROM capacity WHERE material_no=:m").setParameter("m", MAT).executeUpdate();
    }
    @BeforeEach void before() { cleanup(); }
    @AfterEach  void after()  { cleanup(); }

    private ImportContext ctx() {
        ImportContext c = new ImportContext();
        c.customerNo = "C1"; c.systemType = "QUOTE"; c.importedBy = UID; return c;
    }
    private SheetRow row(String proc, int seq, String fee) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("宏丰料号", MAT); m.put("组装工序", proc); m.put("项次", String.valueOf(seq));
        m.put("组装加工费", fee); m.put("货币", "CNY"); m.put("计价单位", "PCS"); m.put("拒收率", "0.01");
        return new SheetRow(seq, m);
    }
    private long current() {
        return ((Number) em.createNativeQuery(
            "SELECT count(*) FROM capacity WHERE material_no=:m AND is_current=true")
            .setParameter("m", MAT).getSingleResult()).longValue();
    }
    private String version() {
        List<?> r = em.createNativeQuery(
            "SELECT calc_version FROM capacity WHERE material_no=:m AND is_current=true LIMIT 1")
            .setParameter("m", MAT).getResultList();
        return r.isEmpty() ? null : String.valueOf(r.get(0));
    }
    private long total() {
        return ((Number) em.createNativeQuery("SELECT count(*) FROM capacity WHERE material_no=:m")
            .setParameter("m", MAT).getSingleResult()).longValue();
    }

    @Test void importTwice_idempotent_isEffectiveTrue() {
        handler.handle(List.of(row("OP14", 1, "100")), ctx());
        handler.handle(List.of(row("OP14", 1, "100")), ctx());
        assertEquals("2000", version(), "calc_version 系统生成（非 V_DEFAULT）");
        assertEquals(1L, total());
        Number eff = (Number) em.createNativeQuery(
            "SELECT count(*) FROM capacity WHERE material_no=:m AND is_effective=true")
            .setParameter("m", MAT).getSingleResult();
        assertEquals(1L, eff.longValue(), "is_effective=true 保全");
    }
    @Test void changeFeeOnly_inPlace_noBump() {
        handler.handle(List.of(row("OP14", 1, "100")), ctx());
        handler.handle(List.of(row("OP14", 1, "200")), ctx());
        assertEquals("2000", version(), "仅金额变 → 版本号不变");
        assertEquals(1L, total(), "原地更新,无历史堆积");
    }

    @Test void changeProcessCode_bumps() {
        handler.handle(List.of(row("Z350", 1, "20")), ctx());
        handler.handle(List.of(row("Z400", 1, "20")), ctx());
        assertEquals("2001", version(), "工序编码变 → 升版");
        assertEquals(1L, current(), "新版当前 1 行");
        assertEquals(2L, total(), "旧版保留为历史");
    }

    @Test void changeProcessCount_bumps_oldGroupRetired() {
        handler.handle(List.of(row("Z350", 1, "20"), row("Z029", 2, "14")), ctx());
        assertEquals(2L, current(), "首版 2 工序");
        handler.handle(List.of(row("Z350", 1, "20")), ctx());
        assertEquals("2001", version(), "工序减少 → 升版");
        assertEquals(1L, current(), "减掉的工序退出 current");
    }
}
