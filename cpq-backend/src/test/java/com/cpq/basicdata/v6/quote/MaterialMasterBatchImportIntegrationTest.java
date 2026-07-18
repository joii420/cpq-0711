package com.cpq.basicdata.v6.quote;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.transaction.Transactional;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.*;

import java.io.ByteArrayOutputStream;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P1-A 集成护栏 + 往返度量。把一份 <b>受控合成 workbook</b>（显式 {@code TESTP1A*} 料号，不触发按名生成 →
 * 完全可按前缀清理、无全局 material_master 污染）经 <b>真实</b> {@link QuoteImportService#processImport}
 * 跑通批量 upsert：
 * <ul>
 *   <li>类② unit_weight（单重 Q18 → {@code upsertBatchWithWeight}，末值非空胜 + 仅 null 也建行）</li>
 *   <li>类③ 仅 material_no（客户料号关系 Q02 → {@code upsertBatchMaterialNoOnly}）</li>
 * </ul>
 * <p><b>task-0717 repair-2 更新</b>：原还含"类① name/type（来料回收折扣 Q09 →
 * {@code upsertBatchNameType}，首个非空胜）"。repair-2 决策扩围（Q06~Q10 投入料号恒按材质，
 * 见 {@link Q09IncomingRecoveryHandler}）后，Q09 的"投入料号"列固化为材质料号语义——原始码直接
 * 作 unit_price 的 code，<b>不再</b> resolve/不再登记 material_master（材质名走 material_recipe
 * 兜底，见该 handler 类注释）。故 E 组（来料回收折扣）不再贡献 material_master 行，本集成测试
 * 的"类① name/type upsert"覆盖已转移到 {@code AssemblyBomMaterialSyncTest}
 * （组成件BOM 未命中材质集时仍走 {@code accMaterialMaster}→{@code upsertBatchNameType}），
 * 本测试保留 E 组 sheet 仅用于继续验证 unit_price 写入 + JDBC 往返度量，不再断言其贡献
 * material_master 行数。
 *
 * <p>验证：① 导入 SUCCESS；② 连跑两次 material_master/customer_map 落库 md5 一致（确定性 / 幂等重导）；
 * ③ 用 Hibernate {@link Statistics#getPrepareStatementCount()} 度量整条 processImport 的真实 JDBC 往返数。
 * 该测试同时在 <b>新代码</b> 与 <b>git stash 后的旧代码</b> 上各跑一次（命令行两遍），用日志里的往返数对比
 * 批量化前后的下降（其余路径恒定，差值即 material_master 批量节省）。
 *
 * <p>等价性的"逐位相同"由 {@code MaterialMaster*BatchUpsertEquivTest}（A/B SQL）保证；本测试补"真实链路 +
 * 往返下降 + 确定性"。
 */
@QuarkusTest
class MaterialMasterBatchImportIntegrationTest {

    @Inject QuoteImportService svc;
    @Inject ManagedExecutor managedExecutor;
    @Inject EntityManager em;
    @Inject EntityManagerFactory emf;

    static final String PFX = "TESTP1A";
    static final String CUST = "TESTP1A_CUST";
    static final String FNAME = "p1a-batch-integ-test.xlsx";
    static final int N = 40;   // 每 sheet 行数，放大往返对比

    @Transactional
    void cleanup() {
        em.createNativeQuery("DELETE FROM material_customer_map WHERE customer_no = :c").setParameter("c", CUST).executeUpdate();
        em.createNativeQuery("DELETE FROM unit_price WHERE customer_no = :c").setParameter("c", CUST).executeUpdate();
        em.createNativeQuery("DELETE FROM annual_discount WHERE material_no LIKE :p").setParameter("p", PFX + "%").executeUpdate();
        em.createNativeQuery("DELETE FROM material_master WHERE material_no LIKE :p").setParameter("p", PFX + "%").executeUpdate();
        em.createNativeQuery("DELETE FROM import_record WHERE original_file_name = :f").setParameter("f", FNAME).executeUpdate();
    }

    @BeforeEach void before() { cleanup(); }
    @AfterEach  void after()  { cleanup(); }

    @Transactional
    UUID anyCustomerId() {
        return (UUID) em.createNativeQuery("SELECT id FROM customer LIMIT 1").getSingleResult();
    }

    @Transactional
    UUID anyUserId() {
        return (UUID) em.createNativeQuery(
            "SELECT imported_by FROM import_record WHERE imported_by IS NOT NULL LIMIT 1").getSingleResult();
    }

    @Transactional
    String statusOf(UUID recordId) {
        return (String) em.createNativeQuery("SELECT import_status FROM import_record WHERE id = :id")
            .setParameter("id", recordId).getSingleResult();
    }

    /** material_master(TESTP1A%) + material_customer_map(CUST) 的稳定 md5。 */
    @Transactional
    String footprintMd5() {
        Object mm = em.createNativeQuery(
            "SELECT md5(COALESCE(string_agg(material_no||'|'||COALESCE(material_name,'')||'|'||" +
            "COALESCE(material_type,'')||'|'||COALESCE(unit_weight::text,''), ';' ORDER BY material_no),'')) " +
            "FROM material_master WHERE material_no LIKE :p").setParameter("p", PFX + "%").getSingleResult();
        Object cm = em.createNativeQuery(
            "SELECT md5(COALESCE(string_agg(material_no||'|'||COALESCE(customer_product_no,''), ';' " +
            "ORDER BY material_no, customer_product_no),'')) " +
            "FROM material_customer_map WHERE customer_no = :c").setParameter("c", CUST).getSingleResult();
        Object ad = em.createNativeQuery(
            "SELECT md5(COALESCE(string_agg(material_no||'|'||discount_order||'|'||COALESCE(discount_ratio::text,'')||" +
            "'|'||COALESCE(fixed_discount_value::text,'')||'|'||COALESCE(currency,'')||'|'||COALESCE(unit,'')||" +
            "'|'||COALESCE(discount_times::text,''), ';' ORDER BY material_no, discount_order),'')) " +
            "FROM annual_discount WHERE material_no LIKE :p").setParameter("p", PFX + "%").getSingleResult();
        return mm + "::" + cm + "::" + ad;
    }

    @Transactional
    long countMaterialMasterFootprint() {
        Object n = em.createNativeQuery("SELECT count(*) FROM material_master WHERE material_no LIKE :p")
            .setParameter("p", PFX + "%").getSingleResult();
        return ((Number) n).longValue();
    }

    private void setCell(Row row, int col, String val) {
        if (val != null) row.createCell(col).setCellValue(val);
    }

    private byte[] buildWorkbook() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            // --- 单重 (Q18, 类②) : headers [料号, 单重] ---
            Sheet s18 = wb.createSheet("单重");
            Row h18 = s18.createRow(0);
            h18.createCell(0).setCellValue("料号");
            h18.createCell(1).setCellValue("单重");
            int r = 1;
            for (int i = 1; i <= N; i++, r++) {
                Row row = s18.createRow(r);
                setCell(row, 0, PFX + "_W" + i);
                setCell(row, 1, String.valueOf(i * 1.5));
            }
            // dup：W1 再来一行更大权重（末值非空胜）
            { Row row = s18.createRow(r++); setCell(row, 0, PFX + "_W1"); setCell(row, 1, "999.0"); }
            // 仅 null 权重：建行、unit_weight 留 null（料号列必填，单重空）
            { Row row = s18.createRow(r++); setCell(row, 0, PFX + "_WNULL"); /* 单重空 */ }

            // --- 来料回收折扣 (Q09, 类①) : headers [投入料号, 投入料号名称, 宏丰料号, 回收折扣] ---
            Sheet s09 = wb.createSheet("来料回收折扣");
            Row h09 = s09.createRow(0);
            h09.createCell(0).setCellValue("投入料号");
            h09.createCell(1).setCellValue("投入料号名称");
            h09.createCell(2).setCellValue("宏丰料号");
            h09.createCell(3).setCellValue("回收折扣");
            r = 1;
            for (int i = 1; i <= N; i++, r++) {
                Row row = s09.createRow(r);
                setCell(row, 0, PFX + "_E" + i);
                setCell(row, 1, "元素" + i);
                setCell(row, 2, PFX + "_F" + i);
                setCell(row, 3, "0." + (i % 9 + 1));
            }
            // dup：E1 再来一行但名称空（首个非空胜 → material_master 仍保留"元素1"）。
            // 用不同宏丰料号 F1B → Q09 落不同 unit_price 分组，避免同组同 uq 撞键（与 P1-A 无关）。
            { Row row = s09.createRow(r++); setCell(row, 0, PFX + "_E1"); setCell(row, 2, PFX + "_F1B"); setCell(row, 3, "0.5"); }

            // --- 客户料号与宏丰料号的关系 (Q02, 类③) : headers [宏丰料号, 客户产品编号] ---
            Sheet s02 = wb.createSheet("客户料号与宏丰料号的关系");
            Row h02 = s02.createRow(0);
            h02.createCell(0).setCellValue("宏丰料号");
            h02.createCell(1).setCellValue("客户产品编号");
            r = 1;
            for (int i = 1; i <= N; i++, r++) {
                Row row = s02.createRow(r);
                setCell(row, 0, PFX + "_P" + i);
                setCell(row, 1, "CPN" + i);
            }
            // dup：P1 再来一行不同客户产品编号（material_master 去重为 1，customer_map 两行）
            { Row row = s02.createRow(r++); setCell(row, 0, PFX + "_P1"); setCell(row, 1, "CPN1B"); }

            // --- 年降系数 (Q19) : headers [宏丰料号, 年降顺序, 年降系数, 单次固定年降金额, 货币, 计价单位, 降价次数] ---
            Sheet s19 = wb.createSheet("年降系数");
            Row h19 = s19.createRow(0);
            String[] hd19 = {"宏丰料号", "年降顺序", "年降系数", "单次固定年降金额", "货币", "计价单位", "降价次数"};
            for (int c = 0; c < hd19.length; c++) h19.createCell(c).setCellValue(hd19[c]);
            r = 1;
            for (int i = 1; i <= N; i++, r++) {
                Row row = s19.createRow(r);
                setCell(row, 0, PFX + "_AD" + i);
                setCell(row, 1, "1");
                setCell(row, 2, "0.9" + (i % 9 + 1));
                setCell(row, 4, "CNY");
                setCell(row, 5, "件");
                setCell(row, 6, String.valueOf(i % 3 + 1));
            }
            // dup：AD1/order=1 再来一行只带 fixed（逐字段末值非空胜：ratio 保留、fixed 补上）
            { Row row = s19.createRow(r++); setCell(row, 0, PFX + "_AD1"); setCell(row, 1, "1"); setCell(row, 3, "7.5"); }

            wb.write(bos);
            return bos.toByteArray();
        }
    }

    @Transactional
    String metadataOf(UUID recordId) {
        return (String) em.createNativeQuery("SELECT metadata FROM import_record WHERE id = :id")
            .setParameter("id", recordId).getSingleResult();
    }

    private void runImport(UUID user, byte[] bytes) throws Exception {
        UUID recId = svc.createImportRecord(anyCustomerId(), FNAME, user);
        managedExecutor.runAsync(() -> svc.processImport(recId, CUST, FNAME, bytes, user))
            .get(60, TimeUnit.SECONDS);
        String st = statusOf(recId);
        if (!"SUCCESS".equals(st)) System.out.println("=== P1A-INTEG non-SUCCESS metadata: " + metadataOf(recId));
        assertEquals("SUCCESS", st, "导入应 SUCCESS");
    }

    @Test
    void batchImport_succeeds_isDeterministic_andCountsRoundTrips() throws Exception {
        UUID user = anyUserId();
        byte[] bytes = buildWorkbook();

        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);

        // --- run 1（计往返）---
        stats.clear();
        long before = stats.getPrepareStatementCount();
        runImport(user, bytes);
        long after = stats.getPrepareStatementCount();
        long prepared = after - before;

        String md5Run1 = footprintMd5();
        long mmRows = countMaterialMasterFootprint();
        // material_master 期望 = W(N)+WNULL(1) ∪ P(N) = 2N+1 个不同料号（前缀互不重叠）。
        // task-0717 repair-2 更新：E 组(来料回收折扣/Q09)"投入料号"列已固化为材质料号语义，
        // 不再 resolve/不再登记 material_master（见 Q09IncomingRecoveryHandler 类注释 + 上方类级 javadoc），
        // 故不再计入 material_master 行数（旧断言 3N+1 含 E(N) 对应 repair-2 前旧语义）。
        assertEquals(2L * N + 1, mmRows, "material_master TESTP1A 行数应=2N+1（W+WNULL ∪ P；E 组 repair-2 后不再登记 master）");

        // --- run 2（幂等重导，验确定性）---
        runImport(user, bytes);
        String md5Run2 = footprintMd5();
        assertEquals(md5Run1, md5Run2, "连跑两次 material_master/customer_map md5 必须一致（确定性/幂等）");

        // 该数字两遍运行（新代码 / git stash 后旧代码）对比：差值≈material_master(3 批) + annual_discount(1 批)
        // 批量节省（其余路径恒定）。
        System.out.println("=== P1A/Q19-INTEG prepareStatementCount(processImport, ~"
            + (4 * N) + " 行级写折叠为 4 批) = " + prepared + " ===");
    }
}
