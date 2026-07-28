package com.cpq.basicdata.v6.quote;

import com.cpq.basicdata.v6.parser.ImportContext;
import com.cpq.basicdata.v6.parser.SheetRow;
import com.cpq.basicdata.v6.service.ProcessNoResolver;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Task 7 集成测试：Q14 组装加工费 → capacity 版本化（calc_version 系统生成 + is_effective 保全）。
 *
 * <p>repair-0727：handler 改为从 {@code ctx.sharedCache["assemblyProcessNo"]} 取 Phase 1
 * 已解析结果落库，不再重复解析——本类单测直调 handler（不经 QuoteImportValidator），须显式
 * 预置 sharedCache（T4 测试纪律：禁止依赖静默兜底）。
 */
@QuarkusTest
class Q14AssemblyProcessFeeHandlerTest {

    @Inject Q14AssemblyProcessFeeHandler handler;
    @Inject EntityManager em;

    static final String MAT = "TEST-Q14-MAT";
    static final UUID UID = UUID.fromString("00000000-0000-0000-0000-000000000014");
    /** 本文件用到的全部「组装工序」原始值（测试里直接当编号使用）。 */
    static final List<String> PROC_CODES = List.of("OP14", "Z350", "Z400", "Z029");

    @Transactional void cleanup() {
        em.createNativeQuery("DELETE FROM capacity WHERE material_no=:m").setParameter("m", MAT).executeUpdate();
    }
    @BeforeEach void before() { cleanup(); }
    @AfterEach  void after()  { cleanup(); }

    private ImportContext ctx() { return ctx(Map.of()); }

    /** @param nameOverride 覆盖指定工序编号的 process_name（未覆盖的默认 "<code>-NAME"）。 */
    private ImportContext ctx(Map<String, String> nameOverride) {
        ImportContext c = new ImportContext();
        c.customerNo = "C1"; c.systemType = "QUOTE"; c.importedBy = UID;
        Map<List<String>, ProcessNoResolver.Resolved> assemblyProcessNo = new LinkedHashMap<>();
        for (String proc : PROC_CODES) {
            String name = nameOverride.getOrDefault(proc, proc + "-NAME");
            assemblyProcessNo.put(List.of("组装加工费", MAT, proc), new ProcessNoResolver.Resolved(proc, name));
        }
        c.sharedCache.put("assemblyProcessNo", assemblyProcessNo);
        return c;
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

    @Transactional
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
    @Transactional
    @Test void changeFeeOnly_inPlace_noBump() {
        handler.handle(List.of(row("OP14", 1, "100")), ctx());
        handler.handle(List.of(row("OP14", 1, "200")), ctx());
        assertEquals("2000", version(), "仅金额变 → 版本号不变");
        assertEquals(1L, total(), "原地更新,无历史堆积");
    }

    @Transactional
    @Test void changeProcessCode_bumps() {
        handler.handle(List.of(row("Z350", 1, "20")), ctx());
        handler.handle(List.of(row("Z400", 1, "20")), ctx());
        assertEquals("2001", version(), "工序编码变 → 升版");
        assertEquals(1L, current(), "新版当前 1 行");
        assertEquals(2L, total(), "旧版保留为历史");
    }

    @Transactional
    @Test void changeProcessCount_bumps_oldGroupRetired() {
        handler.handle(List.of(row("Z350", 1, "20"), row("Z029", 2, "14")), ctx());
        assertEquals(2L, current(), "首版 2 工序");
        handler.handle(List.of(row("Z350", 1, "20")), ctx());
        assertEquals("2001", version(), "工序减少 → 升版");
        assertEquals(1L, current(), "减掉的工序退出 current");
    }

    /** repair-0727 T4 单测①：从 sharedCache 预置解析结果落 process_no=真编号 + process_name=规范名。 */
    @Transactional
    @Test void sharedCacheResolvedResult_writesProcessNoAndProcessName() {
        handler.handle(List.of(row("Z350", 1, "20")), ctx());
        List<?> r = em.createNativeQuery(
            "SELECT process_no, process_name FROM capacity WHERE material_no=:m AND is_current=true")
            .setParameter("m", MAT).getResultList();
        assertEquals(1, r.size());
        Object[] row = (Object[]) r.get(0);
        assertEquals("Z350", row[0], "process_no 落真编号");
        assertEquals("Z350-NAME", row[1], "process_name 落 process_master 规范名");
    }

    /**
     * repair-0727 T4 单测②：CONTENT 含 process_name（原地更新即可写入新名）且
     * VERSION_TRIGGER 不含 process_name（改名不触发升版）——行为级验证，等价于直接断言常量列表。
     */
    @Transactional
    @Test void processNameChangeOnly_inPlace_noVersionBump() {
        handler.handle(List.of(row("Z350", 1, "20")), ctx());
        assertEquals("2000", version());
        assertEquals("Z350-NAME", processName());

        handler.handle(List.of(row("Z350", 1, "20")), ctx(Map.of("Z350", "焊接-改名")));
        assertEquals("2000", version(), "process_name 变化不在 VERSION_TRIGGER，不升版");
        assertEquals(1L, total(), "原地更新,无历史堆积");
        assertEquals("焊接-改名", processName(), "process_name 已按新解析结果原地更新");
    }

    private String processName() {
        List<?> r = em.createNativeQuery(
            "SELECT process_name FROM capacity WHERE material_no=:m AND is_current=true LIMIT 1")
            .setParameter("m", MAT).getResultList();
        return r.isEmpty() ? null : String.valueOf(r.get(0));
    }
}
