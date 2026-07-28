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
 * Task 3 集成测试：Q15 组装加工费年降（#6 code 进 gk, 行集维度=discount_order, seq_no 丢列）→ unit_price 版本化。
 *
 * <p>repair-0727：handler 改为从 {@code ctx.sharedCache["assemblyProcessNo"]} 取 Phase 1
 * 已解析结果落 {@code operation_no}——本类单测直调 handler，须显式预置 sharedCache。
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
        em.createNativeQuery("DELETE FROM unit_price WHERE code=:c").setParameter("c", CODE).executeUpdate();
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
            "SELECT version_no FROM unit_price WHERE code=:c AND is_current=true LIMIT 1")
            .setParameter("c", CODE).getResultList();
        return r.isEmpty() ? null : String.valueOf(r.get(0));
    }
    private long total() {
        return ((Number) em.createNativeQuery("SELECT count(*) FROM unit_price WHERE code=:c")
            .setParameter("c", CODE).getSingleResult()).longValue();
    }

    @Transactional
    @Test void importTwice_idempotent() {
        handler.handle(List.of(row(1, "0.97"), row(2, "0.94")), ctx());
        handler.handle(List.of(row(1, "0.97"), row(2, "0.94")), ctx());
        assertEquals("2000", version());
        assertEquals(2L, total());
    }
    @Transactional
    @Test void changeValue_bumps_codeNotNull() {
        handler.handle(List.of(row(1, "0.97"), row(2, "0.94")), ctx());
        handler.handle(List.of(row(1, "0.97"), row(2, "0.85")), ctx());
        assertEquals("2001", version());
        assertEquals(4L, total());
        // #6：code NOT NULL 必须落库（若 groupKey 漏 code 会在首次 INSERT 即炸）
        Number n = (Number) em.createNativeQuery(
            "SELECT count(*) FROM unit_price WHERE code=:c AND code IS NOT NULL")
            .setParameter("c", CODE).getSingleResult();
        assertEquals(4L, n.longValue());
    }

    /** repair-0727 T5 单测：operation_no 落 sharedCache 里 Phase 1 解析出的真编号（不是 Excel 原始值）。 */
    @Transactional
    @Test void sharedCacheResolvedResult_writesOperationNoAsRealCode() {
        handler.handle(List.of(row(1, "0.97")), ctx());
        List<?> r = em.createNativeQuery(
            "SELECT operation_no FROM unit_price WHERE code=:c AND is_current=true")
            .setParameter("c", CODE).getResultList();
        assertEquals(1, r.size());
        assertEquals(RESOLVED_NO, r.get(0), "operation_no 落解析出的真编号，非 Excel 原始值 " + RAW_PROCESS);
    }
}
