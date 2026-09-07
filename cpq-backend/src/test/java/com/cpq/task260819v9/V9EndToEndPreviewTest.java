package com.cpq.task260819v9;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 需求文档.md §9.4 <b>E 组 · 端到端（真实数据）</b> —— AC-117 / AC-119 / AC-120，
 * 外加一条<b>序列用例</b>（三类覆盖里的「序列」，AC-116 的后端侧对应物）。
 *
 * <h3>🚨 三条纪律在本类的落实</h3>
 * <ul>
 *   <li><b>基准紧邻取</b>：每条 AC 的 {@code count(*)} 都在<b>同一个方法体内、调预览之前</b>执行，
 *       并把「哪个库 + 什么查询 + 取到多少」打印出来。🚫 不照抄 §9.1.1 的 5 / 14 / 42。</li>
 *   <li><b>断言非空在先</b>：先断言基准 &gt; 0，再断言预览行数 —— 否则 0 == 0 会假通过。</li>
 *   <li><b>skip != pass</b>：环境前置不满足一律硬失败并注明「未验证」。</li>
 * </ul>
 *
 * <h3>本类会动的全局状态（testing.md §4.3 登记）</h3>
 * <ol>
 *   <li>{@code component} 表：1 行 {@code V9T-} 前缀的测试组件。{@code @AfterAll} 正向条件删除。</li>
 *   <li>{@code ds_quote_material}（仅 AC-120）：插入若干 {@code material_no} 带 {@code V9T-} 前缀的行。
 *       {@code @AfterEach} 用 {@code WHERE material_no LIKE 'V9T-%'} <b>正向条件</b>删除。
 *       🚫 绝不 {@code TRUNCATE}、绝不无 {@code WHERE} 删 —— 库里有别的会话的 42 行。
 *       ✅ 导入语义已由 {@code task-260902} 的 {@code DatasetUnversionedAcTest}（R-2「免版本表按主键 UPSERT」）
 *       确认为<b>按主键 upsert 而非整表替换</b>，所以带前缀的行不会波及别人的行；
 *       本类仍在导入前后<b>各断言一次「别人的行一个都没变」</b>作为安全网。</li>
 * </ol>
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class V9EndToEndPreviewTest extends V9TestBase {

    private static String componentId;

    @BeforeEach
    void ensureComponent() {
        if (componentId != null) {
            return;
        }
        String id = UUID.randomUUID().toString();
        String code = TAG + "E2E-" + System.currentTimeMillis();
        exec("INSERT INTO component(id, name, code, column_count, fields, formulas, status, "
                        + "created_at, updated_at, component_type, bom_recursive_expand, excel_columns) "
                        + "VALUES (CAST(?1 AS uuid), ?2, ?3, 0, '[]'::jsonb, '[]'::jsonb, 'ACTIVE', "
                        + "NOW(), NOW(), 'NORMAL', false, '[]'::jsonb)",
                id, code, code);
        componentId = id;
        System.out.println("[V9] E2E 测试组件 componentId=" + componentId);
    }

    @AfterEach
    void removeOwnQuoteRows() {
        // 正向条件，只删本套用例自己灌的行。每条用例后都跑一次，避免残留被下一条读成「产品有数据」。
        int n = exec("DELETE FROM ds_quote_material WHERE material_no LIKE 'V9T-%'");
        if (n > 0) {
            System.out.println("[V9] 清理自灌 ds_quote_material " + n + " 行（WHERE material_no LIKE 'V9T-%'）");
        }
    }

    @AfterAll
    static void dropComponents() {
        io.quarkus.narayana.jta.QuarkusTransaction.requiringNew().run(() -> {
            var em = io.quarkus.arc.Arc.container()
                    .instance(jakarta.persistence.EntityManager.class).get();
            int n = em.createNativeQuery("DELETE FROM component WHERE code LIKE 'V9T-%'").executeUpdate();
            System.out.println("[V9] 清理自建测试组件 " + n + " 行");
        });
    }

    // ═══════════════════════════════════════════════════════════════
    // AC-117（单点）核价基础端到端
    // ═══════════════════════════════════════════════════════════════
    @Test
    @Order(117)
    @DisplayName("AC-117: COST_BASIC 主件预览返回非空，行数 = 紧邻取的 ds_cost_basic_material 基准")
    void ac117_costBasicEndToEnd() {
        assertPreviewMatchesTableBaseline(COST_BASIC, "ds_cost_basic_material", "AC-117");
    }

    // ═══════════════════════════════════════════════════════════════
    // AC-119（单点）核价明细端到端（同 AC-117 口径）
    // ═══════════════════════════════════════════════════════════════
    @Test
    @Order(119)
    @DisplayName("AC-119: COST_DETAIL 主件预览返回非空，行数 = 紧邻取的 ds_cost_detail_material 基准")
    void ac119_costDetailEndToEnd() {
        assertPreviewMatchesTableBaseline(COST_DETAIL, "ds_cost_detail_material", "AC-119");
    }

    // ═══════════════════════════════════════════════════════════════
    // 序列用例（三类覆盖之「序列」）：配 COST_BASIC → 预览 → 切 COST_DETAIL → 预览 → 切回 → 预览
    // 断言中间态与最终态：每次结果都只来自当前数据集，切回后与首次完全一致（不残留、不串数据集）
    // ═══════════════════════════════════════════════════════════════
    @Test
    @Order(1165)
    @DisplayName("序列(AC-116 后端侧/AC-117/AC-119): 基础核价→明细核价→切回基础核价，三次预览各自正确且切回后与首次一致")
    void seq_switchDatasetBackAndForth() {
        long basicBaseline = scalarLong("SELECT count(*) FROM ds_cost_basic_material");
        long detailBaseline = scalarLong("SELECT count(*) FROM ds_cost_detail_material");
        System.out.println("[序列] 紧邻取基准（cpq_db_0724）：ds_cost_basic_material=" + basicBaseline
                + " ds_cost_detail_material=" + detailBaseline);
        assertTrue(basicBaseline > 0 && detailBaseline > 0, notReady("序列",
                "两个核价物料表至少一个是空表，序列断言会退化成 0==0 的假通过", "灌数据方"));

        int first = previewMainTab(COST_BASIC, "序列①");
        int middle = previewMainTab(COST_DETAIL, "序列②");
        int back = previewMainTab(COST_BASIC, "序列③");

        System.out.println("[序列] 三次预览行数 = " + first + " / " + middle + " / " + back);
        assertTrue(first > 0, "序列①: 基础核价首次预览必须非空，实际=" + first);
        assertTrue(middle > 0, "序列②: 切到明细核价后预览必须非空，实际=" + middle);
        assertEquals(first, back,
                "序列③: 切走再切回后，基础核价的预览必须与首次<b>完全一致</b>，实际 首次=" + first + " 切回=" + back
                        + "\n  不一致意味着切数据集留下了残留状态（已选列 / 缓存 / 方言），"
                        + "这正是『单点全过、串起来用翻车』的典型形态。");
        assertEquals(Math.min(basicBaseline, 50), first,
                "序列①: 基础核价预览行数应等于紧邻取的基准（受 LIMIT 50 上限约束）");
        assertEquals(Math.min(detailBaseline, 50), middle,
                "序列②: 明细核价预览行数应等于紧邻取的基准（受 LIMIT 50 上限约束）");
    }

    // ═══════════════════════════════════════════════════════════════
    // AC-120（单点·端到端）报价端到端 —— 自灌数据后验
    // ═══════════════════════════════════════════════════════════════
    @Test
    @Order(120)
    @DisplayName("AC-120: POST /dataset/quote/import 自灌数据 → QUOTE 主件预览非空且行数 = 紧邻基准（清理用正向条件）")
    void ac120_quoteEndToEndAfterSelfImport() throws Exception {
        // ── ① 安全网：先记下「别人的行」，导入前后各校验一次没被波及
        long othersBefore = scalarLong("SELECT count(*) FROM ds_quote_material WHERE material_no NOT LIKE 'V9T-%'");
        String othersDigestBefore = scalarStr(
                "SELECT md5(string_agg(material_no || '|' || COALESCE(material_name,'') || '|' "
                        + "|| COALESCE(production_no,''), ',' ORDER BY material_no)) "
                        + "FROM ds_quote_material WHERE material_no NOT LIKE 'V9T-%'");
        System.out.println("[AC-120] 导入前：别的会话的 ds_quote_material 行数=" + othersBefore
                + " 摘要=" + othersDigestBefore);
        assertTrue(othersBefore > 0,
                "AC-120 前置：ds_quote_material 里应有别的会话的行（实测 42 行）。"
                        + "为 0 说明表被清过 —— 停下来查，这可能是别人的数据被打掉了。");

        // ── ② 造夹具：从 **git HEAD 的 blob** 取模板，🚫 不读工作区那份。
        //    2026-09-06 实证：工作区副本文件头 = 877d1c49（**企业 DLP 加密**，git status 显示 M），
        //    POI 只会抛 `ZipException: Cannot find zip signature within the first 4096 bytes` ——
        //    看着像「模板损坏」，其实是环境问题（用户在 Windows 上打开完全正常，客户端透明解密）。
        //    HEAD 里那份是正常 zip（504b0304）。gitXlsxAtHead 会验明正身后再交给 POI。
        String tplRel = "dev-docs/task-260902-报价与核价建表与导入方案新规范/报价 - 数据导入与表格建表.xlsx";
        byte[] tplBytes = gitXlsxAtHead(tplRel);
        String axisPrefix = TAG;
        byte[] xlsx = buildQuoteFixture(tplBytes, axisPrefix, tplRel);

        // ── ③ 导入
        Response imp = RestAssured.given().cookie("CPQ_SESSION", session())
                .multiPart("file", "v9-quote-fixture.xlsx", xlsx,
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                .when().post("/api/cpq/dataset/{ds}/import", "quote");
        System.out.println("[AC-120] import → HTTP " + imp.statusCode() + " body=" + trunc(imp.asString()));
        assertEquals(200, imp.statusCode(),
                "🔴【AC-120 未验证 —— 环境/夹具前置未就绪，不是本任务的产品缺陷】导入返回 "
                        + imp.statusCode() + "，body=" + imp.asString()
                        + "\n  归属：报价导入模板 ⇄ dataset Registry 的字段口径不匹配，属 task-260902 / task-260903 那条线，"
                        + "🚫 本任务不改对方的模板（主线 2026-09-04 明确）。"
                        + "\n  ✅ 2026-09-06 已排除的一层：模板**不再是**读不出来的（原先工作区副本被企业 DLP 加密，"
                        + "文件头 877d1c49，POI 抛 ZipException）。现改从 git HEAD blob 取（504b0304，32081 字节，"
                        + "16 个 sheet 全部解析成功）⇒ **本条已不是「模板读不出来」，是导入器拒收**。"
                        + "\n  📌 当前实测归因（2026-09-06 逐条核过拒收明细，59 处）：**夹具把模板第 2 行当数据行**。"
                        + "\n     直接证据：『物料』sheet 报 `值「零件/外购件」不在允许值域：零件 / 外购件` ——"
                        + "「零件/外购件」是**值域说明文字**，不是某一行的取值 ⇒ 第 2 行是说明行。"
                        + "\n     其余 sheet 同型：clearDataRows() 对『带版本 sheet』从第 3 行起清（保留第 2 行当轴标记），"
                        + "而导入器把那一行当数据校验 ⇒ 『必填项为空 / 不是合法数值 / 主数据不存在』成片出现。"
                        + "\n     ⚠️ 连带：『物料BOM.销售料号 轴值未在物料表登记』(D-24 那条) **本次也在** ——"
                        + "因为『物料』只给带 V9T- 前缀的轴值，而未被清掉的说明行引用的是原始料号。"
                        + "\n  🔑 修法方向（留给主线裁决，🚫 本用例不自行猜模板契约）：要么夹具连第 2 行一起清，"
                        + "要么按 task-260902 的模板契约确认第 2 行到底是『说明行』还是『轴标记行』——"
                        + "两者对 clearDataRows() 的起始行要求相反，猜错就是换一种假绿。"
                        + "\n  ⚠️ 判定纪律：本条以**红**的身份出现在报告里，但结案时记【未验证】，"
                        + "🚫 不得记成通过，也 🚫 不得记成『本任务引入的缺陷』——"
                        + "夹具构造与报价导入模板契约属 task-260902 那条线，本任务不改对方的模板（主线 2026-09-04 明确）。"
                        + "\n  📌 安全网已复核：导入返回『本次未写入任何数据』，实测 ds_quote_material 的 V9T- 行 = 0、"
                        + "他人行数未变 ⇒ 这次红**没有污染共享库**。");

        // ── ④ 安全网复查：别人的行一个都没变
        long othersAfter = scalarLong("SELECT count(*) FROM ds_quote_material WHERE material_no NOT LIKE 'V9T-%'");
        String othersDigestAfter = scalarStr(
                "SELECT md5(string_agg(material_no || '|' || COALESCE(material_name,'') || '|' "
                        + "|| COALESCE(production_no,''), ',' ORDER BY material_no)) "
                        + "FROM ds_quote_material WHERE material_no NOT LIKE 'V9T-%'");
        assertEquals(othersBefore, othersAfter,
                "🚨 AC-120: 导入把别的会话的 ds_quote_material 行数改了（" + othersBefore + " → " + othersAfter
                        + "）—— 这是共享库事故，立刻停下报主线。");
        assertEquals(othersDigestBefore, othersDigestAfter,
                "🚨 AC-120: 导入改动了别的会话的 ds_quote_material 行内容（摘要变了）—— 共享库事故，立刻报主线。");

        // ── ⑤ 紧邻取基准（就在预览之前）
        long mine = scalarLong("SELECT count(*) FROM ds_quote_material WHERE material_no LIKE 'V9T-%'");
        System.out.println("[AC-120] 紧邻取基准：SELECT count(*) FROM ds_quote_material WHERE material_no LIKE 'V9T-%'"
                + " → " + mine + "（库 cpq_db_0724）");
        assertTrue(mine > 0,
                "AC-120: 导入返回 200 但一行都没进 ds_quote_material —— "
                        + "『预览 0 行』会变成两边都空的假通过，这里先挡掉。import 响应=" + trunc(imp.asString()));

        // ── ⑥ 预览：QUOTE 主件应只出我灌的这批（轴收窄按 material_no）
        long allQuoteMaterial = scalarLong("SELECT count(*) FROM ds_quote_material");
        int rowCount = previewMainTab(QUOTE, "AC-120");
        System.out.println("[AC-120] QUOTE 主件预览行数=" + rowCount
                + "（自灌=" + mine + "，全表=" + allQuoteMaterial + "）");
        assertTrue(rowCount > 0,
                "AC-120: 预览必须返回非空行。🚫 空列表 / 0 行 / 「—」一律不算通过。实际=" + rowCount);
        assertEquals(Math.min(allQuoteMaterial, 50), rowCount,
                "AC-120: 预览行数应等于紧邻取的基准（全表 " + allQuoteMaterial + " 受 LIMIT 50 约束）。"
                        + "\n  📌 若实现是『按料号收窄』而非『整表』，请主线裁决 AC-117/119/120 的基准口径 —— "
                        + "AC 原文写的是 `SELECT count(*) FROM <物料表>`（整表），"
                        + "而 builder 预览带 :total_material_no 收窄，两者只有在不传 partNo 时才等价。"
                        + "本用例不传 partNo，就是照 AC 原文来的。");
    }

    // ═══════════════════════ 公共断言 ═══════════════════════

    private void assertPreviewMatchesTableBaseline(String dialect, String table, String ac) {
        // 🚨 基准紧邻取，写明库与查询 —— D-72/D-74 教训（AC-15 照抄 dev 库数字导致恒失败）
        long baseline = scalarLong("SELECT count(*) FROM " + table);
        System.out.println("[" + ac + "] 紧邻取基准（库 cpq_db_0724）：SELECT count(*) FROM " + table
                + " → " + baseline);
        assertTrue(baseline > 0, notReady(ac,
                table + " 是空表 —— 『预览非空』无从验证，且 0 == 0 会假通过", "灌数据方"));

        int rowCount = previewMainTab(dialect, ac);
        System.out.println("[" + ac + "] 预览行数=" + rowCount + " 基准=" + baseline);
        assertTrue(rowCount > 0,
                ac + ": 预览必须返回非空行。🚫 空列表 / 0 行 / 「—」一律不算通过。实际=" + rowCount);
        assertEquals(Math.min(baseline, 50), rowCount,
                ac + ": 预览行数应 = 紧邻取的基准（" + baseline + "，受 api.md §2 的 LIMIT 50 约束）。实际=" + rowCount);
    }

    /** 用指定方言配一个「主件」组件并预览，返回 rowCount。所有断言失败信息都带上原始响应。 */
    private int previewMainTab(String dialect, String ac) {
        Object[] anchor = anchorNode(dialect, "主件");
        assertNotNull(anchor, notReady(ac,
                dialect + " 的『主件』页签视图不存在于 semantic_tab_view", "cpq-backend #2 / B-42"));
        String nodeKey = String.valueOf(anchor[0]);
        Object[] col = someColumnById(String.valueOf(anchor[3]));
        assertNotNull(col, notReady(ac, "节点 " + nodeKey + " 无列声明", "cpq-backend #2 / B-42"));

        Map<String, Object> cfg = new LinkedHashMap<>(config(dialect, "主件", null,
                List.of(column(nodeKey, String.valueOf(col[0]), "测试字段"))));
        // 🚫 不传 partNo —— AC-117/119/120 的基准是「整表 count」，传了料号就变成单料号收窄，口径不同

        Response p = preview(componentId, cfg);
        System.out.println("[" + ac + "] preview(" + dialect + ") → HTTP " + p.statusCode()
                + " body=" + trunc(p.asString()));
        assertEquals(200, p.statusCode(),
                ac + ": preview 应 200，实际=" + p.statusCode() + " body=" + p.asString());
        Integer rc = p.jsonPath().getObject("rowCount", Integer.class);
        assertNotNull(rc, ac + ": preview 响应缺 rowCount（api.md §2.3）。body=" + p.asString());
        if (rc == 0) {
            System.out.println("[" + ac + "] ⚠️ 0 行，后端诊断 = " + p.jsonPath().getString("diagnostics"));
        }
        return rc;
    }

    // ═══════════════════════ 夹具构造 ═══════════════════════

    /**
     * 报价夹具：只留「物料」sheet 的数据行并给「销售料号」加前缀，其余 sheet 全部清空。
     *
     * <p><b>为什么清空其余 sheet</b>：AC-120 只要求验「灌了数据 → 预览非空」，
     * 而带版本 sheet 一旦有数据就会触发主数据严格校验（元素/工序/材质/客户编号），
     * 校验不过整份拒收 —— 那时的红是<b>夹具的错</b>，不是产品的错，会误导排查。
     *
     * <p><b>为什么表头一个字不改</b>：表头列名是导入器的结构校验判据，手改必炸。
     */
    private static byte[] buildQuoteFixture(byte[] template, String axisPrefix, String tplRel) throws Exception {
        try (InputStream in = new java.io.ByteArrayInputStream(template);
             Workbook wb = new XSSFWorkbook(in);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            List<String> names = new ArrayList<>();
            for (int i = 0; i < wb.getNumberOfSheets(); i++) {
                names.add(wb.getSheetName(i));
            }
            assertFalse(names.isEmpty(), "报价模板一个 sheet 都没有 —— 模板损坏（来源 = git HEAD:" + tplRel + "）");
            System.out.println("[AC-120] 模板 sheet = " + names);

            Sheet material = wb.getSheet("物料");
            assertNotNull(material, "报价模板缺「物料」sheet，实际=" + names
                    + "\n  ⚠️ 模板结构变了，夹具假设失效 —— 停下来核对，不要猜。");

            for (String n : names) {
                Sheet s = wb.getSheet(n);
                if (s == material) {
                    continue;
                }
                clearDataRows(s);
            }

            int axisCol = headerIndex(material, "销售料号");
            assertTrue(axisCol >= 0, "「物料」sheet 表头里没有「销售料号」列");
            int prefixed = 0;
            for (int r = 1; r <= material.getLastRowNum(); r++) {
                Row row = material.getRow(r);
                if (row == null) {
                    continue;
                }
                Cell c = row.getCell(axisCol);
                if (c == null) {
                    continue;
                }
                String v = readString(c);
                if (v == null || v.isBlank()) {
                    continue;
                }
                c.setBlank(); // inlineStr 单元格必须先清空，否则写入静默失效（task-260902 踩过）
                c.setCellValue(axisPrefix + v);
                prefixed++;
            }
            System.out.println("[AC-120] 「物料」sheet 加前缀的轴值 = " + prefixed + " 个（前缀 " + axisPrefix + "）");
            assertTrue(prefixed > 0,
                    "AC-120: 模板「物料」sheet 一个数据行都没有 —— 灌不进数据，预览断言会空跑");

            wb.write(out);
            return out.toByteArray();
        }
    }

    /** 清空一个 sheet 的全部数据行（保留表头；带版本 sheet 的第 2 行是「轴/对比项」标记行，也保留）。 */
    private static void clearDataRows(Sheet s) {
        boolean versioned = isVersionedSheet(s);
        int first = versioned ? 2 : 1;
        for (int i = s.getLastRowNum(); i >= first; i--) {
            Row r = s.getRow(i);
            if (r != null) {
                s.removeRow(r);
            }
        }
    }

    /** 带版本 sheet 的第 2 行首列是「轴」标记 —— 运行期判别，🚫 不写死 sheet 名单。 */
    private static boolean isVersionedSheet(Sheet s) {
        Row r = s.getRow(1);
        if (r == null) {
            return false;
        }
        Cell c = r.getCell(0);
        return c != null && "轴".equals(readString(c));
    }

    private static int headerIndex(Sheet s, String header) {
        Row h = s.getRow(0);
        if (h == null) {
            return -1;
        }
        for (int c = 0; c < h.getLastCellNum(); c++) {
            Cell cell = h.getCell(c);
            if (cell != null && header.equals(readString(cell))) {
                return c;
            }
        }
        return -1;
    }

    private static String readString(Cell c) {
        return switch (c.getCellType()) {
            case STRING -> c.getStringCellValue();
            case NUMERIC -> {
                double d = c.getNumericCellValue();
                yield d == Math.floor(d) ? String.valueOf((long) d) : String.valueOf(d);
            }
            case BOOLEAN -> String.valueOf(c.getBooleanCellValue());
            case FORMULA -> c.getCellFormula();
            default -> "";
        };
    }

    private static String trunc(String s) {
        return s == null ? "null" : (s.length() > 1500 ? s.substring(0, 1500) + "…(截断)" : s);
    }
}
