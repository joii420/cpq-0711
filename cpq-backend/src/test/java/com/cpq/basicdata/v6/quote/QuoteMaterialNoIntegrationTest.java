package com.cpq.basicdata.v6.quote;

import com.cpq.basicdata.v6.parser.ImportContext;
import com.cpq.basicdata.v6.parser.SheetImportResult;
import com.cpq.basicdata.v6.parser.SheetRow;
import com.cpq.basicdata.v6.pricing.P05CustomerMapHandler;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 报价料号统一 Spec 1 · Task 5 集成测试：Q02（报价客户料号登记改走 QUOTE）+ P05（核价维持 PRICING）+
 * 8 个发号 handler（BatchState 注入 customerNo/yyMm 后能正常铸造报价料号）。
 *
 * <p>测试客户/料号一律用 {@code QMNI-} 前缀，避免与其它测试/存量数据串号。
 */
@QuarkusTest
class QuoteMaterialNoIntegrationTest {

    @Inject Q02CustomerMapHandler q02Handler;
    @Inject MaterialBomMergeHandler materialBomMergeHandler;
    @Inject Q07IncomingOtherFeeHandler q07Handler;
    @Inject P05CustomerMapHandler p05Handler;
    @Inject EntityManager em;

    @Transactional
    void cleanup() {
        em.createNativeQuery(
            "DELETE FROM material_customer_map WHERE customer_no LIKE 'QMNI-%' OR material_no LIKE 'QMNI%'")
            .executeUpdate();
        em.createNativeQuery(
            "DELETE FROM material_bom_item WHERE customer_no LIKE 'QMNI-%' OR material_no LIKE 'QMNI%'")
            .executeUpdate();
        em.createNativeQuery(
            "DELETE FROM material_bom WHERE customer_no LIKE 'QMNI-%' OR material_no LIKE 'QMNI%'")
            .executeUpdate();
        em.createNativeQuery(
            "DELETE FROM unit_price WHERE customer_no LIKE 'QMNI-%' OR finished_material_no LIKE 'QMNI%' OR code LIKE 'QMNI%'")
            .executeUpdate();
        em.createNativeQuery("DELETE FROM material_master WHERE material_no LIKE 'QMNI%'")
            .executeUpdate();
        // 铸号场景（名称匹配料号表）用的测试名称也要按前缀清，否则上一轮铸造的非 QMNI 前缀报价料号
        // 残留在 material_master，下一轮 resolve() 按名匹配到旧号、不再重新 mintAndRegister，
        // 而 material_customer_map 那行已被上面按 customer_no 前缀清掉，造成登记查询落空。
        em.createNativeQuery("DELETE FROM material_master WHERE material_name LIKE 'QMNI%'")
            .executeUpdate();
        em.createNativeQuery("DELETE FROM quote_customer_code WHERE customer_no LIKE 'QMNI-%'")
            .executeUpdate();
    }

    @BeforeEach void before() { cleanup(); }
    @AfterEach  void after()  { cleanup(); }

    private ImportContext ctx(String customerNo) {
        ImportContext c = new ImportContext();
        c.customerNo = customerNo;
        c.systemType = "QUOTE";
        c.importedBy = null;
        return c;
    }

    private long countMcm(String materialNo) {
        return ((Number) em.createNativeQuery(
            "SELECT count(*) FROM material_customer_map WHERE material_no=:m")
            .setParameter("m", materialNo).getSingleResult()).longValue();
    }

    @Transactional
    void insertRawMcm(String systemType, String materialNo, String customerNo, String customerProductNo) {
        em.createNativeQuery(
            "INSERT INTO material_customer_map (system_type, material_no, customer_no, customer_product_no, created_at, updated_at) " +
            "VALUES (:st, :m, :c, :cp, NOW(), NOW())")
            .setParameter("st", systemType)
            .setParameter("m", materialNo)
            .setParameter("c", customerNo)
            .setParameter("cp", customerProductNo)
            .executeUpdate();
    }

    // ===== 1. Q02 写 QUOTE =====

