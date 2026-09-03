package com.cpq.task260902;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>夹具前置自检（TD 组）</b> —— 不覆盖 AC，覆盖的是「后面那些 AC 断言有没有资格执行」。
 *
 * <h3>为什么单独一个类、而且必须先跑</h3>
 * {@code RECORD.md} 的 task-260902 选配 A 轮教训：夹具引用了库里根本不存在的材质与元素，
 * 119 处引用返工。更糟的是那种失败<b>长得像业务缺陷</b> —— 导入返 400「主数据不存在」，
 * 读报告的人会去查导入器，而真因是夹具编的值。
 *
 * <p>⇒ 把「夹具引用的主数据是否真实存在」提成独立用例：夹具不成立时，
 * 它以「<b>夹具前置不满足</b>」的名义硬失败，绝不伪装成业务缺陷。
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("task-260902 · TD 夹具前置自检")
class DatasetPreflightTest extends DatasetAcTestBase {

    // ═══════════════════ TD-01 三份 Excel 的可读性（test.md §0.4） ═══════════════════

    @Test
    @Order(1)
    @DisplayName("TD-01a 报价模板可读，16 个 sheet")
    void td01a_quoteTemplateReadable() {
        Path p = DatasetFixtureBuilder.quoteTemplate();
        assertTrue(DatasetFixtureBuilder.readable(p), "报价模板不可读：" + p);
        DatasetFixtureBuilder b = DatasetFixtureBuilder.from(p);
        try {
            assertEquals(Fixtures.QUOTE_SHEETS, b.sheetNames(), "报价模板 sheet 名/顺序与需求文档不符");
        } finally {
            b.close();
        }
    }

    @Test
    @Order(2)
    @DisplayName("TD-01b 核价2（基础核价）模板可读，10 个 sheet")
    void td01b_costBasicTemplateReadable() {
        Path p = DatasetFixtureBuilder.costBasicTemplate();
        assertTrue(DatasetFixtureBuilder.readable(p), "核价2 模板不可读：" + p);
        DatasetFixtureBuilder b = DatasetFixtureBuilder.from(p);
        try {
            assertEquals(Fixtures.COST_BASIC_SHEETS, b.sheetNames(), "核价2 模板 sheet 名/顺序与需求文档不符");
        } finally {
            b.close();
        }
    }

    @Test
    @Order(3)
    @DisplayName("TD-01c 核价1（详细核价）模板可读，19 个 sheet —— 🚩 曾损坏，用户已于 2026-09-03 02:25 重存")
    void td01c_costDetailTemplateReadable() {
        Path p = DatasetFixtureBuilder.costDetailTemplate();
        boolean present = java.nio.file.Files.isRegularFile(p);
        boolean ok = DatasetFixtureBuilder.readable(p);
        System.out.printf("[TD-01c] 核价1 存在=%s 可读=%s%n", present, ok);

        assertTrue(ok,
                "🚩 阻塞项（不是实现缺陷）：核价1 - 数据导入与表格建表.xlsx "
                        + (present ? "存在但不是合法 xlsx（BadZipFile）" : "不在本 worktree")
                        + "。依赖它的 AC-26（17 个 tab）/ AC-34（sheet「产能」）一律记「未验证」，"
                        + "🚫 不许用手搓文件冒充。");

        DatasetFixtureBuilder b = DatasetFixtureBuilder.from(p);
        try {
            assertEquals(Fixtures.COST_DETAIL_SHEETS, b.sheetNames(),
                    "核价1 模板 sheet 名/顺序与 字段矩阵.md 的详细核价一节不符");
            assertEquals(17, Fixtures.COST_DETAIL_VERSIONED_SHEETS.size(),
                    "AC-26 判据：详细核价应有 17 张带版本 sheet");
        } finally {
            b.close();
        }
    }

    // ═══════════════════ TD-02 主数据真实性（本轮返工的直接教训） ═══════════════════

    @Test
    @Order(4)
    @DisplayName("TD-02a 夹具引用的元素代码在 element 表存在且 ACTIVE")
    void td02a_elementsExist() {
        for (String code : List.of(ELEMENT_CU, ELEMENT_AG, ELEMENT_NI, "301")) {
            long n = count("SELECT count(*) FROM element WHERE element_code='" + code + "' AND status='ACTIVE'");
            assertEquals(1L, n, "夹具前置不满足：element 里没有 ACTIVE 的 element_code='" + code + "'");
        }
        long absent = count("SELECT count(*) FROM element WHERE element_code='" + ELEMENT_ABSENT + "'");
        assertEquals(0L, absent,
                "AC-8 依赖「" + ELEMENT_ABSENT + " 不存在」，但库里查到了 " + absent + " 行 ⇒ AC-8 会假绿");
    }

