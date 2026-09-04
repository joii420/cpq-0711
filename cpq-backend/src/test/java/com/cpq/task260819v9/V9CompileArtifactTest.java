package com.cpq.task260819v9;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 需求文档.md §9.4 <b>B 组 · 编译产物</b> —— AC-107 / AC-108 / AC-109 / AC-110 / AC-111 / AC-112。
 *
 * <p>层级 = 接口层（{@code POST /api/cpq/components/{cid}/builder/compile}，api.md §2.2）。
 * 断言全部打在<b>编译产物 SQL 文本 + declaredColumns</b> 上 —— 那正是 AC 原文的可观测对象。
 *
 * <h3>🚫 不写死节点 key / 表名</h3>
 * 种子由 B-42 <b>机器生成</b>（D-82），节点命名不由我定。所有 nodeKey / column 一律<b>运行期从
 * {@code semantic_tab_view} + {@code semantic_node} 发现</b>。发现不到就硬失败并注明「环境前置未就绪 = 未验证」。
 *
 * <h3>本用例会动的全局状态（testing.md §4.3 登记）</h3>
 * <b>只有一处</b>：{@code component} 表插入 1 行前缀为 {@code V9T-} 的测试组件（compile 端点需要一个 componentId）。
 * {@code @AfterAll} 用<b>正向条件</b> {@code WHERE code LIKE 'V9T-%'} 删除。
 * 🚫 不碰任何既有组件、不碰 {@code component_sql_view}（compile 不落库）。
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class V9CompileArtifactTest extends V9TestBase {

    private static String componentId;

    @BeforeEach
    void ensureComponent() {
        if (componentId != null) {
            return;
        }
        String id = UUID.randomUUID().toString();
        String code = TAG + "COMPILE-" + System.currentTimeMillis();
        exec("INSERT INTO component(id, name, code, column_count, fields, formulas, status, "
                        + "created_at, updated_at, component_type, bom_recursive_expand, excel_columns) "
                        + "VALUES (CAST(?1 AS uuid), ?2, ?3, 0, '[]'::jsonb, '[]'::jsonb, 'ACTIVE', "
                        + "NOW(), NOW(), 'NORMAL', false, '[]'::jsonb)",
                id, code, code);
        componentId = id;
        System.out.println("[V9] 测试组件 componentId=" + componentId + " code=" + code);
    }

    @AfterAll
    static void dropComponents() {
        // 正向条件删除，只删本套用例自建的行。🚫 无 WHERE 的 DELETE 是 CLAUDE.md §3.2 红线。
        io.quarkus.narayana.jta.QuarkusTransaction.requiringNew().run(() -> {
            var em = io.quarkus.arc.Arc.container()
                    .instance(jakarta.persistence.EntityManager.class).get();
            int n = em.createNativeQuery("DELETE FROM component WHERE code LIKE 'V9T-%'").executeUpdate();
            System.out.println("[V9] 清理自建测试组件 " + n + " 行（WHERE code LIKE 'V9T-%'）");
        });
    }

    // ═══════════════════════════════════════════════════════════════
    // AC-107（单点）不发 V6 收窄
    // AC 原文：用 COST_BASIC 编译「主件」→ 产物 SQL 不含 system_type、不含 customer_no
    // （🔄 D-84 后 is_current / versionFilter 应当出现，改由 AC-109 正向断言，本条不再管它们）
    // ═══════════════════════════════════════════════════════════════
    @Test
    @Order(107)
    @DisplayName("AC-107: COST_BASIC 编译「主件」，产物不含 system_type、不含 customer_no")
    void ac107_noV6ScopePredicates() {
        String sql = compileMainTab(COST_BASIC).sql;
        System.out.println("[AC-107] COST_BASIC 主件产物 SQL:\n" + sql);

        assertFalse(sql.toLowerCase().contains("system_type"),
                "AC-107: 产物不得含 system_type —— ds_* 45 张表没有这一列（B-41①）。SQL=\n" + sql);
        assertFalse(sql.toLowerCase().contains("customer_no"),
                "AC-107: 产物不得含 customer_no —— 仅 ds_quote_customer_part 有该列，而它不进图（N-19）。SQL=\n" + sql);
    }

    // ═══════════════════════════════════════════════════════════════
    // AC-108（单点 × 三方言）轴收窄
    // ═══════════════════════════════════════════════════════════════
    @Test
    @Order(108)
    @DisplayName("AC-108: 三方言编译「主件」，QUOTE→material_no=ANY(:total_material_no)，COST_*→production_no=ANY(...)")
    void ac108_axisNarrowingPerDialect() {
        Map<String, String> expectAxis = new LinkedHashMap<>();
        expectAxis.put(QUOTE, "material_no");
        expectAxis.put(COST_BASIC, "production_no");
        expectAxis.put(COST_DETAIL, "production_no");

        StringBuilder err = new StringBuilder();
        for (Map.Entry<String, String> e : expectAxis.entrySet()) {
            String sql = compileMainTab(e.getKey()).sql;
            String flat = flatten(sql);
            System.out.println("[AC-108] " + e.getKey() + " 主件产物:\n" + sql);

            // 断言形状：<别名>.<轴列> = ANY(:total_material_no)。别名不写死（由 AliasGenerator 决定）。
            String pattern = "(?i)[\\w\\.\"]*\\b" + e.getValue() + "\\b\\s*=\\s*ANY\\s*\\(\\s*:total_material_no\\s*\\)";
            if (!flat.matches(".*" + pattern + ".*")) {
                err.append("\n  ").append(e.getKey()).append(" 期望含 `")
                        .append(e.getValue()).append(" = ANY(:total_material_no)`，实际 SQL=\n").append(sql);
            }
            // 反向：不许发另一侧的轴列做收窄（三套轴语义不同，串了就是取错数据）
            String wrongAxis = "material_no".equals(e.getValue()) ? "production_no" : "material_no";
            String wrongPattern = "(?i)[\\w\\.\"]*\\b" + wrongAxis + "\\b\\s*=\\s*ANY\\s*\\(\\s*:total_material_no\\s*\\)";
            if (flat.matches(".*" + wrongPattern + ".*")) {
                err.append("\n  ").append(e.getKey()).append(" 不应该用 ").append(wrongAxis)
                        .append(" 做轴收窄（§9.1.1 轴列表）。SQL=\n").append(sql);
            }
        }
        assertEquals("", err.toString(), "AC-108 轴收窄不符：" + err);
    }

    // ═══════════════════════════════════════════════════════════════
    // AC-109（单点）版本切换产物（D-84 后断言已反转）
    // 三条：① FROM 源是 v_<主表>_all；② 含 :versionFilter(... version_no::text ...)；③ 输出 view_version
    // ⚠️ 必须挑一个「带版本」的页签 —— 主件（ds_cost_*_material）是免版本表，没有 _history 也没有全版本视图。
    // ═══════════════════════════════════════════════════════════════
    @Test
    @Order(109)
    @DisplayName("AC-109: COST_* 带版本页签的产物 FROM v_<主表>_all + :versionFilter(...version_no::text...) + 输出 view_version")
    void ac109_versionSwitchArtifact() {
        List<String> versionedMains = versionedCostMainTables();
        assertFalse(versionedMains.isEmpty(),
                notReady("AC-109", "库里没有任何带 _history 的 ds_cost_* 主表", "task-260902 V405~V408"));

        StringBuilder err = new StringBuilder();
        int probed = 0;
        for (String dialect : List.of(COST_BASIC, COST_DETAIL)) {
            Object[] picked = pickVersionedTab(dialect, versionedMains);
            if (picked == null) {
                err.append("\n  ").append(dialect)
                        .append(" 找不到任何锚点落在『带版本主表』上的页签视图 —— ")
                        .append("无法验 AC-109（环境前置未就绪，判『未验证』，不是产品缺陷）");
                continue;
            }
            String tabType = String.valueOf(picked[0]);
            String nodeKey = String.valueOf(picked[1]);
            String mainTable = String.valueOf(picked[2]);
            String nodeId = String.valueOf(picked[3]);
            probed++;

            Compiled c = compileTab(dialect, tabType, nodeKey, nodeId);
            String sql = c.sql;
            String flat = flatten(sql);
            System.out.println("[AC-109] " + dialect + " / " + tabType + " / 主表=" + mainTable + " 产物:\n" + sql);

            // ① FROM 源是 v_<主表>_all
            String viewName = "v_" + mainTable + "_all";
            if (!flat.toLowerCase().contains(viewName.toLowerCase())) {
                err.append("\n  ").append(dialect).append('/').append(tabType)
                        .append(" ①: 产物 FROM 源应是全版本视图 `").append(viewName)
                        .append("`，实际 SQL 里找不到它（S-31① / D-84）。SQL=\n").append(sql);
            }
            // 反向：不应再直接 FROM 主表（那样就取不到历史版本了）
            if (flat.matches("(?i).*\\bfrom\\s+" + mainTable + "\\b.*")) {
                err.append("\n  ").append(dialect).append('/').append(tabType)
                        .append(" ①反向: 产物仍直接 FROM 主表 `").append(mainTable)
                        .append("` —— 那样历史版本查不到，版本切换等于没接通。SQL=\n").append(sql);
            }

            // ② :versionFilter(alias.is_current, alias.version_no::text, alias.<轴列>)
            if (!flat.contains(":versionFilter")) {
                err.append("\n  ").append(dialect).append('/').append(tabType)
                        .append(" ②: 产物必须含 `:versionFilter(...)` 宏（S-31③）。SQL=\n").append(sql);
            } else if (!flat.matches("(?i).*:versionFilter\\s*\\([^)]*version_no::text[^)]*\\).*")) {
                err.append("\n  ").append(dialect).append('/').append(tabType)
                        .append(" ②: `:versionFilter(...)` 的版本列必须显式 `::text`（D-85）—— ")
                        .append("ds_*.version_no 是 integer，宏绑的是 :__vfVer::text[]，不转换会在真实执行时报 ")
                        .append("`operator does not exist: integer = text`（AC-126 专守此点）。SQL=\n").append(sql);
            }
            if (!flat.matches("(?i).*:versionFilter\\s*\\([^)]*is_current[^)]*\\).*")) {
                err.append("\n  ").append(dialect).append('/').append(tabType)
                        .append(" ②: `:versionFilter(...)` 第一个参数应是 `<别名>.is_current`（S-31③）。SQL=\n").append(sql);
            }

            // ③ 输出 view_version 约定列
            boolean inSql = flat.toLowerCase().contains("view_version");
            boolean inDeclared = c.declaredColumns.stream().anyMatch(s -> s != null && s.contains("view_version"));
            if (!inSql && !inDeclared) {
                err.append("\n  ").append(dialect).append('/').append(tabType)
                        .append(" ③: 产物必须输出 `view_version` 约定列（AC-109③）。declaredColumns=")
                        .append(c.declaredColumns).append(" SQL=\n").append(sql);
            }
        }
        assertTrue(probed > 0, notReady("AC-109",
                "两个 COST_* 方言都没找到带版本页签，三条断言一条都没执行（断言从未执行 = 假绿）",
                "cpq-backend #1 / B-44 + #2 / B-42"));
        assertEquals("", err.toString(), "AC-109 版本切换产物不符：" + err);
    }

    // ═══════════════════════════════════════════════════════════════
    // AC-110（单点 × 三方言）别名规则按侧不统一
    // QUOTE → _<短名>_<显示名>；两个 COST_* → 裸英文 dbColumn
    // ═══════════════════════════════════════════════════════════════
    @Test
    @Order(110)
    @DisplayName("AC-110: QUOTE 列别名 = _<短名>_<显示名>；COST_* 列别名 = 裸英文 dbColumn")
    void ac110_aliasRulePerDialect() {
        StringBuilder err = new StringBuilder();

        // ── COST_* 侧：裸英文
        for (String dialect : List.of(COST_BASIC, COST_DETAIL)) {
            Compiled c = compileMainTab(dialect);
            System.out.println("[AC-110] " + dialect + " declaredColumns=" + c.declaredColumns
                    + " （所选列 dbColumn=" + c.pickedDbColumn + "）");
            assertFalse(c.declaredColumns.isEmpty(),
                    "AC-110: " + dialect + " 的 declaredColumns 为空 —— 别名断言等于空跑");
            boolean hasBare = c.declaredColumns.contains(c.pickedDbColumn);
            if (!hasBare) {
                err.append("\n  ").append(dialect).append(": 应含裸英文列名 `").append(c.pickedDbColumn)
                        .append("`，实际 declaredColumns=").append(c.declaredColumns);
            }
            // 反向：核价侧不许出现报价侧的 _短名_显示名 形态
            for (String col : c.declaredColumns) {
                if (col != null && col.startsWith("_") && col.chars().filter(ch -> ch == '_').count() >= 2
                        && col.codePoints().anyMatch(cp -> cp > 0x4E00)) {
                    err.append("\n  ").append(dialect).append(": 不应出现报价侧 `_<短名>_<显示名>` 形态的别名 `")
                            .append(col).append("`（AC-110 明写按侧不统一，🚫 不要顺手统一）");
                }
            }
        }

        // ── QUOTE 侧：_<短名>_<显示名>
        Compiled q = compileMainTab(QUOTE);
        System.out.println("[AC-110] QUOTE declaredColumns=" + q.declaredColumns
                + " 短名=" + q.shortName + " 显示名=" + q.pickedDisplayName);
        assertFalse(q.declaredColumns.isEmpty(), "AC-110: QUOTE declaredColumns 为空 —— 断言等于空跑");
        assertNotNull(q.shortName, notReady("AC-110",
                "QUOTE 锚点节点的 short_name 为空，`_<短名>_<显示名>` 无从构成", "cpq-backend #2 / B-42"));
        String expected = "_" + q.shortName + "_" + q.pickedDisplayName;
        if (!q.declaredColumns.contains(expected)) {
            err.append("\n  QUOTE: 应含 `").append(expected)
                    .append("`（= _<短名>_<显示名>，D-13 纯函数），实际 declaredColumns=").append(q.declaredColumns);
        }

        assertEquals("", err.toString(), "AC-110 别名规则不符：" + err);
    }

    // ═══════════════════════════════════════════════════════════════
    // AC-111（单点）料号桥 —— 有对应行时 LEFT JOIN + production_no 连接键 + 执行非空
    // ═══════════════════════════════════════════════════════════════
    @Test
    @Order(111)
    @DisplayName("AC-111: COST_BASIC 场景引用销售料号 → 产物含对 ds_quote_material 的 LEFT JOIN（连接键 production_no），执行非空")
    void ac111_partNoBridge() {
        // ① 前置：紧邻取一次桥的重叠行（🚫 不照抄文档里的数字，它会漂移）
        List<Object[]> bridged = rowList(
                "SELECT q.material_no, q.production_no FROM ds_quote_material q "
                        + "JOIN ds_cost_basic_material m ON m.production_no = q.production_no "
                        + "WHERE q.production_no IS NOT NULL AND q.production_no <> '' ORDER BY 1");
        System.out.println("[AC-111] 紧邻取基准（库 cpq_db_0724）："
                + "SELECT ... FROM ds_quote_material q JOIN ds_cost_basic_material m ON m.production_no=q.production_no"
                + " → " + bridged.size() + " 行 " + fmt(bridged));
        assertFalse(bridged.isEmpty(), notReady("AC-111",
                "ds_quote_material 与 ds_cost_basic_material 在 production_no 上零重叠 —— "
                        + "『执行返回非空』这一条无从验证（业务上 production_no 为空是正常状态，见 B-43 说明）",
                "灌数据方（本任务 AC-120 会灌报价侧，或等 task-260902 侧补 production_no）"));
        String salesPartNo = String.valueOf(bridged.get(0)[0]);
        String prodNo = String.valueOf(bridged.get(0)[1]);

        // ② 编译：COST_BASIC 主件 + 一列取自 ds_quote_material（销售料号）
        Object[] bridgeNode = findNodeByTable("ds_quote_material");
        assertNotNull(bridgeNode, notReady("AC-111",
                "semantic_node 里没有 physical_table='ds_quote_material' 的 LOOKUP 节点（料号桥）",
                "cpq-backend #2 / B-43（S-24 / D-76）"));
        String bridgeKey = String.valueOf(bridgeNode[0]);

        Object[] anchor = anchorNode(COST_BASIC, "主件");
        assertNotNull(anchor, notReady("AC-111", "COST_BASIC『主件』页签视图不存在", "cpq-backend #2 / B-42"));
        String anchorKey = String.valueOf(anchor[0]);
        Object[] anchorCol = someColumnById(String.valueOf(anchor[3]));
        assertNotNull(anchorCol, notReady("AC-111", "锚点节点 " + anchorKey + " 无列声明", "cpq-backend #2 / B-42"));

        List<Map<String, Object>> cols = new ArrayList<>();
        cols.add(column(anchorKey, String.valueOf(anchorCol[0]), "锚点列"));
        cols.add(column(bridgeKey, "material_no", "销售料号"));

        Response r = compile(componentId, config(COST_BASIC, "主件", null, cols));
        assertEquals(200, r.statusCode(), "AC-111: compile 应 200，实际=" + r.statusCode() + " body=" + r.asString());
        String sql = r.jsonPath().getString("sql");
        assertNotNull(sql, "AC-111: compile 响应没有 sql 字段（api.md §2.2）。body=" + r.asString());
        String flat = flatten(sql);
        System.out.println("[AC-111] 产物 SQL:\n" + sql);

        assertTrue(flat.matches("(?i).*\\bleft\\s+(outer\\s+)?join\\s+ds_quote_material\\b.*"),
                "AC-111①: 产物必须含对 `ds_quote_material` 的 LEFT JOIN（S-24 硬要求：桥无对应行时 0 行不报错）。SQL=\n" + sql);
        assertTrue(flat.toLowerCase().contains("production_no"),
                "AC-111②: 桥的连接条件必须是 `production_no`（D-76）。SQL=\n" + sql);

        // ③ 执行非空 —— 用真实存在桥行的料号
        Map<String, Object> pv = new LinkedHashMap<>(config(COST_BASIC, "主件", null, cols));
        pv.put("partNo", prodNo);
        Response p = preview(componentId, pv);
        System.out.println("[AC-111] preview(partNo=" + prodNo + " 对应销售料号=" + salesPartNo + ") → HTTP "
                + p.statusCode() + " body=" + p.asString());
        assertEquals(200, p.statusCode(), "AC-111③: preview 应 200，实际=" + p.statusCode() + " body=" + p.asString());
        Integer rowCount = p.jsonPath().getObject("rowCount", Integer.class);
        assertNotNull(rowCount, "AC-111③: preview 响应缺 rowCount（api.md §2.3）。body=" + p.asString());
        assertTrue(rowCount > 0, "AC-111③: 桥有对应行时执行必须返回非空，实际 rowCount=" + rowCount
                + "\n  🚫 空列表 / 0 行 / 「—」一律不算通过。诊断=" + p.jsonPath().getString("diagnostics")
                + "\n  前置数据：ds_quote_material.material_no=" + salesPartNo + " → production_no=" + prodNo);
    }

    // ═══════════════════════════════════════════════════════════════
    // AC-112（边界）桥缺数据 → 0 行且不抛异常
    // ═══════════════════════════════════════════════════════════════
    @Test
    @Order(112)
    @DisplayName("AC-112: 桥无对应行 → 执行 0 行、不抛异常、诊断可读")
    void ac112_bridgeMissingRowYieldsZeroRowsNotError() {
        // 构造一个「保证桥上没有」的料号：库里查一次确认它真的不存在（不是我以为不存在）
        String ghost = TAG + "NO-BRIDGE-" + System.currentTimeMillis();
        long inQuote = scalarLong("SELECT count(*) FROM ds_quote_material WHERE material_no=?1 OR production_no=?1", ghost);
        long inCost = scalarLong("SELECT count(*) FROM ds_cost_basic_material WHERE production_no=?1", ghost);
        System.out.println("[AC-112] 幽灵料号 " + ghost + " 在 ds_quote_material 命中=" + inQuote
                + "，在 ds_cost_basic_material 命中=" + inCost);
        assertEquals(0L, inQuote + inCost, "AC-112 前置：幽灵料号必须在两侧都不存在，否则这条边界用例没意义");

        Object[] bridgeNode = findNodeByTable("ds_quote_material");
        assertNotNull(bridgeNode, notReady("AC-112", "料号桥节点不存在", "cpq-backend #2 / B-43"));
        Object[] anchor = anchorNode(COST_BASIC, "主件");
        assertNotNull(anchor, notReady("AC-112", "COST_BASIC『主件』页签视图不存在", "cpq-backend #2 / B-42"));
        Object[] anchorCol = someColumnById(String.valueOf(anchor[3]));
        assertNotNull(anchorCol, notReady("AC-112", "锚点节点无列声明", "cpq-backend #2 / B-42"));

        List<Map<String, Object>> cols = new ArrayList<>();
        cols.add(column(String.valueOf(anchor[0]), String.valueOf(anchorCol[0]), "锚点列"));
        cols.add(column(String.valueOf(bridgeNode[0]), "material_no", "销售料号"));

        Map<String, Object> pv = new LinkedHashMap<>(config(COST_BASIC, "主件", null, cols));
        pv.put("partNo", ghost);
        Response p = preview(componentId, pv);
        System.out.println("[AC-112] preview(partNo=" + ghost + ") → HTTP " + p.statusCode() + " body=" + p.asString());

        assertEquals(200, p.statusCode(),
                "AC-112: 桥缺数据必须是『0 行且不抛异常』，不是错误响应。实际 HTTP=" + p.statusCode()
                        + " body=" + p.asString()
                        + "\n  ⚠️ 尤其不能是 500 —— api.md §2.5 明写 500 不允许。");
        Integer rowCount = p.jsonPath().getObject("rowCount", Integer.class);
        assertNotNull(rowCount, "AC-112: 响应缺 rowCount。body=" + p.asString());
        assertEquals(0, rowCount.intValue(), "AC-112: 桥无对应行时应返回 0 行，实际=" + rowCount
                + "\n  📌 若这里返回了非 0 行，说明桥被编译成了保留左表的形态而轴收窄没落到桥的结果上 —— "
                + "请主线核对 AC-112 的语义读法（LEFT JOIN 本身是保留行的，AC 要的 0 行只有在"
                + "『轴收窄依赖桥映射出的 production_no』这一读法下才成立）。");

        List<?> diagnostics = p.jsonPath().getList("diagnostics");
        assertNotNull(diagnostics, "AC-112: 0 行时必须给 diagnostics（api.md §2.3：🚫 不许只返回空表格）。body=" + p.asString());
        assertFalse(diagnostics.isEmpty(),
                "AC-112: 0 行时 diagnostics 不得为空 —— 必须给『哪一层收窄把行滤没了』的可操作诊断。body=" + p.asString());
        System.out.println("[AC-112] 诊断信息 = " + diagnostics);
    }

    // ═══════════════════════════════════════════════════════════════
    // 契约 v9-1（边界）—— 非 AC，但它是 AC-107 / AC-108 可信的前提
    //
    // api.md v9-1 明写：显式传了非三值之一（含旧值 "COSTING"）→ 400，且错误点名收到的是什么、合法值有哪些；
    // 不传 dialect → 缺省 QUOTE。
    // 🔑 为什么这条必须验：静默回落时 AC-107 / AC-108 **会照样通过** ——
    //    它们只断言产物里没有 system_type / customer_no，不断言「方言选对了」。
    //    也就是说，缺了这条，AC-107/108 的绿盖不住「用户选了基础核价、后端按报价编译」这个失败模式。
    // ═══════════════════════════════════════════════════════════════
    @Test
    @Order(1071)
    @DisplayName("契约 v9-1（边界）: 未知方言必须 400 且点名收到值与合法值；不传 dialect 缺省 QUOTE，不得静默回落")
    void contractV91_unknownDialectMustFailLoudly() {
        Object[] anchor = anchorNode(QUOTE, "主件");
        assertNotNull(anchor, notReady("契约 v9-1",
                "QUOTE『主件』页签视图不存在，无从构造请求", "cpq-backend #2 / B-42"));
        Object[] col = someColumnById(String.valueOf(anchor[3]));
        assertNotNull(col, notReady("契约 v9-1", "锚点节点无列声明", "cpq-backend #2 / B-42"));
        List<Map<String, Object>> cols =
                List.of(column(String.valueOf(anchor[0]), String.valueOf(col[0]), "测试字段"));

        // ① 旧值 COSTING —— 已随 V6 整块作废，必须被拒（留着容错等于给「用错方言」开后门）
        for (String badDialect : List.of("COSTING", "COST_BASIC_TYPO", "quote")) {
            Response r = compile(componentId, config(badDialect, "主件", null, cols));
            String body = r.asString();
            System.out.println("[契约 v9-1] dialect=" + badDialect + " → HTTP " + r.statusCode()
                    + " body=" + body);
            assertEquals(400, r.statusCode(),
                    "契约 v9-1: 显式传非三值之一的方言 `" + badDialect + "` 必须 400，实际=" + r.statusCode()
                            + "\n  🚨 静默回落 QUOTE 的后果：用户选了「基础核价」，后端按报价侧编译，全程不报错。"
                            + "\n  body=" + body);
            assertTrue(body.contains(badDialect),
                    "契约 v9-1: 错误信息必须点名『收到的是什么』（`" + badDialect + "`）。body=" + body);
            assertTrue(body.contains(COST_BASIC) && body.contains(COST_DETAIL) && body.contains(QUOTE),
                    "契约 v9-1: 错误信息必须列出『合法值有哪些』（三个方言名）。body=" + body);
        }

        // ② 不传 dialect → 缺省 QUOTE（向后兼容，存量手写视图不受影响）
        Map<String, Object> noDialect = new LinkedHashMap<>(config(QUOTE, "主件", null, cols));
        noDialect.remove(DIALECT_FIELD);
        Response r2 = compile(componentId, noDialect);
        System.out.println("[契约 v9-1] 不传 dialect → HTTP " + r2.statusCode());
        assertEquals(200, r2.statusCode(),
                "契约 v9-1: 不传 dialect 应缺省 QUOTE 并正常编译（200），实际=" + r2.statusCode()
                        + " body=" + r2.asString());
        String sqlDefault = r2.jsonPath().getString("sql");
        assertNotNull(sqlDefault, "契约 v9-1: 缺省编译响应缺 sql 字段。body=" + r2.asString());
        assertTrue(flatten(sqlDefault).matches(
                        "(?i).*[\\w\\.\"]*\\bmaterial_no\\b\\s*=\\s*ANY\\s*\\(\\s*:total_material_no\\s*\\).*"),
                "契约 v9-1: 缺省应按 QUOTE 编译（轴收窄用 material_no），实际 SQL=\n" + sqlDefault);
    }

    // ═══════════════════════════════════════════════════════════════
    // 辅助：编译 + 发现
    // ═══════════════════════════════════════════════════════════════

    private static final class Compiled {
        String sql;
        List<String> declaredColumns = new ArrayList<>();
        String pickedDbColumn;
        String pickedDisplayName;
        String shortName;
    }

    /** 用指定方言编译「主件」页签，取锚点节点的一列。 */
    private Compiled compileMainTab(String dialect) {
        Object[] anchor = anchorNode(dialect, "主件");
        assertNotNull(anchor, notReady("AC-107/108/110",
                dialect + " 的『主件』页签视图不存在于 semantic_tab_view", "cpq-backend #2 / B-42"));
        return compileTab(dialect, "主件", String.valueOf(anchor[0]), String.valueOf(anchor[3]));
    }

    private Compiled compileTab(String dialect, String tabType, String nodeKey, String nodeId) {
        // 🚨 按 node id 取列 —— node_key 跨方言重名（api.md v9-2），按 key 取会串到别的数据集
        Object[] col = someColumnById(nodeId);
        assertNotNull(col, notReady("AC-107~110",
                "节点 " + nodeKey + "(id=" + nodeId + ") 在 semantic_node_column 里一条列声明都没有",
                "cpq-backend #2 / B-42"));
        String shortName = shortNameOf(dialect, nodeKey);

        List<Map<String, Object>> cols = List.of(column(nodeKey, String.valueOf(col[0]), "测试字段"));
        Response r = compile(componentId, config(dialect, tabType, null, cols));
        assertEquals(200, r.statusCode(),
                "compile(" + dialect + "/" + tabType + ") 应 200，实际=" + r.statusCode()
                        + "\n  body=" + r.asString()
                        + "\n  ⚠️ 若报的是「方言不认识」，请核对请求体字段名 —— 本套用例用的是 `"
                        + DIALECT_FIELD + "`（V9TestBase.DIALECT_FIELD，契约缺口已报主线：api.md 未按 v9 更新）。");
        Compiled c = new Compiled();
        c.sql = r.jsonPath().getString("sql");
        assertNotNull(c.sql, "compile 响应缺 sql 字段（api.md §2.2）。body=" + r.asString());
        List<String> dc = r.jsonPath().getList("declaredColumns", String.class);
        if (dc != null) {
            c.declaredColumns = dc;
        }
        c.pickedDbColumn = String.valueOf(col[0]);
        c.pickedDisplayName = col[1] == null ? null : String.valueOf(col[1]);
        c.shortName = shortName;
        return c;
    }

    /** 找一个锚点落在「带版本主表」上的页签视图，返回 {@code (tab_type, node_key, main_table, node_id)}。 */
    private Object[] pickVersionedTab(String dialect, List<String> versionedMains) {
        List<Object[]> tabs = rowList(
                "SELECT v.tab_type, n.node_key, n.physical_table, n.id::text FROM semantic_tab_view v "
                        + "JOIN semantic_node n ON n.id = v.anchor_node_id "
                        + "WHERE v.dialect=?1 AND v.status='ACTIVE' ORDER BY v.tab_type", dialect);
        for (Object[] t : tabs) {
            String main = normalizeToMainTable(t[2] == null ? null : String.valueOf(t[2]));
            if (main != null && versionedMains.contains(main)) {
                return new Object[]{String.valueOf(t[0]), String.valueOf(t[1]), main, String.valueOf(t[3])};
            }
        }
        return null;
    }

    private Object[] findNodeByTable(String table) {
        List<Object[]> r = rowList(
                "SELECT node_key, physical_table FROM semantic_node "
                        + "WHERE status='ACTIVE' AND (physical_table=?1 OR physical_table=?2) LIMIT 1",
                table, "v_" + table + "_all");
        return r.isEmpty() ? null : r.get(0);
    }

    /** 折行 + 压缩空白，便于用正则匹配 SQL 形状（🚫 不改变语义，只影响匹配）。 */
    static String flatten(String sql) {
        return sql == null ? "" : sql.replaceAll("\\s+", " ").trim();
    }

    private static String fmt(List<Object[]> rows) {
        List<String> l = new ArrayList<>();
        for (Object[] r : rows) {
            l.add(java.util.Arrays.toString(r));
        }
        return l.toString();
    }
}