    @Test
    void q02_writesQuoteSystemType() {
        String cust = "QMNI-C1";
        String reportNo = "QMNI-REPORT-0001";
        Map<String, String> m = new LinkedHashMap<>();
        m.put("报价料号", reportNo);
        m.put("客户产品编号", "CPN-1");
        q02Handler.handle(List.of(new SheetRow(1, m)), ctx(cust));

        Object[] r = (Object[]) em.createNativeQuery(
            "SELECT system_type, material_no, customer_product_no FROM material_customer_map WHERE material_no=:m")
            .setParameter("m", reportNo).getSingleResult();
        assertEquals("QUOTE", r[0], "Q02 登记行必须是 QUOTE");
        assertEquals(reportNo, r[1]);
        assertEquals("CPN-1", r[2]);
    }

    // ===== 2. Q02 relabel（报价料号 优先 / 宏丰料号 回退） =====

    @Test
    void q02_relabel_readsQuoteHeaderOrFallsBackToHongfengHeader() {
        String cust = "QMNI-C2";
        Map<String, String> m1 = new LinkedHashMap<>();
        m1.put("报价料号", "QMNI-RPT-0002");
        m1.put("客户产品编号", "CPN-2A");
        Map<String, String> m2 = new LinkedHashMap<>();
        m2.put("宏丰料号", "QMNI-RPT-0003");
        m2.put("客户产品编号", "CPN-2B");

        q02Handler.handle(List.of(new SheetRow(1, m1), new SheetRow(2, m2)), ctx(cust));

        assertEquals(1L, countMcm("QMNI-RPT-0002"), "「报价料号」表头应能读到");
        assertEquals(1L, countMcm("QMNI-RPT-0003"), "「宏丰料号」表头应回退读到");
    }

    // ===== 3. Q02 replace 收窄：只删 QUOTE+客户料号映射行 =====

    @Test
    void q02_replace_onlyNarrowsQuoteCustomerProductRows() {
        String cust = "QMNI-C3";
        insertRawMcm("QUOTE", "QMNI-OLD-CPN", cust, "OLD-CPN");     // 应被替换删除
        insertRawMcm("QUOTE", "QMNI-COMPONENT", cust, null);        // 组件登记行，应存活
        insertRawMcm("PRICING", "QMNI-PRICING", cust, "PRC-CPN");   // PRICING 行，应存活

        Map<String, String> m = new LinkedHashMap<>();
        m.put("报价料号", "QMNI-NEW-CPN");
        m.put("客户产品编号", "NEW-CPN");
        q02Handler.handle(List.of(new SheetRow(1, m)), ctx(cust));

        assertEquals(0L, countMcm("QMNI-OLD-CPN"), "旧 QUOTE 客户料号映射行应被替换删除");
        assertEquals(1L, countMcm("QMNI-COMPONENT"), "组件登记行(customer_product_no=NULL)不应被删除");
        assertEquals(1L, countMcm("QMNI-PRICING"), "PRICING 行不应被删除");
        assertEquals(1L, countMcm("QMNI-NEW-CPN"), "新导入行应存在");
    }

    // ===== 4. Q02 跨客户串号 =====

    @Test
    void q02_crossCustomerCollision_recordsErrorAndDoesNotOverwrite() {
        String custA = "QMNI-C4A", custB = "QMNI-C4B";
        String reportNo = "QMNI-XCUST-0001";

        Map<String, String> m1 = new LinkedHashMap<>();
        m1.put("报价料号", reportNo);
        m1.put("客户产品编号", "CPN-A");
        SheetImportResult r1 = q02Handler.handle(List.of(new SheetRow(1, m1)), ctx(custA));
        assertEquals(0, r1.failedRows, "C1 导入本身不应失败");

        Map<String, String> m2 = new LinkedHashMap<>();
        m2.put("报价料号", reportNo);
        m2.put("客户产品编号", "CPN-B");
        SheetImportResult r2 = q02Handler.handle(List.of(new SheetRow(1, m2)), ctx(custB));

        assertEquals(1, r2.failedRows, "C2 用 C1 已登记的报价料号应记为失败行");
        assertTrue(r2.errors.get(0).message.contains("跨客户"), "错误信息应含跨客户");

        Object[] row = (Object[]) em.createNativeQuery(
            "SELECT customer_no, customer_product_no FROM material_customer_map WHERE material_no=:m")
            .setParameter("m", reportNo).getSingleResult();
        assertEquals(custA, row[0], "跨客户导入不应覆盖归属客户");
        assertEquals("CPN-A", row[1], "跨客户导入不应覆盖 customer_product_no");
    }

