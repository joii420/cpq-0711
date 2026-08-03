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

/**
 * Task 3 集成测试：Q17 电镀费用（一行拆加工费/材料费两组 + 忽略 Excel 版本号 + 跳过电镀方案行）。
 *
 * <p>repair-0802 spec 评审补漏（分支覆盖矩阵）：投入料号解析三分支中，分支 1（有码）与分支 3（皆空回退）
 * 由既有用例覆盖；分支 2（仅名称）新增 {@link #inputNameOnly_mintsNewCode_viaResolverFallback_writesMaterialMaster()}
 * 覆盖其中最容易稳定复现的子路径——单测直调 handler 时 {@code ctx.sharedCache} 无 {@code "partTypeIndex"}，
 * 兜底走 {@code InferResult(ASSEMBLY, DEFAULT)} → {@code MaterialNoResolver.resolve} 铸号路径。
 * RECIPE 子分支（材质反查 / 未找到材质报错）需要构造非 null 的 {@code TypeIndex}，构造成本高且会侵入
 * 产品代码可见性，本测试文件不覆盖——该子分支由 Task 2（QuoteImportValidator Phase 1 预校验）的测试覆盖。
 */
@QuarkusTest
class Q17PlatingCostHandlerTest {

    @Inject Q17PlatingCostHandler handler;
    @Inject EntityManager em;

    static final String SALES_NO = "TEST-Q17-SALES";     // 销售料号 → finished_material_no
    static final String INPUT_NO = "TEST-Q17-INPUT";     // 投入料号 → code
    static final String NAME_ONLY = "TEST-Q17-NAMEONLY"; // 分支2: 仅投入料号名称 → 铸新码
    static final UUID UID = UUID.fromString("00000000-0000-0000-0000-000000000017");

    @Transactional void cleanup() {
        em.createNativeQuery("DELETE FROM unit_price WHERE code IN (:a,:b) OR finished_material_no=:a")
            .setParameter("a", SALES_NO).setParameter("b", INPUT_NO).executeUpdate();
        cleanupNameOnlyMint();
    }
    @BeforeEach void before() { cleanup(); }
    @AfterEach  void after()  { cleanup(); }

    /**
     * 分支2(仅名称)铸号路径专用清理：铸出的料号是运行时生成、不可预知的，按 {@link #NAME_ONLY}
     * 名称反查回其当次铸出的码，再级联删 unit_price / material_master / material_customer_map(QUOTE) 三张表。
     * 不动 quote_customer_code / quote_material_no_seq —— 这两张是客户号="C1"下跨测试类共享的发号基础设施
     * （Q06/Q07 等其它 handler 测试也用同一 customerNo），删掉会影响并发测试类，只是消耗一点序号无副作用。
     */
    private void cleanupNameOnlyMint() {
        List<?> minted = em.createNativeQuery("SELECT material_no FROM material_master WHERE material_name=:n")
            .setParameter("n", NAME_ONLY).getResultList();
        for (Object o : minted) {
            String code = (String) o;
            em.createNativeQuery("DELETE FROM unit_price WHERE code=:c").setParameter("c", code).executeUpdate();
            em.createNativeQuery("DELETE FROM material_master WHERE material_no=:c").setParameter("c", code).executeUpdate();
            em.createNativeQuery("DELETE FROM material_customer_map WHERE material_no=:c AND system_type='QUOTE'")
                .setParameter("c", code).executeUpdate();
        }
    }

    private ImportContext ctx() {
        ImportContext c = new ImportContext();
        c.customerNo = "C1"; c.systemType = "QUOTE"; c.importedBy = UID; return c;
    }

