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
 * 发号 handler（BatchState 注入 customerNo/yyMm 后能正常铸造报价料号）。
 * <p>update-0723 B3/B5 后更新：物料BOM/来料三表的「投入料号」类型改由
 * {@code PartTypeInferenceService} 推断（材质/零件/外购件三态），不再恒为材质；
 * 有码沿用原始码，只有名称时按推断类型补名称反查（材质查 material_recipe，零件/外购件走
 * {@code MaterialNoResolver} 按名查/发号）。
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

    @Transactional
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

    @Transactional
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

    @Transactional
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

    @Transactional
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

    // ===== 5. 物料BOM 材质料号为空+名称未在 material_recipe 命中 → 记错误跳过整行（U2） =====

    /**
     * update-0723 B3 更新：物料BOM 投入料号类型改由 {@code PartTypeInferenceService} 推断，不再
     * 恒为材质。本用例显式建 RECIPE 权威索引（该名称命中「物料与元素BOM」权威集）使该行定型为
     * 材质，验证 U2「材质缺库」路径：料号空+名称在 material_recipe 查无 → 报错「未找到材质」，
     * 不落 material_bom_item、不铸号（材质分支从不 resolve/不铸号，即使名称本身不可用于生成）。
     */
    @Transactional
    @Test
    void materialBomMerge_recipeTypeUnnamedInRecipe_recordsError_noLongerMints() {
        String cust = "QMNI-C5";
        String parentMat = "QMNI-PARENT1";
        String ghostRecipeName = "QMNI试制组件-GHOST";

        ImportContext ctx = ctx(cust);
        Map<String, String> elemRow = new LinkedHashMap<>();
        elemRow.put("销售料号", parentMat);
        elemRow.put("材质料号名称", ghostRecipeName);
        ctx.sharedCache.put("partTypeIndex", partTypeIndexFor(elemRow));

        Map<String, String> m = new LinkedHashMap<>();
        m.put("宏丰料号", parentMat);
        m.put("项次", "1");
        m.put("投入料号", "");
        m.put("投入料号名称", ghostRecipeName);
        m.put("产出料号类型", "2.非银点类");
        m.put("材料毛重", "1.0");
        m.put("重量单位", "KG");

        SheetImportResult result = materialBomMergeHandler.merge(List.of(new SheetRow(1, m)), ctx);

        assertEquals(1, result.totalRows);
        assertEquals(1, result.failedRows, "材质定型但 material_recipe 查无应记为失败行（不再按名铸号，U2）");
        assertEquals(0, result.successRows);
        assertEquals(1, result.errors.size());
        assertTrue(result.errors.get(0).message.contains("未找到材质"),
            "错误信息应含「未找到材质」，实际=" + result.errors.get(0).message);

        long bomItemCount = ((Number) em.createNativeQuery(
            "SELECT count(*) FROM material_bom_item WHERE material_no=:m")
            .setParameter("m", parentMat).getSingleResult()).longValue();
        assertEquals(0L, bomItemCount, "材质缺库行不应落 material_bom_item（不铸号、不入库）");
    }

    private com.cpq.basicdata.v6.service.PartTypeInferenceService.TypeIndex partTypeIndexFor(Map<String, String> elemRow) {
        return partTypeInferenceService.buildIndex(Map.of("物料与元素BOM", List.of(new SheetRow(1, elemRow))));
    }

    @Inject com.cpq.basicdata.v6.service.PartTypeInferenceService partTypeInferenceService;

    // ===== 6. Q07「投入料号」update-0723 B5（U10）：只有名称时按推断类型补名称反查 =====

    /**
     * update-0723 B5（U10）更新：Q06/Q07/Q09 的「投入料号」不再恒为材质原始码——有码沿用原始码
     * （行为不变）；只有名称时，先用 {@code PartTypeInferenceService} 推断类型，材质走
     * material_recipe 按名查码（查无报错），零件/外购件走 {@code MaterialNoResolver}（按名查/发号）。
     * 本用例覆盖「只有名称 + 未命中任一权威 sheet(默认兜底零件)」→ 应成功铸号入库（而非报错）。
     */
    @Transactional
    @Test
    void q07_nameOnly_defaultAssembly_mintsAndSucceeds() {
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

        SheetImportResult r = q07Handler.handle(List.of(new SheetRow(1, m)), ctx(cust));

        assertEquals(0, r.failedRows, "只有名称+默认兜底零件应成功铸号，不再报错");
        assertEquals(1, r.successRows);

        long upCount = ((Number) em.createNativeQuery(
            "SELECT count(*) FROM unit_price WHERE system_type='QUOTE' AND customer_no=:c " +
            "AND price_type='INCOMING_MATERIAL_OTHER'")
            .setParameter("c", cust).getSingleResult()).longValue();
        assertEquals(1L, upCount, "成功行应写入 unit_price");

        long masterCount = ((Number) em.createNativeQuery(
            "SELECT count(*) FROM material_master WHERE material_name=:n AND material_type='零件'")
            .setParameter("n", "QMNI试制来料").getSingleResult()).longValue();
        assertEquals(1L, masterCount, "铸号后应登记 material_master(material_type=零件)");
    }

    /** 料号与名称均为空：仍应报错（U10 未改变这一必填其一约束）。 */
    @Transactional
    @Test
    void q07_bothBlank_recordsError() {
        String cust = "QMNI-C6B";
        Map<String, String> m = new LinkedHashMap<>();
        m.put("要素名称", "QMNI测试要素2");
        m.put("投入料号", "");
        m.put("投入料号名称", "");
        m.put("宏丰料号", "QMNI-FIN-0002");
        m.put("项次", "1");
        m.put("值", "10");

        SheetImportResult r = q07Handler.handle(List.of(new SheetRow(1, m)), ctx(cust));
        assertEquals(1, r.failedRows, "料号与名称均为空应记为失败行");
        assertEquals(0, r.successRows);
    }

    // ===== 8. 跨客户报价料号经 dev handler(MaterialBomMergeHandler) 优雅降级：per-row 跳过、sheet 不回滚 =====

    @Transactional
    @Test
    void materialBomMerge_crossCustomerComponentNo_recordsErrorAndSkipsRowOnly() {
        String custA = "QMNI-C8A", custB = "QMNI-C8B";

        // 1) 客户 A 先铸造一个报价料号 R（登记归属 custA）。投入料号列不建 typeIndex → 默认零件
        //    ASSEMBLY 兜底（update-0723 B3：单表三态，无号有名走 resolve() 铸号）。
        String parentA = "QMNI-PARENT8A";
        Map<String, String> a1 = new LinkedHashMap<>();
        a1.put("宏丰料号", parentA);
        a1.put("项次", "1");
        a1.put("投入料号", "");
        a1.put("投入料号名称", "QMNI试制组件8A");
        a1.put("组成数量", "1");
        materialBomMergeHandler.merge(List.of(new SheetRow(1, a1)), ctx(custA));

        String r = (String) em.createNativeQuery(
            "SELECT component_no FROM material_bom_item WHERE material_no=:m AND is_current=TRUE")
            .setParameter("m", parentA).getSingleResult();
        assertTrue(r.matches("^\\d{4}-\\d{4}\\d{6}$"), "R 应为铸造的报价料号，实际=" + r);

        // 2) 客户 B 的一张 sheet，两行：第一行投入料号=R（跨客户串号）、第二行正常（无号有名，可 mint）。
        String parentB = "QMNI-PARENT8B";
        Map<String, String> b1 = new LinkedHashMap<>();
        b1.put("宏丰料号", parentB);
        b1.put("项次", "1");
        b1.put("投入料号", r);
        b1.put("组成数量", "1");
        Map<String, String> b2 = new LinkedHashMap<>();
        b2.put("宏丰料号", parentB);
        b2.put("项次", "2");
        b2.put("投入料号", "");
        b2.put("投入料号名称", "QMNI试制组件8B");
        b2.put("组成数量", "2");

        SheetImportResult result = materialBomMergeHandler.merge(
            List.of(new SheetRow(1, b1), new SheetRow(2, b2)), ctx(custB));

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

    @Transactional
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
