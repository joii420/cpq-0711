package com.cpq.basicdata.v6.pricing;

import com.cpq.basicdata.v6.dto.ImportResultDTO;
import com.cpq.basicdata.v6.dto.SheetResultDTO;
import com.cpq.basicdata.v6.parser.SheetHandler;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * task-0812 · 核价导入停用四个 Sheet（P01/P02/P04/P05）—— 测试工程师执行用例专用 JUnit 类。
 *
 * <p>对应 {@code dev-docs/task-0812-核价导入停用四个Sheet/test.md} 的 IMP-01/02/03/04/05a/05b/06/07/08、
 * VER-01/02/03、CODE-02 系列用例。设计原则（详见 test.md §1.1）：
 * <ul>
 *   <li>直接注入全部 24 个 handler bean（不经 {@link PricingHandlerCatalog}），使本文件在
 *       停用前（commit f551f37e）与停用后（本分支 HEAD）<b>无需修改即可编译运行</b>，
 *       用于 REG-01 的 A/B 对照（两侧各跑一次本文件，比对输出）。</li>
 *   <li>20 个"保留" Sheet 只写表头、0 数据行（低风险，不触发各 handler 的业务/FK 校验），
 *       其中唯一例外是 P03 汇率管理表（三列纯文本、无 FK 依赖）塞 1 行真实数据，
 *       作为验证"保留 Sheet 正常处理路径未受影响"的最小代表。</li>
 *   <li>4 个"停用" Sheet（P01/P02/P04/P05）各塞 1 行真实数据，验证停用后被完全忽略。</li>
 * </ul>
 */
@QuarkusTest
class Task0812DisabledSheetsTest {

    @Inject PricingImportService importService;
    @Inject EntityManager em;
    @Inject com.cpq.basicdata.v6.repository.MaterialCustomerMapRepository mapRepo;

    @Inject P01ElementPricingPriceHandler p01;
    @Inject P02MaterialPricingPriceHandler p02;
    @Inject P03ExchangeRateHandler p03;
    @Inject P04PricingVersionHandler p04;
    @Inject P05CustomerMapHandler p05;
    @Inject P06MaterialBomHandler p06;
    @Inject P07ElementBomHandler p07;
    @Inject P08CapacityHandler p08;
    @Inject P09EquipmentDepreciationHandler p09;
    @Inject P10ProductionEnergyHandler p10;
    @Inject P11AuxiliaryEnergyHandler p11;
    @Inject P12ToolingCostHandler p12;
    @Inject P13ProductionConsumableHandler p13;
    @Inject P14PackagingConsumableHandler p14;
    @Inject P15IncomingProcessFeeHandler p15;
    @Inject P16IncomingOtherRatioFeeHandler p16;
    @Inject P17IncomingOtherFixedFeeHandler p17;
    @Inject P18SelfProcessAssemblyFeeHandler p18;
    @Inject P19FinishedOtherRatioFeeHandler p19;
    @Inject P20FinishedOtherFixedFeeHandler p20;
    @Inject P21PlatingSchemeHandler p21;
    @Inject P22PlatingCostHandler p22;
    @Inject P23OutsourceProcessFeeHandler p23;
    @Inject P24UnitWeightHandler p24;

    // ------- 哨兵测试数据（专属前缀，不与库内真实数据冲突） -------
    static final String EL_CODE = "T0812-P01-EL";
    static final String MT_CODE = "T0812-P02-MT";
    static final String P04_SKU = "T0812-P04-SKU";
    static final String P05_SKU_NEW = "T0812-P05-SKU-ONLY";
    static final String P05_CUST_NEW = "T0812-CUST";
    static final String FX_BASE = "T0812BASE";
    static final String FX_TGT = "T0812TGT";
    // IMP-05b 存量行覆盖测试用哨兵（先 seed 再"覆盖"）
    static final String P05_SKU_EXIST = "T0812-P05-EXIST-SKU";
    static final String P05_CUST_EXIST = "T0812-EXIST-CUST";

    private List<SheetHandler> all24() {
        return List.of(p01, p02, p03, p04, p05, p06, p07, p08, p09, p10, p11, p12,
                        p13, p14, p15, p16, p17, p18, p19, p20, p21, p22, p23, p24);
    }

    @Transactional
    UUID anyUserId() {
        return (UUID) em.createNativeQuery("SELECT id FROM \"user\" LIMIT 1").getSingleResult();
    }

