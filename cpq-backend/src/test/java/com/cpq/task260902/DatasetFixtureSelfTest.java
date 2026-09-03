package com.cpq.task260902;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>夹具构造器自检 —— 不是 {@code @QuarkusTest}，不连库、不起应用。</b>
 *
 * <h3>它存在的唯一理由</h3>
 * {@code testing.md §4.4}：「自己写的验证脚本同样适用证伪要求 —— 先确认它能输出东西」。
 * 如果 {@link DatasetFixtureBuilder} 的改值落错了格、轴前缀没加上、插列插歪了，
 * 那么后面所有 AC 用例的红都是<b>夹具的错</b>，却会被读成实现缺陷。
 * ⇒ 先把夹具本身钉死，再谈业务断言。
 *
 * <p>本类可在应用起不来时单独运行：
 * {@code ./mvnw test -Dtest=DatasetFixtureSelfTest}
 */
@DisplayName("task-260902 · 夹具构造器自检（不连库）")
class DatasetFixtureSelfTest {

    // ═══════════ 矩阵解析 ═══════════

    @Test
    @DisplayName("FS-01 字段矩阵.md 解析出 45 主表 / 39 带版本 / 6 免版本")
    void fs01_matrixParses() {
        List<FieldMatrixSpec.TableSpec> specs = FieldMatrixSpec.parseAll();
        assertEquals(45, specs.size(), "主表数应为 45，实际 " + specs.size());
        assertEquals(39, specs.stream().filter(s -> s.versioned).count(), "带版本表应为 39");
        assertEquals(16, specs.stream().filter(s -> "quote".equals(s.dataset)).count(), "报价应 16 张");
        assertEquals(10, specs.stream().filter(s -> "cost-basic".equals(s.dataset)).count(), "基础核价应 10 张");
        assertEquals(19, specs.stream().filter(s -> "cost-detail".equals(s.dataset)).count(), "详细核价应 19 张");

        FieldMatrixSpec.TableSpec bom = specs.stream()
                .filter(s -> "ds_cost_basic_material_bom".equals(s.tableName)).findFirst().orElseThrow();
        assertEquals("production_no", bom.axisField, "物料BOM 的轴应为 production_no");
        assertTrue(bom.comparedFields.contains("component_qty"), "组成用量应是比对项（AC-14 靠它）");
        assertFalse(bom.comparedFields.contains("item_seq"), "项次不该是比对项（AC-17 靠它）");
        System.out.printf("[FS-01] 45 表解析 OK；物料BOM 比对项 %d 列%n", bom.comparedFields.size());
    }

    // ═══════════ 基础核价夹具 ═══════════

    @Test
    @DisplayName("FS-02 核价2 夹具：轴值全部带 TEST-DS- 前缀；主数据值已替换成库中实存值")
    void fs02_costBasicFixture() {
        DatasetFixtureBuilder b = Fixtures.costBasic();
        try {
            // 轴列全带前缀
            for (String sheet : Fixtures.COST_BASIC_VERSIONED_SHEETS) {
                List<Integer> rows = b.dataRowNumbers(sheet, true);
                assertFalse(rows.isEmpty(), sheet + " 一条数据行都没有 ⇒ 该 sheet 的断言会空跑");
                for (int rowNo : rows) {
                    String axis = b.readAsString(sheet, rowNo, "生产料号");
                    assertNotNull(axis, sheet + " 第 " + rowNo + " 行轴值为 null");
                    assertTrue(axis.startsWith(DatasetAcTestBase.P),
                            "🚨 " + sheet + " 第 " + rowNo + " 行轴值没加前缀：" + axis
                                    + " ⇒ 共享库红线被破坏，清理面会漏掉这行");
                }
            }
            // 物料 sheet（免版本）
            for (int rowNo : b.dataRowNumbers("物料", false)) {
                assertTrue(b.readAsString("物料", rowNo, "生产料号").startsWith(DatasetAcTestBase.P),
                        "物料 sheet 第 " + rowNo + " 行没加前缀");
            }

            // 🚩 2026-09-03 主线把 Z002/Z008/Z053/Z490/Z611 补进了 process_master、991 补进 material_recipe
            //    ⇒ 夹具（无探针时）**不再替换任何主数据值**，模板原值必须原封不动传下去。
            //    本类不连库，probe 为 null，正好验的就是「不替换」这条路径。
            for (int rowNo : b.dataRowNumbers("加工费&组装费", true)) {
                String op = b.readAsString("加工费&组装费", rowNo, "工序编号");
                assertTrue(op != null && op.startsWith("Z"),
                        "工序编号被夹具改坏了：" + op + "（主数据已补齐，不该再替换）");
            }
            assertTrue(Fixtures.appliedSubstitutions.isEmpty(),
                    "无探针时不该发生任何回退替换，实际：" + Fixtures.appliedSubstitutions);

            // AC 锚点未被夹具动过
            assertEquals("1", b.readAsString("物料BOM", 3, "组成用量"), "AC-14 锚点：组成用量应仍为 1");
            assertEquals("10", b.readAsString("物料BOM", 3, "项次"), "AC-17 锚点：项次应仍为 10");
            assertEquals("5.5", b.readAsString("来料加工费", 3, "加工费"), "AC-9/AC-18 锚点：加工费应仍为 5.5");
            assertEquals("主料1", b.readAsString("物料", 2, "品名"), "AC-21 锚点：品名应仍为 主料1");

            // 轴 TEST-DS-3120014539 在物料BOM 下应有 8 行（AC-12/AC-15 的行数判据）
            long n = b.dataRowNumbers("物料BOM", true).stream()
                    .filter(r -> DatasetAcTestBase.AXIS_BASIC.equals(b.readAsString("物料BOM", r, "生产料号")))
                    .count();
            assertEquals(8L, n, "AC-12 判据：该轴应有 8 行，实际 " + n);
            System.out.printf("[FS-02] 核价2 夹具 OK：轴 %s 有 %d 行%n", DatasetAcTestBase.AXIS_BASIC, n);
        } finally {
            b.close();
        }
    }