    /** inputNo/inputName 传 null 表示该列不存在（模拟老模板/空单元格）。 */
    private SheetRow row(String inputNo, String inputName, String process, String material,
                         String excelVersion, String platingSchemeNo) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("宏丰料号", SALES_NO);
        if (inputNo != null) m.put("投入料号", inputNo);
        if (inputName != null) m.put("投入料号名称", inputName);
        if (platingSchemeNo != null) m.put("电镀方案编号", platingSchemeNo);
        if (excelVersion != null) m.put("版本编号", excelVersion);
        m.put("电镀加工费", process); m.put("电镀材料费", material);
        m.put("货币", "CNY"); m.put("计价单位", "PCS"); m.put("不良率", "0.01");
        return new SheetRow(1, m);
    }

    private String version(String code, String costType) {
        List<?> r = em.createNativeQuery(
            "SELECT version_no FROM unit_price WHERE code=:c AND cost_type=:ct AND is_current=true LIMIT 1")
            .setParameter("c", code).setParameter("ct", costType).getResultList();
        return r.isEmpty() ? null : String.valueOf(r.get(0));
    }
    private long total() {
        return ((Number) em.createNativeQuery(
            "SELECT count(*) FROM unit_price WHERE code IN (:a,:b)")
            .setParameter("a", SALES_NO).setParameter("b", INPUT_NO).getSingleResult()).longValue();
    }
    private String fmnOf(String code) {
        List<?> r = em.createNativeQuery(
            "SELECT finished_material_no FROM unit_price WHERE code=:c AND is_current=true LIMIT 1")
            .setParameter("c", code).getResultList();
        return r.isEmpty() || r.get(0) == null ? null : String.valueOf(r.get(0));
    }

    @Transactional
    @Test void importTwice_idempotent_twoCostTypes_ignoreExcelVersion() {
        handler.handle(List.of(row(null, null, "5", "3", "V99", null)), ctx());
        handler.handle(List.of(row(null, null, "5", "3", "V99", null)), ctx());
        assertEquals("2000", version(SALES_NO, "电镀加工费"), "version 系统生成, 忽略 Excel 'V99'");
        assertEquals("2000", version(SALES_NO, "电镀材料费"));
        assertEquals(2L, total(), "一行拆两条, 导两遍不翻倍");
    }

    @Transactional
    @Test void changeOneFee_bumpsOnlyThatCostType() {
        handler.handle(List.of(row(null, null, "5", "3", null, null)), ctx());
        handler.handle(List.of(row(null, null, "9", "3", null, null)), ctx());
        assertEquals("2001", version(SALES_NO, "电镀加工费"), "加工费升版");
        assertEquals("2000", version(SALES_NO, "电镀材料费"), "材料费不变");
        assertEquals(3L, total(), "加工费 2 行(2000下线+2001生效) + 材料费 1 行");
    }

    @Transactional
    @Test void platingSchemeNo_skipsRow() {
        handler.handle(List.of(row(null, null, "5", "3", null, "SCHEME-1")), ctx());
        assertEquals(0L, total(), "有电镀方案编号 → 整行跳过, 不写 unit_price");
    }

    @Transactional
    @Test void inputPartNo_landsInCode_salesNoLandsInFinishedMaterialNo() {
        handler.handle(List.of(row(INPUT_NO, "投入零件X", "5", "3", null, null)), ctx());
        assertEquals("2000", version(INPUT_NO, "电镀加工费"), "code = 投入料号");
        assertEquals(SALES_NO, fmnOf(INPUT_NO), "finished_material_no = 销售料号");
        assertNull(version(SALES_NO, "电镀加工费"), "销售料号不再写进 code");
        assertEquals(2L, total(), "一行拆两条");
    }

    @Transactional
    @Test void bothColumnsBlank_fallsBackToSalesNo_noError() {
        var result = handler.handle(List.of(row(null, null, "5", "3", null, null)), ctx());
        assertEquals(0, result.failedRows, "两列皆空是合法输入(非必填)，不得报错");
        assertEquals(1, result.successRows);
        assertEquals("2000", version(SALES_NO, "电镀加工费"), "code 回退为销售料号");
        assertEquals(SALES_NO, fmnOf(SALES_NO), "finished_material_no 仍写销售料号");
    }

    @Transactional
    @Test void sameSalesNo_twoInputParts_coexistWithoutOverwrite() {
        Map<String, String> m2 = new LinkedHashMap<>();
        m2.put("宏丰料号", SALES_NO); m2.put("投入料号", INPUT_NO + "-B");
        m2.put("电镀加工费", "7"); m2.put("电镀材料费", "4");
        m2.put("货币", "CNY"); m2.put("计价单位", "PCS"); m2.put("不良率", "0.01");
        handler.handle(List.of(row(INPUT_NO, null, "5", "3", null, null), new SheetRow(2, m2)), ctx());

        long n = ((Number) em.createNativeQuery(
            "SELECT count(*) FROM unit_price WHERE finished_material_no=:f AND is_current=true")
            .setParameter("f", SALES_NO).getSingleResult()).longValue();
        assertEquals(4L, n, "两个投入料号 × 两个 cost_type = 4 行，互不覆盖");
        em.createNativeQuery("DELETE FROM unit_price WHERE code=:c")
            .setParameter("c", INPUT_NO + "-B").executeUpdate();
    }

    /**
     * 分支2(仅名称)：单测直调 handler 无 partTypeIndex → 兜底 InferResult(ASSEMBLY, DEFAULT)
     * → 走 MaterialNoResolver.resolve 铸号路径（非 RECIPE 反查）。验证：
     * ①铸出的码既非销售料号也非名称本身（真的是新码）；②该码写入 material_master 且 material_type='零件'
     * （非 OUTSOURCED 兜底）；③unit_price.code = 铸出码、finished_material_no = 销售料号。
     */
    @Transactional
    @Test void inputNameOnly_mintsNewCode_viaResolverFallback_writesMaterialMaster() {
        var result = handler.handle(List.of(row(null, NAME_ONLY, "5", "3", null, null)), ctx());
        assertEquals(0, result.failedRows, "仅名称是合法输入，应铸号成功而非报错");
        assertEquals(1, result.successRows);

        List<?> mintedRows = em.createNativeQuery(
            "SELECT material_no FROM material_master WHERE material_name=:n")
            .setParameter("n", NAME_ONLY).getResultList();
        assertEquals(1, mintedRows.size(), "仅名称应铸出且仅铸出一个新码");
        String minted = (String) mintedRows.get(0);
        assertNotEquals(SALES_NO, minted, "铸出的码不应是销售料号");
        assertNotEquals(NAME_ONLY, minted, "铸出的码不应是名称字面量本身");
        assertTrue(minted.matches("^\\d{4}-\\d{4}\\d{6}$"), "应为报价料号格式: " + minted);

        String materialType = (String) em.createNativeQuery(
            "SELECT material_type FROM material_master WHERE material_no=:c")
            .setParameter("c", minted).getSingleResult();
        assertEquals("零件", materialType, "非 OUTSOURCED/RECIPE 兜底应落零件");

        assertEquals("2000", version(minted, "电镀加工费"), "code = 铸出的新料号");
        assertEquals(SALES_NO, fmnOf(minted), "finished_material_no = 销售料号");
    }
}