    // ===== 5. BOM 无号组件发报价料号 =====

    @Test
    void materialBomMerge_unnumberedComponent_mintsQuoteMaterialNo() {
        String cust = "QMNI-C5";
        String parentMat = "QMNI-PARENT1";
        Map<String, String> m = new LinkedHashMap<>();
        m.put("宏丰料号", parentMat);
        m.put("项次", "1");
        m.put("投入料号", "");
        m.put("投入料号名称", "QMNI试制组件");
        m.put("产出料号类型", "2.非银点类");
        m.put("材料毛重", "1.0");
        m.put("重量单位", "KG");

        materialBomMergeHandler.merge(List.of(new SheetRow(1, m)), List.of(), ctx(cust));

        String componentNo = (String) em.createNativeQuery(
            "SELECT component_no FROM material_bom_item WHERE material_no=:m AND is_current=TRUE")
            .setParameter("m", parentMat).getSingleResult();
        assertTrue(componentNo.matches("^\\d{4}-\\d{4}\\d{6}$"),
            "无号组件应被铸造为报价料号格式，实际=" + componentNo);
        assertFalse(componentNo.startsWith("9"), "不应是旧 9 字头生成路径");

        Object[] reg = (Object[]) em.createNativeQuery(
            "SELECT system_type, customer_no FROM material_customer_map WHERE material_no=:m")
            .setParameter("m", componentNo).getSingleResult();
        assertEquals("QUOTE", reg[0], "铸造的报价料号应已登记 QUOTE 行");
        assertEquals(cust, reg[1]);
    }

    // ===== 6. Q07 费用 handler 同样发报价料号 =====

    @Test
    void q07_unnumberedIncomingMaterial_mintsQuoteMaterialNo() {
        String cust = "QMNI-C6";
        Map<String, String> m = new LinkedHashMap<>();
        m.put("要素名称", "QMNI测试要素");
        m.put("投入料号", "");
        m.put("投入料号名称", "QMNI试制来料");
        m.put("宏丰料号", "QMNI-FIN-0001");
        m.put("项次", "1");
        m.put("值", "10");
        m.put("货币", "RMB");
        m.put("计价单位", "PCS");

        q07Handler.handle(List.of(new SheetRow(1, m)), ctx(cust));

        String code = (String) em.createNativeQuery(
            "SELECT code FROM unit_price WHERE system_type='QUOTE' AND customer_no=:c " +
            "AND price_type='INCOMING_MATERIAL_OTHER' AND is_current=TRUE")
            .setParameter("c", cust).getSingleResult();
        assertTrue(code.matches("^\\d{4}-\\d{4}\\d{6}$"),
            "无号来料应被铸造为报价料号格式，实际=" + code);

        String regSystemType = (String) em.createNativeQuery(
            "SELECT system_type FROM material_customer_map WHERE material_no=:m")
            .setParameter("m", code).getSingleResult();
        assertEquals("QUOTE", regSystemType);
    }

    // ===== 8. 跨客户报价料号经 dev handler(MaterialBomMergeHandler) 优雅降级：per-row 跳过、sheet 不回滚 =====