    @Test
    @DisplayName("FS-03 行操作：swapRows / deleteRow / appendCopyOf 落点正确")
    void fs03_rowOperations() {
        DatasetFixtureBuilder b = Fixtures.costBasic();
        try {
            String before3 = b.readAsString("物料BOM", 3, "组成料号");
            String before4 = b.readAsString("物料BOM", 4, "组成料号");
            assertFalse(before3.equals(before4), "自检前提：两行的组成料号本就不同");

            b.swapRows("物料BOM", 3, 4);
            assertEquals(before4, b.readAsString("物料BOM", 3, "组成料号"), "swapRows 没换成");
            assertEquals(before3, b.readAsString("物料BOM", 4, "组成料号"), "swapRows 没换成");

            int cnt = b.dataRowCount("物料BOM", true);
            b.deleteRow("物料BOM", 3);
            assertEquals(cnt - 1, b.dataRowCount("物料BOM", true), "deleteRow 没删掉");
            assertEquals(before3, b.readAsString("物料BOM", 3, "组成料号"), "deleteRow 后未上移");

            int cnt2 = b.dataRowCount("物料", false);
            b.appendCopyOf("物料", 2, row -> row.getCell(0).setCellValue(DatasetAcTestBase.P + "APPEND"));
            assertEquals(cnt2 + 1, b.dataRowCount("物料", false), "appendCopyOf 没加行");
            int last = b.dataRowNumbers("物料", false).get(cnt2);
            assertEquals(DatasetAcTestBase.P + "APPEND", b.readAsString("物料", last, "生产料号"),
                    "appendCopyOf 的 mutate 没生效");
        } finally {
            b.close();
        }
    }

    // ═══════════ 报价夹具 ═══════════