    @Test
    @Order(5)
    @DisplayName("TD-02b 夹具引用的材质料号在 material_recipe 存在且 ACTIVE")
    void td02b_recipesExist() {
        for (String code : List.of(RECIPE_00168, RECIPE_00006, RECIPE_992)) {
            long n = count("SELECT count(*) FROM material_recipe WHERE code='" + code + "' AND status='ACTIVE'");
            assertEquals(1L, n, "夹具前置不满足：material_recipe 里没有 ACTIVE 的 code='" + code + "'");
        }
        // 🚩 2026-09-03：主线已把 991 补进 material_recipe（symbol='991'，name 为占位值）
        //    ⇒ 旧断言「991 必须不存在」已失效并反转，改为正向断言：模板引用的它现在真的在。
        assertEquals(1L, count("SELECT count(*) FROM material_recipe WHERE code='991'"),
                "夹具前置不满足：模板「物料与元素BOM」引用材质料号 991，它应已被补进 material_recipe");
    }

    @Test
    @Order(6)
    @DisplayName("TD-02c 工序主数据：模板引用的 5 个工序编号都在 process_master —— R-7 已更正为 process_master.process_no")
    void td02c_processMasterDataReady() {
        // 🚩 本条曾以「契约歧义须主线裁决」的名义故意红着：
        //    需求文档 R-7 原写「工序编号 ∈ process」，而 process 表实测 0 行、字段名也对不上
        //    （code/name vs process_no/process_name），真实主数据在 process_master。
        //    2026-09-03 主线确认是文档缺陷并已更正 R-7；实现从一开始就查的 process_master。
        //    ⇒ 现在改成**正向断言**：模板引用的工序编号必须都能查到。
        List<String> templateOps = List.of("Z002", "Z008", "Z053", "Z490", "Z611");
        assertTrue(tableExists("process_master"), "夹具前置不满足：process_master 表不存在");

        List<String> missing = new ArrayList<>();
        for (String op : templateOps) {
            if (count("SELECT count(*) FROM process_master WHERE process_no='" + op + "'") == 0) {
                missing.add(op);
            }
        }
        System.out.printf("[TD-02c] process_master 命中模板工序 %d/%d，缺失=%s%n",
                templateOps.size() - missing.size(), templateOps.size(),
                missing.isEmpty() ? "无 ✅" : missing);

        assertTrue(missing.isEmpty(),
                "夹具前置不满足：模板引用的工序编号 " + missing + " 不在 process_master。"
                        + "\n  ⇒ 按 R-7（已更正为 process_master.process_no）会以「主数据不存在」整份拒收。"
                        + "\n  主线于 2026-09-03 补过这 5 条，若又缺了说明被别的会话删了。");
    }

    @Test
    @Order(7)
    @DisplayName("TD-02d 报价模板「元素」列的 5 个值都在 element 表（主线已于 2026-09-03 补建 4 个）")
    void td02d_quoteElementsReady() {
        // 🚩 曾以「模板与主数据不对齐」红着：模板该列填的是元素名称，5 个里只有「白银」命中。
        //    裁决 D-22：**仍严格校验，用户把这些建进 element 表**。主线已补 电解铜/线材/钢板/锌锭。
        List<String> names = List.of("线材", "白银", "电解铜", "锌锭", "钢板");
        List<String> missing = new ArrayList<>();
        for (String n : names) {
            long hit = count("SELECT count(*) FROM element WHERE element_code='" + n + "' OR element_name='" + n + "'");
            if (hit == 0) {
                missing.add(n);
            }
        }
        System.out.printf("[TD-02d] 报价模板元素列命中 %d/%d，缺失=%s%n",
                names.size() - missing.size(), names.size(), missing.isEmpty() ? "无 ✅" : missing);

        assertTrue(missing.isEmpty(),
                "夹具前置不满足：报价模板「物料与元素BOM · 元素」列的 " + missing
                        + " 在 element 表里既不是 code 也不是 name。"
                        + "\n  ⇒ 按 D-22（严格校验）会以「主数据不存在」整份拒收，AC-36 不可达。");
    }

    @Test
    @Order(8)
    @DisplayName("TD-02e 回归守卫：报价「来料回收折扣」不得再出现轴值为空的残行（用户已于 02:25 修掉）")
    void td02e_quoteStrayRowStaysFixed() {
        DatasetFixtureBuilder b = DatasetFixtureBuilder.from(DatasetFixtureBuilder.quoteTemplate());
        try {
            List<String> stray = new ArrayList<>();
            for (int rowNo : b.dataRowNumbers("来料回收折扣", true)) {
                String axis = b.readAsString("来料回收折扣", rowNo, "销售料号");
                if (axis == null || axis.isBlank()) {
                    stray.add("第 " + rowNo + " 行（投入料号="
                            + b.readAsString("来料回收折扣", rowNo, "投入料号") + "）");
                }
            }
            System.out.printf("[TD-02e] 来料回收折扣 轴值为空的残行：%s%n", stray.isEmpty() ? "无 ✅" : stray);
            assertTrue(stray.isEmpty(),
                    "🚩 模板残行回来了：报价模板「来料回收折扣」出现轴值为空的行 " + stray
                            + "。按 R-1「轴列一律必填」，原样导入必然 400。"
                            + "\n  该残行曾于 2026-09-03 02:25 由用户修掉，本条是防它回归的守卫。");
        } finally {
            b.close();
        }
    }