    @Transactional
    void cleanup() {
        em.createNativeQuery("DELETE FROM unit_price WHERE code IN (:c1,:c2)")
            .setParameter("c1", EL_CODE).setParameter("c2", MT_CODE).executeUpdate();
        em.createNativeQuery("DELETE FROM material_version_mgmt WHERE material_no = :m")
            .setParameter("m", P04_SKU).executeUpdate();
        em.createNativeQuery("DELETE FROM material_customer_map WHERE material_no IN (:m1,:m2) AND customer_no IN (:c1,:c2)")
            .setParameter("m1", P05_SKU_NEW).setParameter("m2", P05_SKU_EXIST)
            .setParameter("c1", P05_CUST_NEW).setParameter("c2", P05_CUST_EXIST).executeUpdate();
        em.createNativeQuery("DELETE FROM material_master WHERE material_no IN (:m1,:m2)")
            .setParameter("m1", P05_SKU_NEW).setParameter("m2", P05_SKU_EXIST).executeUpdate();
        em.createNativeQuery("DELETE FROM exchange_rate_v6 WHERE base_currency = :b AND target_currency = :t")
            .setParameter("b", FX_BASE).setParameter("t", FX_TGT).executeUpdate();
        em.createNativeQuery("DELETE FROM import_record WHERE original_file_name LIKE 'task0812-fixture%'").executeUpdate();
    }

    @BeforeEach void before() { cleanup(); }
    @AfterEach  void after()  { cleanup(); }

    // ------------------------------------------------------------------
    // 夹具构造（§1.1）
    // ------------------------------------------------------------------