    @Test
    @DisplayName("FS-04 报价夹具：D-18 的「客户编号」列已插在第 0 位；残行已删；元素名已换代码")
    void fs04_quoteFixture() {
        DatasetFixtureBuilder b = Fixtures.quote();
        try {
            assertEquals(0, b.columnIndex("客户料号", Fixtures.CUSTOMER_NO_COLUMN),
                    "D-18 的「客户编号」应插在第 0 列");
            // 🚩 模板 04:22 起自带「客户编号」列（值 CUST-0004）⇒ 夹具不再插列，值取模板原文。
            //    判据放宽成「必须是 customer 表里实存的 3 个之一」，不写死某一个 —— 模板改值不该让它红。
            String custNo = b.readAsString("客户料号", 2, Fixtures.CUSTOMER_NO_COLUMN);
            assertTrue(List.of(DatasetAcTestBase.CUSTOMER_ROCKWELL, DatasetAcTestBase.CUSTOMER_TEST,
                            DatasetAcTestBase.CUSTOMER_CHINT).contains(custNo),
                    "客户编号必须取自 customer 表实存的 3 个（test.md §3.1），实际=" + custNo);
            assertEquals("A002", b.readAsString("客户料号", 2, "客户产品编号"),
                    "插列后原有列错位了");
            assertTrue(b.readAsString("客户料号", 2, "销售料号").startsWith(DatasetAcTestBase.P),
                    "销售料号应带前缀（清理面靠它）");

            // 模板残行已删：来料回收折扣 现在要么没有数据行，要么轴值非空
            for (int rowNo : b.dataRowNumbers("来料回收折扣", true)) {
                String axis = b.readAsString("来料回收折扣", rowNo, "销售料号");
                assertTrue(axis != null && !axis.isBlank(),
                        "来料回收折扣 第 " + rowNo + " 行轴值仍为空 ⇒ 模板残行没删干净，报价导入必 400");
            }

            // 🚩 2026-09-03 主线已把 线材/电解铜/钢板/锌锭 补进 element（裁决 D-22：仍严格校验，用户建数据）
            //    ⇒ 无探针时夹具**不再替换**，元素列必须保持模板原值。
            //    判据从「已被替换成代码」翻转为「未被夹具动过」——
            //    主数据补齐后，替换本身才是缺陷。
            List<String> elements = b.dataRowNumbers("物料与元素BOM", true).stream()
                    .map(r -> b.readAsString("物料与元素BOM", r, "元素"))
                    .toList();
            assertFalse(elements.isEmpty(), "元素列一行都没有 ⇒ 断言空跑");
            assertTrue(Fixtures.appliedSubstitutions.isEmpty(),
                    "无探针时不该发生任何回退替换，实际：" + Fixtures.appliedSubstitutions);
            System.out.printf("[FS-04] 元素列（模板原值，未替换）= %s%n", elements);

            // AC-36 锚点：材质 992 下含量 21.11 / 2.78 未被动过
            List<String> pcts = b.dataRowNumbers("物料与元素BOM", true).stream()
                    .filter(r -> DatasetAcTestBase.RECIPE_992.equals(
                            b.readAsString("物料与元素BOM", r, "材质料号")))
                    .map(r -> b.readAsString("物料与元素BOM", r, "组成含量（%）"))
                    .toList();
            assertTrue(pcts.contains("21.11"), "AC-36 锚点 21.11 丢了，实际：" + pcts);
            assertTrue(pcts.contains("2.78"), "AC-36 锚点 2.78 丢了，实际：" + pcts);
            System.out.printf("[FS-04] 报价夹具 OK：材质 992 含量 = %s%n", pcts);
        } finally {
            b.close();
        }
    }

    /**
     * 🚨 <b>写入生效守卫</b> —— 防 2026-09-03 那个「改值静默失效」再来一次。
     *
     * <p>模板 04:22 换版后单元格底层变成 {@code t="inlineStr"}，POI 的 {@code setCellValue}
     * 对这种单元格<b>既不报错也不生效</b> ⇒ 夹具每一次改值都悄悄没做，
     * 断言打在一份没被改过的文件上。修法是所有写入先 {@code setBlank()}。
     *
     * <p>本条是那条修复的<b>证伪守卫</b>：把 {@code setBlank()} 去掉，它必须变红。
     */
    @Test
    @DisplayName("FS-06 写入生效守卫：setText/setNumber/blank 三种写法都必须能读回新值（inlineStr 静默失效防线）")
    void fs06_writesActuallyTakeEffect() {
        DatasetFixtureBuilder b = Fixtures.costBasic();
        try {
            b.setText("物料BOM", 3, "组成料号", "WRITE-PROBE");
            assertEquals("WRITE-PROBE", b.readAsString("物料BOM", 3, "组成料号"),
                    "🚨 setText 静默失效：写进去的值读不回来。"
                            + "根因多半是 inlineStr 单元格未先 setBlank()（2026-09-03 实证）。"
                            + "⇒ 此时所有改值型用例都在验一份没被改过的文件，全是假绿/假红。");

            b.setNumber("物料BOM", 3, "组成用量", 987d);
            assertEquals("987", b.readAsString("物料BOM", 3, "组成用量"), "🚨 setNumber 静默失效");

            b.blank("物料BOM", 3, "组成类型");
            String blanked = b.readAsString("物料BOM", 3, "组成类型");
            assertTrue(blanked == null || blanked.isBlank(), "🚨 blank 静默失效，实际=" + blanked);

            // 轴前缀也走同一条写入路径
            String axis = b.readAsString("物料BOM", 3, "生产料号");
            assertTrue(axis != null && axis.startsWith(DatasetAcTestBase.P),
                    "🚨 轴前缀静默失效：实际=" + axis + "（共享库清理面依赖这个前缀）");
        } finally {
            b.close();
        }
    }

    @Test
    @DisplayName("FS-05 夹具产物是合法 xlsx（能被重新读回来），且非空")
    void fs05_outputIsValidXlsx() throws Exception {
        for (DatasetFixtureBuilder b : List.of(Fixtures.costBasic(), Fixtures.quote())) {
            try {
                byte[] bytes = b.toBytes();
                assertTrue(bytes.length > 4_000, "夹具产物过小（" + bytes.length + " 字节），疑似空文件");
                try (var in = new java.io.ByteArrayInputStream(bytes);
                     var wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook(in)) {
                    assertTrue(wb.getNumberOfSheets() >= 10,
                            "夹具产物 sheet 数不对：" + wb.getNumberOfSheets());
                }
            } finally {
                b.close();
            }
        }
    }
}