    @Test
    void materialBomMerge_crossCustomerComponentNo_recordsErrorAndSkipsRowOnly() {
        String custA = "QMNI-C8A", custB = "QMNI-C8B";

        // 1) 客户 A 先铸造一个报价料号 R（登记归属 custA）。
        String parentA = "QMNI-PARENT8A";
        Map<String, String> a1 = new LinkedHashMap<>();
        a1.put("宏丰料号", parentA);
        a1.put("项次（一级）", "1");
        a1.put("组成件料号", "");
        a1.put("组成件名称", "QMNI试制组件8A");
        a1.put("组成数量", "1");
        a1.put("组成单位", "PCS");
        materialBomMergeHandler.merge(List.of(), List.of(new SheetRow(1, a1)), ctx(custA));

        String r = (String) em.createNativeQuery(
            "SELECT component_no FROM material_bom_item WHERE material_no=:m AND is_current=TRUE")
            .setParameter("m", parentA).getSingleResult();
        assertTrue(r.matches("^\\d{4}-\\d{4}\\d{6}$"), "R 应为铸造的报价料号，实际=" + r);

        // 2) 客户 B 的一张 sheet，两行：第一行组成件料号=R（跨客户串号）、第二行正常（无号有名，可 mint）。
        String parentB = "QMNI-PARENT8B";
        Map<String, String> b1 = new LinkedHashMap<>();
        b1.put("宏丰料号", parentB);
        b1.put("项次（一级）", "1");
        b1.put("组成件料号", r);
        b1.put("组成件名称", "");
        b1.put("组成数量", "1");
        b1.put("组成单位", "PCS");
        Map<String, String> b2 = new LinkedHashMap<>();
        b2.put("宏丰料号", parentB);
        b2.put("项次（一级）", "2");
        b2.put("组成件料号", "");
        b2.put("组成件名称", "QMNI试制组件8B");
        b2.put("组成数量", "2");
        b2.put("组成单位", "PCS");

        SheetImportResult result = materialBomMergeHandler.merge(
            List.of(), List.of(new SheetRow(1, b1), new SheetRow(2, b2)), ctx(custB));

        // sheet 不整体回滚：两行都被计入 totalRows，只有第 1 行失败，第 2 行成功。
        assertEquals(2, result.totalRows);
        assertEquals(1, result.failedRows, "应恰好一行失败(跨客户)，而非整表失败");
        assertEquals(1, result.successRows, "另一行应正常成功入库");
        assertEquals(1, result.errors.size());
        assertEquals(1, result.errors.get(0).rowNo);
        assertTrue(result.errors.get(0).message.contains("跨客户"),
            "错误信息应含跨客户，实际=" + result.errors.get(0).message);

        // 第 2 行照常入库：material_bom_item 只有 1 条当前行，且不是 R（跨客户那行被跳过，不能污染 custB 的 BOM）。
        String componentNoB = (String) em.createNativeQuery(
            "SELECT component_no FROM material_bom_item WHERE material_no=:m AND is_current=TRUE")
            .setParameter("m", parentB).getSingleResult();
        assertNotEquals(r, componentNoB, "跨客户被拒的那行不应写入 custB 的 BOM");
        assertTrue(componentNoB.matches("^\\d{4}-\\d{4}\\d{6}$"),
            "第 2 行应正常铸造报价料号，实际=" + componentNoB);

        // R 的归属客户不应被 custB 的导入动作改变。
        String ownerR = (String) em.createNativeQuery(
            "SELECT customer_no FROM material_customer_map WHERE material_no=:m")
            .setParameter("m", r).getSingleResult();
        assertEquals(custA, ownerR, "R 的归属客户不应被跨客户导入改写");
    }

    // ===== 7. P05 写 PRICING =====

    @Test
    void p05_writesPricingSystemType() {
        String cust = "QMNI-P05C1";
        String materialNo = "QMNI-P05-MAT1";
        Map<String, String> m = new LinkedHashMap<>();
        m.put("宏丰料号", materialNo);
        m.put("客户编号", cust);
        m.put("客户产品编号", "P05-CPN-1");
        m.put("客户名称", "QMNI测试客户");
        m.put("项次", "1");

        p05Handler.handle(List.of(new SheetRow(1, m)), ctx(cust));

        String systemType = (String) em.createNativeQuery(
            "SELECT system_type FROM material_customer_map WHERE material_no=:m AND customer_no=:c")
            .setParameter("m", materialNo).setParameter("c", cust).getSingleResult();
        assertEquals("PRICING", systemType, "P05 登记行必须是 PRICING");
    }
}