    /**
     * @param p03Rate  非 null 时给 P03 塞 1 行（基础货币/核价货币固定为 FX_BASE/FX_TGT，核价汇率=该值）；
     *                 null 时 P03 也是 0 数据行（IMP-07/IMP-08 边界用）
     * @param disabledDataOk true=4 个停用 Sheet 塞合法数据；false=塞非法数据（元素代码/客户编号留空，IMP-08 用）
     */
    private byte[] buildFixture(String p03Rate, boolean disabledDataOk) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            for (SheetHandler h : all24()) {
                Sheet sheet = wb.createSheet(h.sheetName());
                List<String> headers = h.templateHeaders();
                Row headerRow = sheet.createRow(0);
                for (int i = 0; i < headers.size(); i++) headerRow.createCell(i).setCellValue(headers.get(i));

                Map<String, String> dataRow = null;
                if (h == p01) {
                    dataRow = new LinkedHashMap<>();
                    dataRow.put("元素代码", disabledDataOk ? EL_CODE : "");
                    dataRow.put("核价单价", "1.234");
                    dataRow.put("市场参考价", "9.9");
                    dataRow.put("参考价来源网址", "https://example.com");
                    dataRow.put("网站名称", "示例网站");
                    dataRow.put("参考价取用规则", "取最新值");
                    dataRow.put("币种", "CNY");
                    dataRow.put("计量单位", "KG");
                    dataRow.put("回收折扣（%）", "0.85");
                    dataRow.put("元素价格版本", "V1");
                } else if (h == p02) {
                    dataRow = new LinkedHashMap<>();
                    dataRow.put("材料料号", MT_CODE);
                    dataRow.put("核价单价", "3.456");
                    dataRow.put("市场参考价", "8.8");
                    dataRow.put("参考价来源网址", "https://example.com/m");
                    dataRow.put("网站名称", "示例材料网站");
                    dataRow.put("参考价取用规则", "取平均值");
                    dataRow.put("币种", "CNY");
                    dataRow.put("计量单位", "PCS");
                    dataRow.put("回收折扣（%）", "0.75");
                    dataRow.put("材料价格版本", "V1");
                } else if (h == p04) {
                    dataRow = new LinkedHashMap<>();
                    dataRow.put("销售料号", P04_SKU);
                    dataRow.put("项次", "1");
                    dataRow.put("核价版本编号", "V1");
                } else if (h == p05) {
                    dataRow = new LinkedHashMap<>();
                    dataRow.put("销售料号", P05_SKU_NEW);
                    dataRow.put("客户编号", disabledDataOk ? P05_CUST_NEW : "");
                    dataRow.put("客户产品编号", "CP-0812");
                } else if (h == p03 && p03Rate != null) {
                    dataRow = new LinkedHashMap<>();
                    dataRow.put("基础货币", FX_BASE);
                    dataRow.put("核价货币", FX_TGT);
                    dataRow.put("核价汇率", p03Rate);
                }

                if (dataRow != null) {
                    Row r1 = sheet.createRow(1);
                    for (int i = 0; i < headers.size(); i++) {
                        String v = dataRow.get(headers.get(i));
                        if (v != null) r1.createCell(i).setCellValue(v);
                    }
                }
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            wb.write(bos);
            return bos.toByteArray();
        }
    }

    static final String P05_EXIST_PRODUCT_NO = "CP-EXISTING-ORIGINAL"; // 冲突键一部分，seed 与夹具行必须一致

    /** IMP-05b 专用：在 P05 Sheet 追加第二行，销售料号/客户编号/客户产品编号复用已存在行的完整冲突键
     *  (system_type='PRICING', material_no, customer_no, customer_product_no)，仅客户名称填新值，
     *  验证"如果 P05 被处理会触发 upsert 覆盖"的这一行，在停用后确实完全不被碰。 */
    private byte[] buildFixtureWithExistingRowOverlap() throws Exception {
        byte[] base = buildFixture("6.5000", true);
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(base))) {
            Sheet p05Sheet = wb.getSheet(p05.sheetName());
            List<String> headers = p05.templateHeaders();
            Map<String, String> overlapRow = new LinkedHashMap<>();
            overlapRow.put("销售料号", P05_SKU_EXIST);
            overlapRow.put("客户编号", P05_CUST_EXIST);
            overlapRow.put("客户产品编号", P05_EXIST_PRODUCT_NO);
            overlapRow.put("客户名称", "被覆盖测试-不应生效");
            Row r2 = p05Sheet.createRow(2);
            for (int i = 0; i < headers.size(); i++) {
                String v = overlapRow.get(headers.get(i));
                if (v != null) r2.createCell(i).setCellValue(v);
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            wb.write(bos);
            return bos.toByteArray();
        }
    }

    private ImportResultDTO doImport(byte[] xlsx) {
        return importService.importExcel("task0812-fixture.xlsx", new ByteArrayInputStream(xlsx), anyUserId());
    }

    private static String dump(ImportResultDTO out) {
        StringBuilder sb = new StringBuilder("status=" + out.status + " success=" + out.totalSuccessRows
            + " failed=" + out.totalFailedRows + " sheetResults.size=" + out.sheetResults.size() + "\n");
        for (SheetResultDTO sr : out.sheetResults) {
            sb.append("  [").append(sr.sheetName).append("] total=").append(sr.totalRows)
              .append(" ok=").append(sr.successRows).append(" fail=").append(sr.failedRows);
            if (sr.errors != null && !sr.errors.isEmpty()) sb.append(" errors=").append(sr.errors.size());
            sb.append('\n');
        }
        return sb.toString();
    }

    @Transactional
    void deleteImportRecord(UUID id) {
        if (id == null) return;
        em.createNativeQuery("DELETE FROM import_record WHERE id = :id").setParameter("id", id).executeUpdate();
    }

    // ------------------------------------------------------------------
    // IMP-01 / IMP-02 / IMP-03 / IMP-04 / IMP-05a / IMP-06 —— 核心停用生效 + 零写入
    // ------------------------------------------------------------------

    @Test
    void imp01_02_coreDisabledSheetsIgnored() throws Exception {
        byte[] xlsx = buildFixture("6.5000", true);
        ImportResultDTO out = doImport(xlsx);
        try {
            System.out.println("=== IMP-01/02 ===\n" + dump(out));

            // IMP-01：sheetResults 条目数 = 18（16 循环 + 2 合并），不含 4 个停用名
            assertEquals(18, out.sheetResults.size(), "sheetResults 条目数应为 18: " + dump(out));
            for (String disabled : List.of("元素核价价格表", "材料核价价格表", "核价版本", "宏丰-客户料号对应关系")) {
                assertTrue(out.sheetResults.stream().noneMatch(sr -> sr.sheetName != null && sr.sheetName.contains(disabled)),
                    "sheetResults 不应含已停用 Sheet [" + disabled + "]: " + dump(out));
            }

            // IMP-02：totalSuccessRows 只应等于 P03 那 1 行（4 个停用 Sheet 各 1 行不计入）
            assertEquals(1, out.totalSuccessRows, "totalSuccessRows 应只含 P03 的 1 行，4 个停用 Sheet 的行不应计入: " + dump(out));
            assertEquals(0, out.totalFailedRows, dump(out));
            assertEquals("SUCCESS", out.status, dump(out));

            // IMP-03：unit_price 里两个夹具 code 均查不到
            assertEquals(0L, countUnitPrice(EL_CODE), "P01 夹具 code 不应写入 unit_price");
            assertEquals(0L, countUnitPrice(MT_CODE), "P02 夹具 code 不应写入 unit_price");

            // IMP-04：material_version_mgmt 夹具料号查不到
            assertEquals(0L, countVersionMgmt(P04_SKU), "P04 夹具料号不应写入 material_version_mgmt");

            // IMP-05a：material_customer_map 全新组合查不到
            assertEquals(0L, countCustomerMap(P05_SKU_NEW, P05_CUST_NEW), "P05 夹具组合不应写入 material_customer_map");

            // IMP-06：material_master 里 P05 专属料号查不到（0 rows）
            assertEquals(0L, countMaterialMaster(P05_SKU_NEW), "P05 专属料号不应写入 material_master");

            // 正向对照：P03（保留 Sheet）正常处理，证明"停用没有误伤其余 Sheet 的正常写入路径"
            assertEquals(1L, countExchangeRate(FX_BASE, FX_TGT), "P03 汇率行应正常写入 exchange_rate_v6");
        } finally {
            deleteImportRecord(out.importRecordId);
        }
    }

    // ------------------------------------------------------------------
    // VER-01 / VER-03 —— 重导两次幂等
    // ------------------------------------------------------------------

    @Test
    void ver01_03_reimportTwice_idempotent() throws Exception {
        byte[] xlsx = buildFixture("6.5000", true);
        ImportResultDTO first = doImport(xlsx);
        String v1 = null;
        try {
            v1 = versionOf(FX_BASE, FX_TGT);
            assertEquals("2000", v1, "首次导入应为版本 2000");

            ImportResultDTO second = doImport(xlsx);
            try {
                System.out.println("=== VER-01/03 second import ===\n" + dump(second));
                assertEquals(v1, versionOf(FX_BASE, FX_TGT), "重导相同内容不应升版");
                assertEquals(1L, countExchangeRate(FX_BASE, FX_TGT), "重导不应产生第二行/翻倍");

                // VER-03：4 个停用表两次导入之间始终零增量
                assertEquals(0L, countUnitPrice(EL_CODE));
                assertEquals(0L, countUnitPrice(MT_CODE));
                assertEquals(0L, countVersionMgmt(P04_SKU));
                assertEquals(0L, countCustomerMap(P05_SKU_NEW, P05_CUST_NEW));
                assertEquals(0L, countMaterialMaster(P05_SKU_NEW));
            } finally {
                deleteImportRecord(second.importRecordId);
            }
        } finally {
            deleteImportRecord(first.importRecordId);
        }
    }

    // ------------------------------------------------------------------
    // VER-02 —— 改值后第三次导入应正常升版（排除假阳性）
    // ------------------------------------------------------------------

    @Test
    void ver02_changedValue_bumpsVersion() throws Exception {
        ImportResultDTO first = doImport(buildFixture("6.5000", true));
        try {
            assertEquals("2000", versionOf(FX_BASE, FX_TGT));
            ImportResultDTO second = doImport(buildFixture("7.7000", true)); // 改汇率值
            try {
                assertEquals("2001", versionOf(FX_BASE, FX_TGT), "内容变化应升版到 2001");
            } finally {
                deleteImportRecord(second.importRecordId);
            }
        } finally {
            deleteImportRecord(first.importRecordId);
        }
    }

    // ------------------------------------------------------------------
    // IMP-07 —— 边界：仅 4 个停用 Sheet 有数据，其余 20 Sheet（含 P03）皆空
    // ------------------------------------------------------------------

    @Test
    void imp07_onlyDisabledSheetsHaveData() throws Exception {
        byte[] xlsx = buildFixture(null, true); // p03Rate=null → P03 也是 0 行
        ImportResultDTO out = doImport(xlsx);
        try {
            System.out.println("=== IMP-07 ===\n" + dump(out));
            assertEquals(18, out.sheetResults.size());
            for (SheetResultDTO sr : out.sheetResults) {
                assertEquals(0, sr.totalRows, "sheet[" + sr.sheetName + "] 应为 0 数据行: " + dump(out));
                assertEquals(0, sr.successRows);
                assertEquals(0, sr.failedRows);
            }
            assertEquals("SUCCESS", out.status, dump(out));
            assertEquals(0, out.totalSuccessRows);
            assertEquals(0, out.totalFailedRows);
        } finally {
            deleteImportRecord(out.importRecordId);
        }
    }

    // ------------------------------------------------------------------
    // IMP-08 —— 停用 Sheet 内数据格式错误不产生任何错误/不影响 status
    // ------------------------------------------------------------------

    @Test
    void imp08_disabledSheetsBadData_notExposed() throws Exception {
        byte[] xlsx = buildFixture(null, false); // disabledDataOk=false：元素代码/客户编号留空
        ImportResultDTO out = doImport(xlsx);
        try {
            System.out.println("=== IMP-08 ===\n" + dump(out));
            assertEquals("SUCCESS", out.status, dump(out));
            assertEquals(18, out.sheetResults.size());
            for (SheetResultDTO sr : out.sheetResults) {
                assertTrue(sr.errors == null || sr.errors.isEmpty(), "sheet[" + sr.sheetName + "] 不应有错误: " + dump(out));
                for (var e : sr.errors == null ? List.<com.cpq.basicdata.v6.parser.RowError>of() : sr.errors) {
                    String msg = e.message == null ? "" : e.message;
                    assertFalse(msg.contains("元素代码") || msg.contains("客户编号"),
                        "不应出现停用 Sheet 的必填校验错误: " + msg);
                }
            }
        } finally {
            deleteImportRecord(out.importRecordId);
        }
    }

    // ------------------------------------------------------------------
    // IMP-05b —— 存量行被"覆盖"也不被 upsert
    // ------------------------------------------------------------------

    @Transactional
    void seedExistingCustomerMapRow() {
        // 复用生产代码真实写路径（MaterialCustomerMapRepository#upsert，P05CustomerMapHandler 内部
        // 调用的同一个方法），而非拼裸 SQL——避免漏填 system_type / 冲突键列导致 seed 本身出错，
        // 也让"存量行"与生产写入行的列齐全度完全一致，测试更贴近真实场景。
        mapRepo.upsert(P05_SKU_EXIST, P05_CUST_EXIST, "原始客户名称",
            null, P05_EXIST_PRODUCT_NO, null, null, null, null, null, null, null, anyUserId());
    }

    @Transactional
    Object[] readCustomerMapRow(String materialNo, String customerNo) {
        List<?> rows = em.createNativeQuery(
            "SELECT customer_product_no, customer_name, updated_at FROM material_customer_map " +
            "WHERE material_no = :m AND customer_no = :c")
            .setParameter("m", materialNo).setParameter("c", customerNo).getResultList();
        return rows.isEmpty() ? null : (Object[]) rows.get(0);
    }

    @Test
    void imp05b_existingRowNotOverwrittenByDisabledSheet() throws Exception {
        seedExistingCustomerMapRow();
        Object[] before = readCustomerMapRow(P05_SKU_EXIST, P05_CUST_EXIST);
        assertNotNull(before, "前置 seed 应成功写入");
        System.out.println("=== IMP-05b before: productNo=" + before[0] + " name=" + before[1] + " updatedAt=" + before[2]);

        byte[] xlsx = buildFixtureWithExistingRowOverlap();
        ImportResultDTO out = doImport(xlsx);
        try {
            Object[] after = readCustomerMapRow(P05_SKU_EXIST, P05_CUST_EXIST);
            System.out.println("=== IMP-05b after: productNo=" + after[0] + " name=" + after[1] + " updatedAt=" + after[2]);
            assertEquals(before[0], after[0], "customer_product_no 不应被覆盖");
            assertEquals(before[1], after[1], "customer_name 不应被覆盖");
            assertEquals(before[2].toString(), after[2].toString(), "updated_at 不应变化（行完全未被碰）");
        } finally {
            deleteImportRecord(out.importRecordId);
        }
    }

    // ------------------------------------------------------------------
    // CODE-02 —— orderedHandlers() / PricingHandlerCatalog.all() 顺序逐位断言
    // ------------------------------------------------------------------

    @Test
    void code02_orderedHandlersSequence_notReordered() throws Exception {
        Method m = PricingImportService.class.getDeclaredMethod("orderedHandlers");
        m.setAccessible(true);
        // importService 是 CDI client proxy，反射调用私有方法必须先 unwrap 到真实 contextual
        // instance，否则代理对象自身字段全为 null（@Inject 字段只存在于真实实例上）。
        Object target = io.quarkus.arc.ClientProxy.unwrap(importService);
        @SuppressWarnings("unchecked")
        List<SheetHandler> ordered = (List<SheetHandler>) m.invoke(target);
        List<String> actualNames = ordered.stream().map(SheetHandler::sheetName).toList();
        System.out.println("=== CODE-02 orderedHandlers().sheetName() 实际序列 ===\n" + actualNames);

        // 停用后期望序列（需求文档 §5.3 第一序列）：p24,p03,p06,p07,p08,p09,p10,p11,p12,p13,p14,p15,p18,p21,p22,p23
        List<String> expectedAfter = List.of(
            p24.sheetName(), p03.sheetName(), p06.sheetName(), p07.sheetName(), p08.sheetName(),
            p09.sheetName(), p10.sheetName(), p11.sheetName(), p12.sheetName(), p13.sheetName(),
            p14.sheetName(), p15.sheetName(), p18.sheetName(), p21.sheetName(), p22.sheetName(), p23.sheetName());
        // 停用前期望序列（f551f37e 基线）：p24,p05,p03,p04,p06,p07,p01,p02,p08,p09,p10,p11,p12,p13,p14,p15,p18,p21,p22,p23
        List<String> expectedBefore = List.of(
            p24.sheetName(), p05.sheetName(), p03.sheetName(), p04.sheetName(), p06.sheetName(), p07.sheetName(),
            p01.sheetName(), p02.sheetName(), p08.sheetName(), p09.sheetName(), p10.sheetName(), p11.sheetName(),
            p12.sheetName(), p13.sheetName(), p14.sheetName(), p15.sheetName(), p18.sheetName(), p21.sheetName(),
            p22.sheetName(), p23.sheetName());

        if (actualNames.size() == 16) {
            assertEquals(expectedAfter, actualNames, "停用后 orderedHandlers() 顺序与需求文档 §5.3 第一序列不符（可能被重排）");
        } else if (actualNames.size() == 20) {
            assertEquals(expectedBefore, actualNames, "停用前 orderedHandlers() 顺序与基线不符（A 侧本不应变化，本测试仅作对照）");
        } else {
            fail("orderedHandlers() 长度既不是 16 也不是 20，意外: " + actualNames);
        }
    }

    // ------------------------------------------------------------------
    // helpers：SQL 计数
    // ------------------------------------------------------------------

    @Transactional
    long countUnitPrice(String code) {
        return ((Number) em.createNativeQuery("SELECT count(*) FROM unit_price WHERE code = :c")
            .setParameter("c", code).getSingleResult()).longValue();
    }

    @Transactional
    long countVersionMgmt(String materialNo) {
        return ((Number) em.createNativeQuery("SELECT count(*) FROM material_version_mgmt WHERE material_no = :m")
            .setParameter("m", materialNo).getSingleResult()).longValue();
    }

    @Transactional
    long countCustomerMap(String materialNo, String customerNo) {
        return ((Number) em.createNativeQuery(
                "SELECT count(*) FROM material_customer_map WHERE material_no = :m AND customer_no = :c")
            .setParameter("m", materialNo).setParameter("c", customerNo).getSingleResult()).longValue();
    }

    @Transactional
    long countMaterialMaster(String materialNo) {
        return ((Number) em.createNativeQuery("SELECT count(*) FROM material_master WHERE material_no = :m")
            .setParameter("m", materialNo).getSingleResult()).longValue();
    }

    // is_current=true 过滤是必须的：升版后旧版本行翻转为 is_current=false 并保留（不删除，见
    // P01P02PricingPriceVersioningTest 同类模式），不过滤会在 getResultList() 拿到多行且顺序未定义，
    // 第一次调试时正是漏了这个过滤导致 VER-02 假红（查到的是旧行不是新行，不是产品 bug）。

    @Transactional
    long countExchangeRate(String base, String target) {
        return ((Number) em.createNativeQuery(
                "SELECT count(*) FROM exchange_rate_v6 WHERE base_currency = :b AND target_currency = :t AND is_current = true")
            .setParameter("b", base).setParameter("t", target).getSingleResult()).longValue();
    }

    @Transactional
    String versionOf(String base, String target) {
        List<?> r = em.createNativeQuery(
                "SELECT version_no FROM exchange_rate_v6 WHERE base_currency = :b AND target_currency = :t AND is_current = true")
            .setParameter("b", base).setParameter("t", target).getResultList();
        return r.isEmpty() ? null : String.valueOf(r.get(0));
    }
}
