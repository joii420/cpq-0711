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
 * repair-0804：Q15 组装加工费年降 → annual_discount(discount_type=ASSEMBLY_PROCESS) 版本化。
 * 行集维度=discount_order。
 *
 * <p>repair-0727：handler 改为从 {@code ctx.sharedCache["assemblyProcessNo"]} 取 Phase 1
 * 已解析结果落 {@code target_no}——本类单测直调 handler，须显式预置 sharedCache。该语义本次不变，
 * 只改落库目标表由 unit_price 切到 annual_discount。
 */
@QuarkusTest
class Q15AssemblyAnnualDiscountHandlerTest {

    @Inject Q15AssemblyAnnualDiscountHandler handler;
    @Inject EntityManager em;

    static final String CODE = "TEST-Q15-CODE";
    static final UUID UID = UUID.fromString("00000000-0000-0000-0000-000000000015");
    static final String RAW_PROCESS = "ASM1";
    static final String RESOLVED_NO = "Z-ASM1";

    @Transactional void cleanup() {
        em.createNativeQuery("DELETE FROM annual_discount WHERE material_no=:m").setParameter("m", CODE).executeUpdate();
    }
    @BeforeEach void before() { cleanup(); }
    @AfterEach  void after()  { cleanup(); }

    private ImportContext ctx() {
        ImportContext c = new ImportContext();
        c.customerNo = "C1"; c.systemType = "QUOTE"; c.importedBy = UID;
        Map<List<String>, ProcessNoResolver.Resolved> assemblyProcessNo = new LinkedHashMap<>();
        // Q15 不写名称，processName 传 null 亦可，这里给个可辨识值方便排查。
        assemblyProcessNo.put(List.of("组装加工费年降", CODE, RAW_PROCESS),
            new ProcessNoResolver.Resolved(RESOLVED_NO, "ASM1-NAME"));
        c.sharedCache.put("assemblyProcessNo", assemblyProcessNo);
        return c;
    }
    private SheetRow row(int order, String ratio) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("宏丰料号", CODE); m.put("组装工序", RAW_PROCESS);
        m.put("年降顺序", String.valueOf(order)); m.put("年降系数", ratio);
        m.put("货币", "CNY"); m.put("计价单位", "PCS");
        return new SheetRow(order, m);
    }
    private String version() {
        List<?> r = em.createNativeQuery(
            "SELECT version_no FROM annual_discount WHERE material_no=:m AND is_current=true LIMIT 1")
            .setParameter("m", CODE).getResultList();
        return r.isEmpty() ? null : String.valueOf(r.get(0));
    }
    private long total() {
        return ((Number) em.createNativeQuery("SELECT count(*) FROM annual_discount WHERE material_no=:m")
            .setParameter("m", CODE).getSingleResult()).longValue();
    }

    @Transactional
    @Test void importTwice_idempotent() {
        handler.handle(List.of(row(1, "0.97"), row(2, "0.94")), ctx());
        handler.handle(List.of(row(1, "0.97"), row(2, "0.94")), ctx());
        assertEquals("2000", version());
        assertEquals(2L, total());
    }
    @Transactional
    @Test void changeValue_bumps_materialNoNotNull() {
        handler.handle(List.of(row(1, "0.97"), row(2, "0.94")), ctx());
        handler.handle(List.of(row(1, "0.97"), row(2, "0.85")), ctx());
        assertEquals("2001", version());
        assertEquals(4L, total());
        // material_no NOT NULL 必须落库（若 groupKey 漏 material_no 会在首次 INSERT 即炸）
        Number n = (Number) em.createNativeQuery(
            "SELECT count(*) FROM annual_discount WHERE material_no=:m AND material_no IS NOT NULL")
            .setParameter("m", CODE).getSingleResult();
        assertEquals(4L, n.longValue());
    }

    /** repair-0727 T5 单测：target_no 落 sharedCache 里 Phase 1 解析出的真编号（不是 Excel 原始值）。 */
    @Transactional
    @Test void sharedCacheResolvedResult_writesTargetNoAsRealCode() {
        handler.handle(List.of(row(1, "0.97")), ctx());
        List<?> r = em.createNativeQuery(
            "SELECT target_no FROM annual_discount WHERE material_no=:m AND is_current=true")
            .setParameter("m", CODE).getResultList();
        assertEquals(1, r.size());
        assertEquals(RESOLVED_NO, r.get(0), "target_no 落解析出的真编号，非 Excel 原始值 " + RAW_PROCESS);
    }

    /** repair-0804：钉住 discount_type 常量与「组装工序」列留空时 target_no 允许为 null。 */
    @Transactional
    @Test void writesAssemblyTypeAndAllowsNullProcess() {
        // 「组装工序」列留空 → target_no 为 null（允许），discount_type 恒 ASSEMBLY_PROCESS
        Map<String, String> m = new LinkedHashMap<>();
        m.put("销售料号", CODE);
        m.put("项次", "1");
        m.put("年降顺序", "1");
        m.put("年降系数（%）", "5.5");
        m.put("货币", "CNY");
        m.put("计价单位", "PCS");
        m.put("降价次数", "2");
        handler.handle(List.of(new SheetRow(1, m)), ctx());

        Object[] r = (Object[]) em.createNativeQuery(
            "SELECT discount_type, target_no, customer_no, seq_no, discount_times " +
            "FROM annual_discount WHERE material_no=:m AND is_current=true")
            .setParameter("m", CODE).getSingleResult();

        assertEquals("ASSEMBLY_PROCESS", r[0]);
        assertNull(r[1], "组装工序允许为空 → target_no 为 null");
        assertEquals("C1", r[2]);
        assertEquals(1, ((Number) r[3]).intValue(), "项次此前被丢弃，本次必须落库");
        assertEquals(2, ((Number) r[4]).intValue(), "降价次数此前被丢弃，本次必须落库");
    }
}
