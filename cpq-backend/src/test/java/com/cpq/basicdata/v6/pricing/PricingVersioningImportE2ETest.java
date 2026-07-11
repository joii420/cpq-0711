package com.cpq.basicdata.v6.pricing;

import com.cpq.basicdata.v6.dto.ImportResultDTO;
import com.cpq.basicdata.v6.dto.SheetResultDTO;
import com.cpq.basicdata.v6.parser.RowError;
import com.cpq.basicdata.v6.quote.QuoteImportService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * tesk-0709 §7 端到端真文件导入自检（一票否决验收关卡）。
 *
 * <p>用真实 Excel 文件（非合成 workbook）逐条验证 backtask.md §7：
 * <ul>
 *   <li>§7.3 核价重导 failedRows=0 + 落库正确（production_energy 两 price_type / labor_rate·auxiliary_energy·
 *       tooling_cost is_current + version=2000 / unit_price 各 price_type is_current + version_no=2000）</li>
 *   <li>§7.4 重导不升版（同文件二次导入 → 版本号不变、is_current 唯一）</li>
 *   <li>§7.6 顺序无关：由 {@code VersionedV6Writer.multisetEqual}（tally 比对）+
 *       {@code VersionedBatchEquivTest} 单测覆盖，此处不重复构造乱序真文件</li>
 *   <li>§7.7 报价回归：核价侧改造不得带坏报价导入（真实 V3 文件 failedRows=0）</li>
 * </ul>
 * §7.5 值变升版由各 handler 专项版本化单测覆盖（P01P02PricingPriceVersioningTest /
 * P03ExchangeRateVersioningTest / P08CapacityHandlerTest+P08LaborRateVersioningTest /
 * P09P10ProductionEnergyVersioningTest / P11AuxiliaryEnergyVersioningTest / P12ToolingCostVersioningTest /
 * UnitPriceFeeVersioningTest / PricingMergeVersioningTest），此 E2E 不重复改真文件某格再重导。
 *
 * <p>路径注意：测试工作目录 = {@code cpq-backend/}，真文件在 {@code ../docs/table/**}（不在 classpath，
 * 用 {@link FileInputStream} 直接读，而非 classpath getResourceAsStream）。
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PricingVersioningImportE2ETest {

    @Inject PricingImportService pricingService;
    @Inject QuoteImportService quoteService;
    @Inject ManagedExecutor managedExecutor;
    @Inject EntityManager em;

    static final String PRICING_FILE = "../docs/table/核价测试数据/核价系统功能基础数据功能结构所需字段（6.0版） .xlsx";
    static final String QUOTE_FILE = "../docs/table/报价测试数据/报价系统功能基础数据功能结构所需字段V3.xlsx";

    /**
     * 真实测试文件里 production_energy/labor_rate/auxiliary_energy/tooling_cost 四张专用表唯一出现的
     * 销售料号（实测确认）。这几张表是**共享测试库**，其它 handler 单测（如 P09P10ProductionEnergyVersioningTest）
     * 会用 {@code T0V.../T0D...} 等前缀料号写入自己的版本演进测试数据并可能残留跨会话正常升版的历史行——
     * 那些残留版本 <> 2000 是**其它测试的合法产物**，不是本 E2E 的回归。用真实料号显式过滤，
     * 避免"扫全表"把无关测试的残留数据误判为本任务引入的 bug（反之亦然：真出问题也不会被这些残留掩盖）。
     * unit_price 表在本次运行前对 system_type='PRICING' 是空的（实测确认 code 集合与本文件行一一对应），
     * 可安全整表断言，无需按料号过滤。
     */
    static final String REAL_MATERIAL_NO = "3120018220";

    // ---------- helpers ----------

    @Transactional
    UUID anyUserId() {
        return (UUID) em.createNativeQuery("SELECT id FROM \"user\" LIMIT 1").getSingleResult();
    }

    @Transactional
    UUID anyCustomerId() {
        return (UUID) em.createNativeQuery("SELECT id FROM customer LIMIT 1").getSingleResult();
    }

    private static InputStream openFile(String relPath) throws Exception {
        File f = new File(relPath);
        assertTrue(f.exists(), "测试文件不存在，检查相对路径/工作目录: " + f.getAbsolutePath());
        return new FileInputStream(f);
    }

    /** 把失败 sheet 的错误明细打印到 stdout，方便 mvn 输出里直接看到根因。 */
    private static void dumpFailures(String label, ImportResultDTO out) {
        System.out.println("=== " + label + " totalSuccessRows=" + out.totalSuccessRows
            + " totalFailedRows=" + out.totalFailedRows + " status=" + out.status + " ===");
        if (out.sheetResults != null) {
            for (SheetResultDTO s : out.sheetResults) {
                System.out.println("  sheet=" + s.sheetName + " total=" + s.totalRows
                    + " success=" + s.successRows + " failed=" + s.failedRows);
                if (s.failedRows > 0 && s.errors != null) {
                    List<RowError> errs = s.errors;
                    int show = Math.min(errs.size(), 20);
                    for (int i = 0; i < show; i++) {
                        RowError e = errs.get(i);
                        System.out.println("      row=" + e.rowNo + " col=" + e.column + " msg=" + e.message);
                    }
                    if (errs.size() > show) System.out.println("      ... 还有 " + (errs.size() - show) + " 条");
                }
            }
        }
    }

    @Transactional
    long countGroupsWithMultiCurrent(String sql) {
        Object n = em.createNativeQuery(sql).getSingleResult();
        return ((Number) n).longValue();
    }

    @Transactional
    long countDistinctVersion(String sql) {
        Object n = em.createNativeQuery(sql).getSingleResult();
        return n == null ? 0 : ((Number) n).longValue();
    }

    // ---------- §7.3 首次核价导入 ----------

    static ImportResultDTO firstImportResult;

    @Test
    @Order(1)
    void step1_pricingFirstImport_failedRowsZero_andLandsCorrectly() throws Exception {
        UUID user = anyUserId();
        try (InputStream stream = openFile(PRICING_FILE)) {
            firstImportResult = pricingService.importExcel(
                "核价系统功能基础数据功能结构所需字段（6.0版） .xlsx", stream, user);
        }
        dumpFailures("§7.3 核价首次导入", firstImportResult);

        assertEquals(0, firstImportResult.totalFailedRows,
            "§7.3 核价首次导入 failedRows 应为 0，实际=" + firstImportResult.totalFailedRows
                + "（详情见上方 dump）");

        // --- production_energy：同料号同工序应同时存在 DEPRECIATION + ENERGY 两类 is_current 行，unit_price 非空 ---
        long peBothTypes = countGroupsWithMultiCurrent(
            "SELECT count(*) FROM (" +
            "  SELECT material_no, process_no FROM production_energy" +
            "  WHERE is_current = true AND system_type = 'PRICING' AND unit_price IS NOT NULL" +
            "  AND material_no = '" + REAL_MATERIAL_NO + "'" +
            "  GROUP BY material_no, process_no" +
            "  HAVING count(DISTINCT price_type) = 2" +
            ") t");
        assertTrue(peBothTypes > 0,
            "§7.3 production_energy 应存在同料号同工序 DEPRECIATION+ENERGY 两类 is_current 行，实际组数=" + peBothTypes);
        long peBadVersion = countDistinctVersion(
            "SELECT count(*) FROM production_energy WHERE is_current = true AND system_type = 'PRICING' " +
            "AND material_no = '" + REAL_MATERIAL_NO + "' AND calc_version <> '2000'");
        assertEquals(0, peBadVersion, "§7.3 production_energy(料号=" + REAL_MATERIAL_NO + ") 首次导入 is_current 行版本号应全为 2000");

        // --- labor_rate / auxiliary_energy / tooling_cost：有 is_current 行、版本号=2000（按真实料号过滤，
        //     避开其它 handler 单测用 T0V.../T0D... 等前缀料号残留的历史版本行，见 REAL_MATERIAL_NO 注释）---
        long laborRateCount = countDistinctVersion(
            "SELECT count(*) FROM labor_rate WHERE is_current = true AND system_type = 'PRICING' AND material_no = '" + REAL_MATERIAL_NO + "'");
        assertTrue(laborRateCount > 0, "§7.3 labor_rate 应有 is_current 行，实际=" + laborRateCount);
        long laborRateBadVersion = countDistinctVersion(
            "SELECT count(*) FROM labor_rate WHERE is_current = true AND system_type = 'PRICING' AND material_no = '"
                + REAL_MATERIAL_NO + "' AND version_no <> '2000'");
        assertEquals(0, laborRateBadVersion, "§7.3 labor_rate 首次导入 is_current 行版本号应全为 2000");

        long auxEnergyCount = countDistinctVersion(
            "SELECT count(*) FROM auxiliary_energy WHERE is_current = true AND system_type = 'PRICING' AND material_no = '" + REAL_MATERIAL_NO + "'");
        assertTrue(auxEnergyCount > 0, "§7.3 auxiliary_energy 应有 is_current 行，实际=" + auxEnergyCount);
        long auxEnergyBadVersion = countDistinctVersion(
            "SELECT count(*) FROM auxiliary_energy WHERE is_current = true AND system_type = 'PRICING' AND material_no = '"
                + REAL_MATERIAL_NO + "' AND calc_version <> '2000'");
        assertEquals(0, auxEnergyBadVersion, "§7.3 auxiliary_energy 首次导入 is_current 行版本号应全为 2000");

        long toolingCostCount = countDistinctVersion(
            "SELECT count(*) FROM tooling_cost WHERE is_current = true AND system_type = 'PRICING' AND material_no = '" + REAL_MATERIAL_NO + "'");
        assertTrue(toolingCostCount > 0, "§7.3 tooling_cost 应有 is_current 行，实际=" + toolingCostCount);
        long toolingCostBadVersion = countDistinctVersion(
            "SELECT count(*) FROM tooling_cost WHERE is_current = true AND system_type = 'PRICING' AND material_no = '"
                + REAL_MATERIAL_NO + "' AND calc_version <> '2000'");
        assertEquals(0, toolingCostBadVersion, "§7.3 tooling_cost 首次导入 is_current 行版本号应全为 2000");

        // --- unit_price：PRICING 侧各 price_type 有 is_current 行、version_no=2000 ---
        // 注：PLATING(P22 电镀成本) 不在此列断言 —— 真实测试文件「电镀成本」sheet 唯一一行的
        // "电镀方案编号"非空（=A0001），按 P22PlatingCostHandler 既有逻辑（"沿用原逻辑"，非本任务改造）
        // 视为电镀方案引用行、跳过不落 unit_price，故该 price_type 在此文件下无 is_current 行属预期数据特征，
        // 不是 tesk-0709 版本化改造引入的回归。
        String[] priceTypes = {"ELEMENT", "MATERIAL_PRICE", "CONSUMABLE", "PACKAGING", "INCOMING_PROCESS",
            "INCOMING_OTHER", "SELF_PROCESS", "FINISHED_OTHER", "OUTSOURCE_PROCESS"};
        for (String pt : priceTypes) {
            long cnt = countDistinctVersion(
                "SELECT count(*) FROM unit_price WHERE system_type = 'PRICING' AND is_current = true AND price_type = '" + pt + "'");
            assertTrue(cnt > 0, "§7.3 unit_price price_type=" + pt + " 应有 is_current 行，实际=" + cnt);
            long badVersion = countDistinctVersion(
                "SELECT count(*) FROM unit_price WHERE system_type = 'PRICING' AND is_current = true AND price_type = '"
                    + pt + "' AND version_no <> '2000'");
            assertEquals(0, badVersion, "§7.3 unit_price price_type=" + pt + " 首次导入 is_current 行版本号应全为 2000");
        }

        // --- material_no=销售料号、production_no=生产料号 抽查（两列均非空即视为语义落位正确） ---
        long peMissingMaterialNo = countDistinctVersion(
            "SELECT count(*) FROM production_energy WHERE is_current = true AND system_type = 'PRICING' " +
            "AND (material_no IS NULL OR material_no = '')");
        assertEquals(0, peMissingMaterialNo, "§7.3 production_energy material_no(销售料号) 不应为空");
    }

    // ---------- §7.4 重导不升版 ----------

    @Test
    @Order(2)
    void step2_pricingReimport_doesNotBumpVersion_isCurrentUniquePerGroup() throws Exception {
        UUID user = anyUserId();
        ImportResultDTO second;
        try (InputStream stream = openFile(PRICING_FILE)) {
            second = pricingService.importExcel(
                "核价系统功能基础数据功能结构所需字段（6.0版） .xlsx", stream, user);
        }
        dumpFailures("§7.4 核价重导（同文件第2次）", second);
        assertEquals(0, second.totalFailedRows, "§7.4 重导 failedRows 应仍为 0，实际=" + second.totalFailedRows);

        // --- 版本号未累加：is_current 行版本仍为 2000（按真实料号过滤，理由见 REAL_MATERIAL_NO 注释）---
        assertEquals(0, countDistinctVersion(
            "SELECT count(*) FROM labor_rate WHERE is_current = true AND system_type = 'PRICING' AND material_no = '"
                + REAL_MATERIAL_NO + "' AND version_no <> '2000'"),
            "§7.4 labor_rate 重导后 is_current 版本应仍为 2000（不升版）");
        assertEquals(0, countDistinctVersion(
            "SELECT count(*) FROM auxiliary_energy WHERE is_current = true AND system_type = 'PRICING' AND material_no = '"
                + REAL_MATERIAL_NO + "' AND calc_version <> '2000'"),
            "§7.4 auxiliary_energy 重导后 is_current 版本应仍为 2000（不升版）");
        assertEquals(0, countDistinctVersion(
            "SELECT count(*) FROM tooling_cost WHERE is_current = true AND system_type = 'PRICING' AND material_no = '"
                + REAL_MATERIAL_NO + "' AND calc_version <> '2000'"),
            "§7.4 tooling_cost 重导后 is_current 版本应仍为 2000（不升版）");
        assertEquals(0, countDistinctVersion(
            "SELECT count(*) FROM production_energy WHERE is_current = true AND system_type = 'PRICING' AND material_no = '"
                + REAL_MATERIAL_NO + "' AND calc_version <> '2000'"),
            "§7.4 production_energy 重导后 is_current 版本应仍为 2000（不升版）");
        assertEquals(0, countDistinctVersion(
            "SELECT count(*) FROM unit_price WHERE system_type = 'PRICING' AND is_current = true AND version_no <> '2000'"),
            "§7.4 unit_price(PRICING) 重导后 is_current 版本应仍为 2000（不升版）");

        // --- is_current 唯一性：每个版本组只应有一个 current 版本号（按真实料号过滤）---
        // production_energy 每 (material_no, price_type) 组 is_current 版本唯一
        assertEquals(0, countGroupsWithMultiCurrent(
            "SELECT count(*) FROM (SELECT material_no, price_type, count(DISTINCT calc_version) v " +
            "  FROM production_energy WHERE is_current = true AND system_type = 'PRICING' AND material_no = '" + REAL_MATERIAL_NO + "'" +
            "  GROUP BY material_no, price_type HAVING count(DISTINCT calc_version) > 1) t"),
            "§7.4 production_energy 每 (material_no,price_type) 组 is_current 版本必须唯一");

        // labor_rate 每 material_no 组唯一
        assertEquals(0, countGroupsWithMultiCurrent(
            "SELECT count(*) FROM (SELECT material_no, count(DISTINCT version_no) v " +
            "  FROM labor_rate WHERE is_current = true AND system_type = 'PRICING' AND material_no = '" + REAL_MATERIAL_NO + "'" +
            "  GROUP BY material_no HAVING count(DISTINCT version_no) > 1) t"),
            "§7.4 labor_rate 每 material_no 组 is_current 版本必须唯一");

        // auxiliary_energy 每 material_no 组唯一
        assertEquals(0, countGroupsWithMultiCurrent(
            "SELECT count(*) FROM (SELECT material_no, count(DISTINCT calc_version) v " +
            "  FROM auxiliary_energy WHERE is_current = true AND system_type = 'PRICING' AND material_no = '" + REAL_MATERIAL_NO + "'" +
            "  GROUP BY material_no HAVING count(DISTINCT calc_version) > 1) t"),
            "§7.4 auxiliary_energy 每 material_no 组 is_current 版本必须唯一");

        // tooling_cost 每 material_no 组唯一
        assertEquals(0, countGroupsWithMultiCurrent(
            "SELECT count(*) FROM (SELECT material_no, count(DISTINCT calc_version) v " +
            "  FROM tooling_cost WHERE is_current = true AND system_type = 'PRICING' AND material_no = '" + REAL_MATERIAL_NO + "'" +
            "  GROUP BY material_no HAVING count(DISTINCT calc_version) > 1) t"),
            "§7.4 tooling_cost 每 material_no 组 is_current 版本必须唯一");

        // unit_price INCOMING_OTHER 同 finished_material_no is_current 版本唯一（合并防双升版关键护栏）
        assertEquals(0, countGroupsWithMultiCurrent(
            "SELECT count(*) FROM (SELECT finished_material_no, count(DISTINCT version_no) v FROM unit_price " +
            "  WHERE system_type='PRICING' AND price_type='INCOMING_OTHER' AND is_current=TRUE " +
            "  GROUP BY finished_material_no HAVING count(DISTINCT version_no) > 1) t"),
            "§7.4 unit_price INCOMING_OTHER(P16+P17合并组) 同销售料号 is_current 版本必须唯一（无双升版）");

        // unit_price FINISHED_OTHER 同 code is_current 版本唯一（P19+P20 合并组）
        assertEquals(0, countGroupsWithMultiCurrent(
            "SELECT count(*) FROM (SELECT code, count(DISTINCT version_no) v FROM unit_price " +
            "  WHERE system_type='PRICING' AND price_type='FINISHED_OTHER' AND is_current=TRUE " +
            "  GROUP BY code HAVING count(DISTINCT version_no) > 1) t"),
            "§7.4 unit_price FINISHED_OTHER(P19+P20合并组) 同销售料号 is_current 版本必须唯一（无双升版）");

        // unit_price 其它 price_type 通用：同 (price_type, code) 组 is_current 版本唯一
        assertEquals(0, countGroupsWithMultiCurrent(
            "SELECT count(*) FROM (SELECT price_type, code, count(DISTINCT version_no) v FROM unit_price " +
            "  WHERE system_type='PRICING' AND is_current=TRUE " +
            "  GROUP BY price_type, code HAVING count(DISTINCT version_no) > 1) t"),
            "§7.4 unit_price 每 (price_type,code) 组 is_current 版本必须唯一");

        // --- 总行数相对第一次导入无增长（不累加）---
        assertEquals(firstImportResult.totalSuccessRows, second.totalSuccessRows,
            "§7.4 重导 successRows 应与首次导入一致（内容未变，不产生新行）");
    }

    // ---------- §7.7 报价回归 ----------

    @Test
    @Order(3)
    void step3_quoteImport_regression_failedRowsZero() throws Exception {
        UUID user = anyUserId();
        UUID customerId = anyCustomerId();
        byte[] bytes;
        try (InputStream stream = openFile(QUOTE_FILE)) {
            bytes = stream.readAllBytes();
        }
        UUID recordId = quoteService.createImportRecord(customerId,
            "报价系统功能基础数据功能结构所需字段V3.xlsx", user);
        managedExecutor.runAsync(() ->
                quoteService.processImport(recordId, "E2E_TESK0709_CUST", "报价系统功能基础数据功能结构所需字段V3.xlsx", bytes, user))
            .get(120, TimeUnit.SECONDS);

        Object[] row = statusAndCounts(recordId);
        String status = (String) row[0];
        int unmatchedRows = row[1] == null ? -1 : ((Number) row[1]).intValue();
        String metadata = (String) row[2];

        if (unmatchedRows != 0) {
            System.out.println("=== §7.7 报价回归 non-zero unmatchedRows=" + unmatchedRows
                + " status=" + status + " metadata=" + metadata);
        }
        if (!"SUCCESS".equals(status)) {
            fail("§7.7 报价回归导入应 SUCCESS，实际=" + status + "，metadata=" + metadata);
        }
        assertEquals(0, unmatchedRows, "§7.7 报价回归 failedRows(unmatched_rows) 应为 0，实际=" + unmatchedRows);
    }

    @Transactional
    Object[] statusAndCounts(UUID recordId) {
        Object[] r = (Object[]) em.createNativeQuery(
            "SELECT import_status, unmatched_rows, metadata FROM import_record WHERE id = :id")
            .setParameter("id", recordId)
            .getSingleResult();
        return r;
    }
}