    @Test
    @Order(85)
    @DisplayName("TD-02f 夹具引用的客户编号在 customer.code 存在且 ACTIVE；AC-46 的反例确实不存在")
    void td02f_customerCodesExist() {
        for (String code : List.of(CUSTOMER_ROCKWELL, CUSTOMER_TEST, CUSTOMER_CHINT)) {
            long n = count("SELECT count(*) FROM customer WHERE code='" + code + "' AND status='ACTIVE'");
            assertEquals(1L, n,
                    "夹具前置不满足：customer 里没有 ACTIVE 的 code='" + code
                            + "'（test.md §3.1 点名只许用这 3 个）");
        }
        assertEquals(0L, count("SELECT count(*) FROM customer WHERE code='" + CUSTOMER_ABSENT + "'"),
                "AC-46 依赖「" + CUSTOMER_ABSENT + " 不存在」，库里却查到了 ⇒ AC-46 会假绿");

        // 主线点名「不许用」的编号，确认它们确实未登记（说明夹具选型的理由成立）
        long unregistered = count(
                "SELECT count(*) FROM customer WHERE code IN ('8000142','8000155','Q13CUST0617','C1')");
        System.out.printf("[TD-02f] 未登记编号命中数 = %d（期望 0，说明 test.md §3.1 的告诫成立）%n", unregistered);
    }

    @Test
    @Order(86)
    @DisplayName("TD-04 🚩 worktree 的立项文档未同步：字段矩阵.md 里缺 D-18 的 customer_no 列")
    void td04_taskDocsOutOfSync() {
        FieldMatrixSpec.TableSpec cp = SPECS.stream()
                .filter(s -> "ds_quote_customer_part".equals(s.tableName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("矩阵里没有 ds_quote_customer_part"));

        System.out.printf("[TD-04] 矩阵路径 = %s%n[TD-04] ds_quote_customer_part 建字段 = %s%n",
                FieldMatrixSpec.locateMatrix(), cp.builtColumns);

        assertTrue(cp.builtColumns.contains("customer_no"),
                "🚩 阻塞项（不是实现缺陷）：本 worktree 的 字段矩阵.md / 需求文档.md / test.md "
                        + "仍是 AC-44 版本，裁决 D-18（客户料号补 customer_no）与 AC-45~47 只落在主工作区、未同步进来。"
                        + "\n  ⇒ TS-02（AC-2 逐表列集合）会把实现建出来的 customer_no 报成「多列」，"
                        + "那是文档滞后造成的假红。"
                        + "\n  ⇒ 请主线把 3 份文档提交到分支 " + "feat/task-260902-dataset-tables-import" + " 后重跑。"
                        + "\n  实际解析到的建字段：" + cp.builtColumns);
    }

    // ═══════════════════ TD-03 契约一致性（矩阵类型 vs 模板实际值） ═══════════════════

    @Test
    @Order(9)
    @DisplayName("TD-03 🚩 字段矩阵把 25 个文本列判成 numeric —— 模板里这些格填的是 PCS/KG/g/网址")
    void td03_unitColumnsTypedNumeric() {
        record Bad(String table, String field, String type, String sample) {
        }
        List<Bad> bad = new ArrayList<>();
        for (FieldMatrixSpec.TableSpec s : SPECS) {
            for (var e : s.fieldType.entrySet()) {
                String field = e.getKey();
                String type = e.getValue();
                if (!type.startsWith("numeric")) {
                    continue;
                }
                if (field.endsWith("_unit") || field.startsWith("price_source") || field.equals("price_fetch_rule")) {
                    bad.add(new Bad(s.tableName, field, type, "模板实际值形如 PCS / KG / g / https://…"));
                }
            }
        }
        System.out.printf("[TD-03] 疑似类型判错的列 %d 个：%s%n", bad.size(),
                bad.stream().map(x -> x.table() + "." + x.field()).toList());

        assertTrue(bad.isEmpty(),
                "🚩 规范缺陷（须主线裁决，不是实现缺陷）：字段矩阵.md 把以下 " + bad.size()
                        + " 个列判成 numeric，但三份模板里这些格填的是文本"
                        + "（组成用量单位=PCS、重量单位=g、计价单位=PCS、元素单价来源网站网址=https://www.ccmn.cn/ …）。"
                        + "\n  ⇒ 若建表与 Phase 1 类型校验都按矩阵走，导入模板会以「不是合法数值」整份拒收，"
                        + "AC-11/12/13/36 全线不可达。"
                        + "\n  明细：" + bad.stream()
                        .map(x -> x.table() + "." + x.field() + " " + x.type())
                        .toList());
    }
}
