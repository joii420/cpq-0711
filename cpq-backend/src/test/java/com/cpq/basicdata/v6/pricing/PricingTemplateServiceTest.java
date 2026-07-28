package com.cpq.basicdata.v6.pricing;

import com.cpq.basicdata.v6.dto.ImportResultDTO;
import com.cpq.basicdata.v6.dto.SheetResultDTO;
import com.cpq.basicdata.v6.parser.RowError;
import com.cpq.basicdata.v6.parser.SheetHandler;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * task-0728 · B4：核价基础数据 24 Sheet 空模板生成 + <b>闭环导入</b>自检。
 *
 * <p>三层验收：
 * <ol>
 *   <li>sheet 数 / 名 / 顺序逐个对齐 {@link PricingHandlerCatalog}（不是对齐一份手写常量）；</li>
 *   <li>表头写在第 1 行、无数据行、无重复列；</li>
 *   <li><b>闭环</b>：把生成的字节流原样喂给 {@link PricingImportService#importExcel}，
 *       断言不出现「缺少 Sheet / sheet 不存在」类错误，且每个模板 sheet 都被导入侧消费到。</li>
 * </ol>
 */
@QuarkusTest
class PricingTemplateServiceTest {

    @Inject PricingTemplateService templateService;
    @Inject PricingHandlerCatalog catalog;
    @Inject PricingImportService importService;
    @Inject EntityManager em;

    @Transactional
    UUID anyUserId() {
        return (UUID) em.createNativeQuery("SELECT id FROM \"user\" LIMIT 1").getSingleResult();
    }

    @Transactional
    void deleteImportRecord(UUID id) {
        em.createNativeQuery("DELETE FROM import_record WHERE id = :id").setParameter("id", id).executeUpdate();
    }

    private static List<String> headerRow(Sheet s) {
        Row r = s.getRow(0);
        if (r == null) return List.of();
        List<String> out = new ArrayList<>();
        for (int i = 0; i < r.getLastCellNum(); i++) {
            Cell c = r.getCell(i);
            out.add(c == null ? "" : c.getStringCellValue());
        }
        return out;
    }

    // ------------------------------------------------------------------ ①

    /** ① sheet 数 == handler 数（24），② 逐个对齐 sheetName() 与顺序。 */
    @Test
    void sheetsMatchHandlerRegistry() throws Exception {
        byte[] xlsx = templateService.generateTemplate();
        assertTrue(xlsx.length > 0);
        assertEquals('P', xlsx[0], "不是 xlsx（zip 魔数 PK）");
        assertEquals('K', xlsx[1]);

        List<SheetHandler> handlers = catalog.all();
        assertEquals(24, handlers.size(), "核价 handler 应为 24 个（P01~P24）");

        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            assertEquals(handlers.size(), wb.getNumberOfSheets(), "sheet 数与 handler 数不符");
            for (int i = 0; i < handlers.size(); i++) {
                assertEquals(handlers.get(i).sheetName(), wb.getSheetAt(i).getSheetName(),
                    "第 " + i + " 个 sheet 名与 handler 不符（顺序或命名漂移）");
            }
        }
    }

    /**
     * 登记表完备性：{@code com.cpq.basicdata.v6.pricing} 包下<b>所有</b> {@link SheetHandler} CDI bean
     * 都必须在 {@link PricingHandlerCatalog} 里 —— 防止将来加 P25 只接了导入、漏了模板（模板静默少一个 sheet）。
     */
    @Test
    void catalogCoversEveryPricingHandlerBean() {
        List<String> beans = new ArrayList<>();
        for (var h : io.quarkus.arc.Arc.container().listAll(SheetHandler.class)) {
            Class<?> c = h.getBean().getBeanClass();
            if (c.getPackageName().equals("com.cpq.basicdata.v6.pricing")) beans.add(c.getSimpleName());
        }
        List<String> registered = catalog.all().stream().map(h -> h.getClass().getSimpleName()).toList();
        List<String> missing = beans.stream()
            // CDI 代理类名形如 P01XxxHandler_ClientProxy / _Subclass，按前缀匹配
            .filter(b -> registered.stream().noneMatch(r -> r.startsWith(b)))
            .toList();
        assertTrue(missing.isEmpty(), "以下核价 handler 未登记进 PricingHandlerCatalog（模板会少 sheet）: " + missing);
        assertEquals(24, beans.size(), "核价 handler bean 数变了，请同步 catalog 与本测试: " + beans);
    }

    /** 表头写第 1 行、内容 == templateHeaders()、无重复列、无数据行。 */
    @Test
    void headersWrittenOnFirstRow_noDataRows() throws Exception {
        byte[] xlsx = templateService.generateTemplate();
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            for (SheetHandler h : catalog.all()) {
                Sheet s = wb.getSheet(h.sheetName());
                assertNotNull(s, "缺 sheet: " + h.sheetName());

                List<String> declared = h.templateHeaders();
                assertFalse(declared.isEmpty(), "handler 未声明表头: " + h.sheetName());
                assertEquals(declared, headerRow(s), "表头与 templateHeaders() 不一致: " + h.sheetName());
                assertEquals(declared.size(), declared.stream().distinct().count(),
                    "表头有重复列: " + h.sheetName() + " " + declared);
                for (String col : declared) {
                    assertFalse(col == null || col.isBlank(), "表头有空列名: " + h.sheetName());
                }
                assertEquals(0, s.getLastRowNum(), "模板不应有数据行: " + h.sheetName());
            }
        }
    }

    /**
     * 表头可用性：每个 sheet 的<b>必填键列</b>都能被 handler 的读取键（{@code SheetRow.getStr} 的
     * contains 语义）命中。清单独立抄自各 handler {@code handle()} 里判空报错的那些列 ——
     * 与 {@code templateHeaders()} 相互印证，任何一边写错都会被发现。
     */
    @Test
    void requiredKeyColumnsAreResolvable() {
        Map<String, List<String>> required = new LinkedHashMap<>();
        required.put("元素核价价格表", List.of("元素代码"));
        required.put("材料核价价格表", List.of("材料料号"));
        required.put("汇率管理表", List.of("基础货币", "核价货币", "核价汇率"));
        required.put("核价版本", List.of("销售料号", "核价版本编号"));
        required.put("宏丰-客户料号对应关系", List.of("销售料号", "客户编号", "客户产品编号"));
        required.put("物料BOM", List.of("销售料号", "组成料号", "计算类型"));
        required.put("物料与元素BOM", List.of("销售料号", "材质料号", "元素代码"));
        required.put("产能", List.of("销售料号", "工序编号", "人工标准单价"));
        required.put("设备折旧成本", List.of("销售料号", "工序编号", "折旧单价"));
        required.put("生产设备能耗", List.of("销售料号", "工序编号", "生产能耗单价"));
        required.put("辅助设备能耗", List.of("销售料号", "工序编号", "非生产能耗单价"));
        required.put("模具工装成本", List.of("销售料号", "工序编号", "模具台账", "模具工装成本单价"));
        required.put("生产耗材BOM", List.of("销售料号", "工序编号", "耗材成本单价"));
        required.put("包装材料BOM", List.of("销售料号", "工序编号", "包装成本单价"));
        required.put("来料加工费", List.of("来料料号", "销售料号", "加工费"));
        required.put("来料其他费用（比例）", List.of("来料料号", "要素编号", "比例"));
        required.put("来料其他固定费用", List.of("来料料号", "要素名称", "费用"));
        required.put("加工费&组装费", List.of("销售料号", "工序编号", "加工费"));
        required.put("成品其他比例费用", List.of("销售料号", "要素名称", "比例"));
        required.put("成品其他固定费用", List.of("销售料号", "要素名称", "费用"));
        required.put("电镀方案", List.of("方案编号", "电镀元素名称", "电镀面积", "镀层厚度"));
        required.put("电镀成本", List.of("销售料号", "电镀方案编号", "电镀加工费", "电镀材料费"));
        required.put("其他外加工成本", List.of("销售料号", "工序编号", "外加工费用"));
        required.put("单重", List.of("销售料号", "单重"));

        for (SheetHandler h : catalog.all()) {
            List<String> headers = h.templateHeaders();
            List<String> keys = required.get(h.sheetName());
            assertNotNull(keys, "本测试的必填键清单漏了 sheet: " + h.sheetName() + "（新增 handler 后请补齐）");
            for (String key : keys) {
                assertTrue(headers.stream().anyMatch(col -> col.contains(key)),
                    "sheet[" + h.sheetName() + "] 表头里没有能命中读取键「" + key + "」的列: " + headers);
            }
        }
    }

    // ------------------------------------------------------------------ ③

    /**
     * ③ <b>闭环</b>：生成的模板原样喂给导入服务 —— 不报「缺少 Sheet / sheet 不存在」，
     * 各 sheet 均按 0 数据行正常返回，且<b>每个模板 sheet 都被导入侧消费到</b>
     * （证明模板 sheet 名与 {@code wb.getSheet(h.sheetName())} 的匹配口径一致）。
     */
    @Test
    void generatedTemplate_importsCleanly() {
        byte[] xlsx = templateService.generateTemplate();
        UUID userId = anyUserId();

        ImportResultDTO out = importService.importExcel(
            "pricing_basic_data_template.xlsx", new ByteArrayInputStream(xlsx), userId);
        try {
            assertNotNull(out);
            assertEquals("PRICING", out.systemType);
            assertEquals(0, out.totalFailedRows, "空模板不该有失败行: " + dump(out));
            assertEquals(0, out.totalSuccessRows, "空模板不该写入任何数据行: " + dump(out));
            assertEquals("SUCCESS", out.status, dump(out));

            for (SheetResultDTO sr : out.sheetResults) {
                assertEquals(0, sr.totalRows, "sheet[" + sr.sheetName + "] 应为 0 数据行");
                assertTrue(sr.errors == null || sr.errors.isEmpty(),
                    "sheet[" + sr.sheetName + "] 报错: " + dump(out));
            }
            // 「缺少 Sheet / sheet 不存在」类错误的兜底扫描（措辞可能变，按关键词扫）
            for (SheetResultDTO sr : out.sheetResults) {
                if (sr.errors == null) continue;
                for (RowError e : sr.errors) {
                    String msg = e.message == null ? "" : e.message;
                    assertFalse(msg.contains("缺少") || msg.contains("不存在") || msg.toLowerCase().contains("sheet"),
                        "出现缺 Sheet 类错误: [" + sr.sheetName + "] " + msg);
                }
            }

            // 每个模板 sheet 都必须出现在导入报告里（P16/P17、P19/P20 走合并 bean，
            // 其报告名形如「来料其他费用（比例）+来料其他固定费用(合并)」，故用 contains 判定）。
            List<String> reported = out.sheetResults.stream().map(s -> s.sheetName).toList();
            for (SheetHandler h : catalog.all()) {
                assertTrue(reported.stream().anyMatch(n -> n != null && n.contains(h.sheetName())),
                    "模板 sheet [" + h.sheetName() + "] 未被导入侧消费（sheet 名两边不同源）。导入报告: " + reported);
            }
        } finally {
            if (out != null && out.importRecordId != null) deleteImportRecord(out.importRecordId);
        }
    }

    private static String dump(ImportResultDTO out) {
        StringBuilder sb = new StringBuilder("status=" + out.status
            + " success=" + out.totalSuccessRows + " failed=" + out.totalFailedRows + "\n");
        for (SheetResultDTO sr : out.sheetResults) {
            sb.append("  ").append(sr.sheetName).append(" total=").append(sr.totalRows)
              .append(" ok=").append(sr.successRows).append(" fail=").append(sr.failedRows);
            if (sr.errors != null && !sr.errors.isEmpty()) {
                sb.append(" errors=");
                for (RowError e : sr.errors) sb.append("[r").append(e.rowNo).append(' ')
                    .append(e.column).append(": ").append(e.message).append(']');
            }
            sb.append('\n');
        }
        return sb.toString();
    }
}
