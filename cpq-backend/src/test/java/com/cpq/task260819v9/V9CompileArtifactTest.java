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
    //
    // 🔄 2026-09-08（repair-260908 B-7 / D-9）：customer_no 那半条断言**已收窄**，理由一并更正。
    //
    // 原理由写的是「仅 ds_quote_customer_part 有该列，而它不进图（N-19）」——
    // 🚫 **这条理由已被 V425 推翻**：task-260907 给 28 张 ds_quote_* 普遍加了 customer_no，
    // 复合轴变成 (customer_no, material_no)。留着过期理由比留着过期断言更危险：
    // 下一个人会照它再做一次「ds_quote_* 没有客户列」的错判断。
    //
    // 现在的正确口径（repair-260908 AC-16，判据是**物理表有没有这一列**，不是方言）：
    //   · **外层锚点** ds_cost_basic_* —— 逐表实测 55 张 ds_cost_* 的 customer_no 列数 = 0
    //     ⇒ 仍然一条客户谓词都不许有，这半条断言原样保留；
    //   · **NARROW 桥的子查询** —— 它 FROM 的是 ds_quote_material（有 customer_no 且此前从不过滤），
    //     缺陷①在核价侧正是以这种桥接形态存在的 ⇒ 桥子查询里出现 customer_no 是**要求**，不是违规。
    // ⇒ 断言从「整段文本不含」收窄为「**剔掉桥子查询之后**不含」。
    // ═══════════════════════════════════════════════════════════════

    /**
     * 桥半连接子查询的形态：{@code IN (SELECT … )}，用于把它整段从产物里剔掉。
     *
     * <p>🚨 <b>必须允许一层嵌套括号</b>（{@code [^()]*(\(...\)[^()]*)*}）：子查询里含
     * {@code = ANY(:total_material_no)}。若照抄 {@code SemanticCompiler.BRIDGE_SEMI_JOIN} 的
     * 「禁止括号」写法，正则会在 {@code ANY(} 处走不下去 ⇒ <b>一处都匹不到</b> ⇒ 剔除等于没剔，
     * 断言退回「整段文本不含 customer_no」的老口径（2026-09-08 首跑实测踩到）。
     * 那边禁止括号是为了把匹配锁死在同一层、避免假报警；这边要的是整段剔除，目标不同。
     */
    private static final java.util.regex.Pattern BRIDGE_SUBQUERY = java.util.regex.Pattern.compile(
            "\\bIN\\s*\\(\\s*SELECT\\b[^()]*(?:\\([^()]*\\)[^()]*)*\\)",
            java.util.regex.Pattern.CASE_INSENSITIVE);

    @Test
    @Order(107)
    @DisplayName("AC-107: COST_BASIC 编译「主件」，外层锚点不含 system_type / customer_no（桥子查询里含客户谓词属正确）")
    void ac107_noV6ScopePredicates() {
        String sql = compileMainTab(COST_BASIC).sql;
        System.out.println("[AC-107] COST_BASIC 主件产物 SQL:\n" + sql);

        assertFalse(sql.toLowerCase().contains("system_type"),
                "AC-107: 产物不得含 system_type —— ds_* 45 张表没有这一列（B-41①）。SQL=\n" + sql);

        // 剔掉桥子查询，剩下的就是「外层锚点自己的部分」
        String outer = BRIDGE_SUBQUERY.matcher(sql).replaceAll(" IN (<桥子查询已剔除>)");
        assertFalse(outer.toLowerCase().contains("customer_no"),
                "AC-107: **外层锚点**不得含 customer_no —— ds_cost_* 55 张表逐表实测该列数 = 0，"
                        + "出现它说明谓词加错了位置（判据应是列存在性，不是方言）。"
                        + "\n剔除桥子查询后的外层=\n" + outer + "\n完整 SQL=\n" + sql);

        // 🔑 正向那一半（repair-260908 AC-16 结构断言）：桥确实在、且桥里确实带了客户谓词。
        //    没有这一句，上面的「剔除后不含」在**桥整个消失**时也会绿 —— 那是最典型的空跑。
        assertTrue(BRIDGE_SUBQUERY.matcher(sql).find(),
                "AC-107 前置：COST_BASIC 主件产物里找不到 NARROW 桥子查询 ⇒ 上面的『剔除后不含 customer_no』"
                        + "会变成恒真的空断言。SQL=\n" + sql);
        assertTrue(sql.contains("customer_no = :customerCode"),
                "repair-260908 AC-16: 桥子查询必须带 customer_no = :customerCode —— 桥 FROM 的 "
                        + "ds_quote_material 有该列且此前从不过滤，缺陷①在核价侧就是以这种形态存在的。SQL=\n" + sql);
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

            // 🔴 AC-108 原文（「COST_* → production_no = ANY(:total_material_no)」）**未随 D-110 同步**。
            //    D-110 把桥改成「输入收窄」后，COST_* 的实际产物是：
            //      dcbm.production_no IN (SELECT dqm.production_no FROM ds_quote_material dqm
            //                             WHERE dqm.material_no = ANY(:total_material_no))
            //    ⇒ 字面的 `production_no = ANY(:total_material_no)` 不再出现，
            //      且 `material_no = ANY(...)`**合法地**出现在桥子查询里（原反向断言会误伤）。
            //    这里断言两种读法都认可的**落点不变量**，并把「AC 原文待同步」写进失败信息报主线。
            String axis = e.getValue();
            boolean literalAny = flat.matches(
                    "(?i).*[\\w\\.\"]*\\b" + axis + "\\b\\s*=\\s*ANY\\s*\\(\\s*:total_material_no\\s*\\).*");
            boolean bridgedIn = QUOTE.equals(e.getKey()) ? false
                    : flat.matches("(?i).*[\\w\\.\"]*\\b" + axis
                            + "\\b\\s+IN\\s*\\(\\s*SELECT[^)]*ds_quote_material[^)]*:total_material_no.*");
            if (!literalAny && !bridgedIn) {
                err.append("\n  ").append(e.getKey()).append(" 轴收窄没落在 `").append(axis)
                        .append("` 上（既不是 `= ANY(:total_material_no)`，也不是经 ds_quote_material 桥的 `IN (SELECT …)`）。SQL=\n")
                        .append(sql);
            } else {
                System.out.println("[AC-108] " + e.getKey() + " 收窄形态 = "
                        + (literalAny ? "字面 = ANY(:total_material_no)" : "经桥 IN (SELECT … FROM ds_quote_material …)（D-110）"));
            }
            // 反向：QUOTE 侧不许出现生产料号收窄（报价侧没有桥，串了就是取错数据）
            if (QUOTE.equals(e.getKey()) && flat.matches(
                    "(?i).*[\\w\\.\"]*\\bproduction_no\\b\\s*=\\s*ANY\\s*\\(\\s*:total_material_no\\s*\\).*")) {
                err.append("\n  QUOTE 不应该用 production_no 做轴收窄（§9.1.1 轴列表）。SQL=\n").append(sql);
            }
        }
        assertEquals("", err.toString(), "AC-108 轴收窄不符：" + err
                + "\n  🔴 提醒主线：AC-108 原文仍写「COST_* → production_no = ANY(:total_material_no)」，"
                + "**未随 D-110（桥改输入收窄）同步** —— 与 AC-113③ 未随 D-107 同步是同一类问题。"
                + "\n     本用例按 D-110 的实际形态断言落点不变量，请主线同步 AC-108 措辞。");
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
    // AC-111（单点）料号桥 = 输入收窄（🔄🔄 D-110 重写，桥不再是输出 LOOKUP）
    // ① 产物不含对 ds_quote_material 的 LEFT JOIN
    // ② WHERE 里含经桥解析的收窄（锚点.production_no IN (SELECT … FROM ds_quote_material WHERE material_no = ANY(...)))
    // ③ 传销售料号 S 执行 → 返回 P 对应的行，非空
    // ═══════════════════════════════════════════════════════════════
    @Test
    @Order(111)
    @DisplayName("AC-111: 桥是输入收窄 —— 产物无 LEFT JOIN ds_quote_material，WHERE 含桥子查询；传销售料号执行非空")
    void ac111_partNoBridgeAsInputNarrowing() {
        Object[] pair = pickSalesToProduction();
        assertNotNull(pair, notReady("AC-111",
                "找不到「销售料号 S → 生产料号 P 且 P 在 ds_cost_basic_material 里」的映射", "灌数据方"));
        String salesNo = String.valueOf(pair[0]);
        String prodNo = String.valueOf(pair[1]);
        System.out.println("[AC-111] 紧邻取基准（库 cpq_db_0724）：销售料号 " + salesNo + " → 生产料号 " + prodNo);

        Compiled c = compileMainTab(COST_BASIC);
        String flat = flatten(c.sql);
        System.out.println("[AC-111] COST_BASIC 主件产物 SQL:\n" + c.sql);

        // ① 🚫 不得再有 LEFT JOIN ds_quote_material（那是被 D-110 推翻的老形态，会扇出）
        assertFalse(flat.matches("(?i).*\\bleft\\s+(outer\\s+)?join\\s+ds_quote_material\\b.*"),
                "AC-111①: 桥已改为『输入收窄』，产物**不得**再含对 ds_quote_material 的 LEFT JOIN（D-110）。"
                        + "\n  老形态会让「一个生产料号对应多个销售料号」时行数翻倍 —— 那正是 AC-112① 要守的。SQL=\n" + c.sql);

        // ② WHERE 里要有经桥解析的收窄：既提到 ds_quote_material，又落在轴列上
        assertTrue(flat.toLowerCase().contains("ds_quote_material"),
                "AC-111②: 产物必须经 ds_quote_material 解析销售料号→生产料号（D-76 料号桥）。SQL=\n" + c.sql);
        assertTrue(flat.matches("(?i).*\\bproduction_no\\b.*ds_quote_material.*")
                        || flat.matches("(?i).*ds_quote_material.*\\bproduction_no\\b.*"),
                "AC-111②: 桥的收窄必须落在 production_no 上。SQL=\n" + c.sql);
        assertTrue(flat.contains(":total_material_no"),
                "AC-111②: 收窄的入参应是 :total_material_no（传进来的是销售料号）。SQL=\n" + c.sql);

        // ③ 传销售料号执行 → 非空，且行确实落在 P 上
        Map<String, Object> pv = new LinkedHashMap<>(configForMainTab(COST_BASIC));
        pv.put("partNo", salesNo);
        // repair-260908 B-7：产物含 :customerCode ⇒ 预览必须带客户（见 customerOfSales 注释）
        String custCode = customerOfSales(salesNo);
        assertNotNull(custCode, notReady("AC-111③",
                "销售料号 " + salesNo + " 在 ds_quote_material 上没有 customer_no —— "
                        + "带客户谓词的产物无从预览", "灌数据方 / BL-0226"));
        pv.put("customerCode", custCode);
        Response p = preview(componentId, pv);
        System.out.println("[AC-111③] preview(partNo=" + salesNo + " 销售料号, customerCode=" + custCode
                + ") → HTTP " + p.statusCode()
                + " body=" + trunc(p.asString()));
        assertEquals(200, p.statusCode(), "AC-111③: preview 应 200，body=" + p.asString());
        Integer rc = p.jsonPath().getObject("rowCount", Integer.class);
        assertNotNull(rc, "AC-111③: 响应缺 rowCount。body=" + p.asString());
        assertTrue(rc > 0, "AC-111③: 传销售料号 " + salesNo + " 应返回 P=" + prodNo
                + " 对应的行，实际 rowCount=" + rc + "。🚫 0 行不算通过。诊断=" + p.jsonPath().getString("diagnostics"));

        List<Map<String, Object>> rows = p.jsonPath().getList("rows");
        assertNotNull(rows, "AC-111③: 响应缺 rows");
        assertFalse(rows.isEmpty(), "AC-111③: rowCount=" + rc + " 但 rows 为空 —— 断言空跑");
        boolean allOnP = rows.stream().allMatch(r0 -> prodNo.equals(String.valueOf(r0.get("production_no"))));
        assertTrue(allOnP, "AC-111③: 返回的行应全部落在生产料号 " + prodNo + " 上，实际 rows=" + rows);
    }

    // ═══════════════════════════════════════════════════════════════
    // AC-112①（🔄🔄 D-110 重写）不扇出：一个生产料号对应多个销售料号时，行数与不带桥相同
    // ═══════════════════════════════════════════════════════════════
    @Test
    @Order(1121)
    @DisplayName("AC-112①: 一个生产料号↔多个销售料号 → 核价侧行数与不带桥时相同，🚫 不翻倍")
    void ac112a_bridgeMustNotFanOut() {
        // 紧邻找「一个生产料号对应 ≥2 个销售料号」的轴值（🚫 不写死 TEST0813-P01-PROD）
        List<String> multi = strList(
                "SELECT production_no FROM ds_quote_material "
                        + "WHERE production_no IS NOT NULL AND production_no <> '' "
                        + "GROUP BY production_no HAVING count(DISTINCT material_no) > 1 ORDER BY 1");
        System.out.println("[AC-112①] 紧邻取基准：一个生产料号对应多个销售料号的轴值 = " + multi);
        assertFalse(multi.isEmpty(), notReady("AC-112①",
                "ds_quote_material 里没有『一个生产料号对应多个销售料号』的数据 —— 扇出无从构造，"
                        + "这条 AC 的全部意义就在这种数据上", "主线（V9T-FIXTURE 夹具）"));
        String prodNo = multi.get(0);

        List<String> salesNos = strList(
                "SELECT material_no FROM ds_quote_material WHERE production_no=?1 ORDER BY 1", prodNo);
        assertTrue(salesNos.size() >= 2,
                "AC-112① 前置：该生产料号应有 ≥2 个销售料号，实际=" + salesNos);
        System.out.println("[AC-112①] 生产料号 " + prodNo + " ← 销售料号 " + salesNos);

        // 挑一个锚点落在多行表上的页签（1 行的表扇出只从 1→2，证据力弱；夹具在 _bom 上有 3 行）
        Object[] tab = pickTabByMainTable(COST_BASIC, "ds_cost_basic_material_bom");
        assertNotNull(tab, notReady("AC-112①",
                "COST_BASIC 下找不到锚点为 ds_cost_basic_material_bom 的页签视图", "cpq-backend #2 / B-42"));
        String tabType = String.valueOf(tab[0]);
        String nodeKey = String.valueOf(tab[1]);
        String nodeId = String.valueOf(tab[3]);

        // 不带桥的基准：直接查主表（当前版本）
        long baseline = scalarLong(
                "SELECT count(*) FROM ds_cost_basic_material_bom WHERE production_no=?1", prodNo);
        System.out.println("[AC-112①] 紧邻取基准（不带桥）：SELECT count(*) FROM ds_cost_basic_material_bom "
                + "WHERE production_no='" + prodNo + "' → " + baseline + " 行");
        assertTrue(baseline > 0, notReady("AC-112①",
                "该生产料号在 ds_cost_basic_material_bom 上 0 行 —— 『不翻倍』会退化成 0==0 的假通过",
                "主线（V9T-FIXTURE 夹具）"));

        Object[] col = someColumnById(nodeId);
        assertNotNull(col, notReady("AC-112①", "节点 " + nodeKey + " 无列声明", "cpq-backend #2 / B-42"));
        Map<String, Object> cfg = config(COST_BASIC, tabType, null,
                List.of(column(nodeKey, String.valueOf(col[0]), "测试字段")));

        // 🔄 task-260907 B-3（用户 2026-09-07 裁决）：本页签**可能是 BOM 树页签**。
        //   pickTabByMainTable 按 tab_type 排序取第一个，'BOM' 恰好排在最前 ⇒ 实际命中的就是树页签。
        //   树页签的行集语义已经变了：从「父件 = 本料号的边」改成「**子件**属于本单闭包的边 + 根分支」，
        //   而上面的 baseline 是按 `WHERE production_no=?`（**父件**口径）数出来的 ⇒ 两者天然不等。
        //   🚫 **不能因此把 baseline 断言删掉** —— 它在非树页签上仍然是 D-110 的主判据。
        //   ⇒ 拆成两层：
        //     · 第 1 层（**所有页签都查**）= AC-112① 真正的不变量：N 个销售料号各自的行数**必须彼此相同**。
        //       扇出的特征就是「行数随销售料号个数放大」，这一层足以证伪它，且与页签语义无关。
        //     · 第 2 层（**仅非树页签**）= 原来的强判据：行数还必须等于不带桥的基准。
        boolean treeTab = com.cpq.component.service.TabSemanticResolver.SEMANTIC_TREE.equals(
                com.cpq.component.service.TabSemanticResolver.semanticOfGraphTabType(tabType));
        if (treeTab) {
            System.out.println("[AC-112①] 命中的是 BOM 树页签（task-260907 B-3 后行集口径 = 子件在本单闭包 + 根分支），"
                    + "不带桥基准 " + baseline + " 是父件口径、不可比 ⇒ 本轮只查『各销售料号行数彼此相同』这一层");
        }

        StringBuilder err = new StringBuilder();
        Map<String, Integer> rcBySales = new LinkedHashMap<>();
        for (String salesNo : salesNos) {
            Map<String, Object> pv = new LinkedHashMap<>(cfg);
            pv.put("partNo", salesNo);
            // repair-260908 B-7：逐个销售料号取**它自己的**客户号，🚫 不写死（复合轴 (customer_no, material_no)）
            pv.put("customerCode", customerOfSales(salesNo));
            Response p = preview(componentId, pv);
            System.out.println("[AC-112①] preview(销售料号=" + salesNo + ", 页签=" + tabType + ") → HTTP "
                    + p.statusCode() + " rowCount=" + p.jsonPath().getObject("rowCount", Integer.class));
            if (p.statusCode() != 200) {
                err.append("\n  ").append(salesNo).append(": HTTP ").append(p.statusCode())
                        .append(" body=").append(p.asString());
                continue;
            }
            Integer rc = p.jsonPath().getObject("rowCount", Integer.class);
            if (rc == null) {
                err.append("\n  ").append(salesNo).append(": 响应缺 rowCount");
                continue;
            }
            rcBySales.put(salesNo, rc);
            if (!treeTab && rc != baseline) {
                err.append("\n  ").append(salesNo).append(": 行数=").append(rc)
                        .append("，与不带桥的基准 ").append(baseline).append(" 不同");
                if (rc == baseline * salesNos.size()) {
                    err.append("  🚨 恰好 = 基准 × 销售料号个数(").append(salesNos.size())
                            .append(") ⇒ **这就是桥扇出**（老 LEFT JOIN 形态的特征），D-110 要消灭的正是它");
                }
            }
        }

        // 第 1 层：各销售料号行数必须彼此相同（扇出的直接判据，树/非树都查）
        long distinctRc = rcBySales.values().stream().distinct().count();
        if (distinctRc > 1) {
            err.append("\n  🚨 各销售料号的行数彼此不同 ").append(rcBySales)
                    .append(" ⇒ 行数随销售料号变化，**这就是桥扇出**（D-110 要消灭的正是它）");
        }
        // 🚨 全 0 时「彼此相同」恒成立 ⇒ 会退化成假通过，必须单独挡掉
        boolean allZero = !rcBySales.isEmpty() && rcBySales.values().stream().allMatch(v -> v == 0);
        assertFalse(allZero, notReady("AC-112①",
                "全部销售料号预览都是 0 行 —— 『行数彼此相同』退化成 0==0 的假通过，本条判定为【未验证】",
                "主线（V9T-FIXTURE 夹具）/ task-260907 B-7 递归换表"));

        assertEquals("", err.toString(),
                "AC-112①: 桥是输入收窄，一个生产料号对应多个销售料号时行数**不得随销售料号个数放大**"
                        + (treeTab ? "（本轮命中树页签，仅查『彼此相同』层）"
                                   : "，且必须与不带桥相同（基准=" + baseline + "）")
                        + "：" + err);
        System.out.println("[AC-112① ✅] 生产料号 " + prodNo + " 的 " + salesNos.size()
                + " 个销售料号各自预览行数 " + rcBySales + " —— 未扇出（老形态会是 "
                + (baseline * salesNos.size()) + " 行）");
    }

    // ═══════════════════════════════════════════════════════════════
    // AC-112②（边界）传一个 ds_quote_material 里不存在的销售料号 → 0 行、不抛异常
    // ═══════════════════════════════════════════════════════════════
    @Test
    @Order(1122)
    @DisplayName("AC-112②: 销售料号在 ds_quote_material 不存在 → 收窄生效，0 行、不抛异常、诊断可读")
    void ac112b_unknownSalesPartNoYieldsZeroRows() {
        String ghost = TAG + "NO-SALES-" + System.currentTimeMillis();
        long inQuote = scalarLong("SELECT count(*) FROM ds_quote_material WHERE material_no=?1", ghost);
        System.out.println("[AC-112②] 幽灵销售料号 " + ghost + " 在 ds_quote_material 命中=" + inQuote);
        assertEquals(0L, inQuote, "AC-112② 前置：幽灵销售料号必须真的不存在，否则边界用例没意义");

        Map<String, Object> pv = new LinkedHashMap<>(configForMainTab(COST_BASIC));
        pv.put("partNo", ghost);
        // repair-260908 B-7：幽灵料号当然查不到自己的客户，这里取库里任一真实客户号即可 ——
        // 本用例要证的是「料号不存在 ⇒ 0 行且不抛异常」，客户号只需让 bindLiterals 有东西可替换。
        List<String> anyCust = strList("SELECT customer_no FROM ds_quote_material "
                + "WHERE customer_no IS NOT NULL ORDER BY 1 LIMIT 1");
        assertFalse(anyCust.isEmpty(), notReady("AC-112②",
                "ds_quote_material 里一个 customer_no 都没有 —— 带客户谓词的产物无从预览", "灌数据方"));
        pv.put("customerCode", anyCust.get(0));
        Response p = preview(componentId, pv);
        System.out.println("[AC-112②] preview(partNo=" + ghost + ") → HTTP " + p.statusCode()
                + " body=" + trunc(p.asString()));

        assertEquals(200, p.statusCode(),
                "AC-112②: 销售料号不存在必须是『0 行且不抛异常』。实际 HTTP=" + p.statusCode()
                        + " body=" + p.asString() + "\n  ⚠️ 尤其不能是 500（api.md §2.5）。");
        Integer rc = p.jsonPath().getObject("rowCount", Integer.class);
        assertNotNull(rc, "AC-112②: 响应缺 rowCount。body=" + p.asString());
        assertEquals(0, rc.intValue(),
                "AC-112②: 桥收窄应把不存在的销售料号解析成空集 ⇒ 0 行，实际=" + rc
                        + "\n  📌 非 0 说明收窄没生效（桥被跳过或退化成全表）。");
        List<?> diagnostics = p.jsonPath().getList("diagnostics");
        assertNotNull(diagnostics, "AC-112②: 0 行时必须给 diagnostics（api.md §2.3）。body=" + p.asString());
        assertFalse(diagnostics.isEmpty(), "AC-112②: 0 行时 diagnostics 不得为空。body=" + p.asString());
        System.out.println("[AC-112②] 诊断 = " + diagnostics);
    }

    // ═══════════════════════════════════════════════════════════════
    // 契约 v9-1（边界）—— 非 AC，但它是 AC-107 / AC-108 可信的前提
    //
    // api.md v9-1 明写：显式传了非三值之一（含旧值 "COSTING"）→ 400，且错误点名收到的是什么、合法值有哪些；
    // 不传 dialect → 缺省 QUOTE。
    //
    // 🔄 2026-09-04 随 api.md §v9-1 的 D-109 放宽同步改判据（用户裁决）：
    //    **大小写与首尾空白容错**（服务端归一后比对），其余一律显式 400。
    //    原文写「逐字一致（大写下划线）」与实现不符 —— 容错不改变语义、不会导致「选错数据集」，
    //    ⇒ 让文档跟实现走，🚫 不为对齐文档去收紧实现。
    //    本用例据此把 "quote" 从坏值列表**移出**，并补一条**正向**断言：
    //    "quote" / " QUOTE " 必须 200 且**真的按 QUOTE 编译**（轴收窄用 material_no）——
    //    只断言 200 是不够的，静默回落 QUOTE 时也会 200，区分不出「归一成功」与「认不出就回落」。
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

        // ① 旧值 COSTING + 拼错值 —— 必须被拒（留着容错等于给「用错方言」开后门）
        //    🚫 "quote" 已按 D-109 移出本列表：大小写/空白容错是**明文契约**，不是缺陷。
        for (String badDialect : List.of("COSTING", "COST_BASIC_TYPO")) {
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

        // ①b【正向 · D-109】大小写与首尾空白必须被归一后接受，且**真的按归一后的方言编译**
        //     判据不能只看 200：静默回落 QUOTE 时同样 200。所以必须再断言轴收窄列，
        //     证明产物确实是 QUOTE 侧（material_no），不是「认不出 → 回落」这个失败模式。
        for (String tolerated : List.of("quote", " QUOTE ")) {
            Response ok = compile(componentId, config(tolerated, "主件", null, cols));
            String okBody = ok.asString();
            System.out.println("[契约 v9-1/D-109] dialect=[" + tolerated + "] → HTTP " + ok.statusCode()
                    + " body=" + trunc(okBody));
            assertEquals(200, ok.statusCode(),
                    "契约 v9-1（D-109 放宽）: 方言 `" + tolerated + "` 应被『trim + 大写归一』后识别为 QUOTE 并正常编译（200），"
                            + "实际=" + ok.statusCode() + "\n  body=" + okBody);
            String tolSql = ok.jsonPath().getString("sql");
            assertNotNull(tolSql, "契约 v9-1（D-109）: `" + tolerated + "` 编译响应缺 sql 字段。body=" + okBody);
            assertTrue(flatten(tolSql).matches(
                            "(?i).*[\\w\\.\"]*\\bmaterial_no\\b\\s*=\\s*ANY\\s*\\(\\s*:total_material_no\\s*\\).*"),
                    "契约 v9-1（D-109）: `" + tolerated + "` 必须**归一成 QUOTE**（轴收窄用 material_no），"
                            + "而不是被静默丢弃后走别的路径。实际 SQL=\n" + tolSql);
            assertFalse(flatten(tolSql).matches(
                            "(?i).*\\bproduction_no\\b\\s*=\\s*ANY\\s*\\(\\s*:total_material_no\\s*\\).*"),
                    "契约 v9-1（D-109）: `" + tolerated + "` 归一后应是 QUOTE，产物不该出现核价侧的 production_no 轴收窄。实际 SQL=\n" + tolSql);
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

    /** 紧邻找一对「销售料号 S → 生产料号 P，且 P 在 ds_cost_basic_material 里」。 */
    private Object[] pickSalesToProduction() {
        List<Object[]> r = rowList(
                "SELECT q.material_no, q.production_no FROM ds_quote_material q "
                        + "JOIN ds_cost_basic_material m ON m.production_no = q.production_no "
                        + "WHERE q.production_no IS NOT NULL AND q.production_no <> '' ORDER BY 1 LIMIT 1");
        return r.isEmpty() ? null : r.get(0);
    }

    /**
     * 该销售料号自己的客户号 —— repair-260908 B-7 起 {@code /preview} 必须带 {@code customerCode}。
     *
     * <p><b>为什么原来不用传、现在要传</b>：B-1/B-1b 之后编译产物普遍含 {@code :customerCode}
     * （锚点谓词 或 NARROW 桥子查询里的客户收窄），而 {@code BuilderService.bindLiterals} 只在
     * {@code customerCode != null} 时做字面量替换 ⇒ 不传就撞既有守卫 {@code PREVIEW_UNBOUND_PLACEHOLDER}
     * 返 500。产品侧不受影响（{@code SqlViewBuilderTab.tsx} 有「请先选择预览客户」硬门），
     * 是本套<b>夹具</b>没传。
     *
     * <p>🚫 <b>不许在这里写死一个客户号</b>：复合轴是 {@code (customer_no, material_no)}，
     * 写死会在「该料号不属于那个客户」时返 0 行 —— AC-111③ 的 {@code rc > 0} 会变成假失败，
     * AC-112① 的「各销售料号行数彼此相同」会退化成 0==0 的假通过。⇒ 按料号紧邻取它自己的客户。
     */
    private String customerOfSales(String salesNo) {
        List<String> r = strList("SELECT customer_no FROM ds_quote_material "
                + "WHERE material_no=?1 AND customer_no IS NOT NULL ORDER BY 1 LIMIT 1", salesNo);
        return r.isEmpty() ? null : r.get(0);
    }

    /** 找锚点落在指定主表上的页签视图，返回 {@code (tab_type, node_key, main_table, node_id)}。 */
    private Object[] pickTabByMainTable(String dialect, String mainTable) {
        List<Object[]> tabs = rowList(
                "SELECT v.tab_type, n.node_key, n.physical_table, n.id::text FROM semantic_tab_view v "
                        + "JOIN semantic_node n ON n.id = v.anchor_node_id "
                        + "WHERE v.dialect=?1 AND v.status='ACTIVE' ORDER BY v.tab_type", dialect);
        for (Object[] t : tabs) {
            String main = normalizeToMainTable(t[2] == null ? null : String.valueOf(t[2]));
            if (mainTable.equals(main)) {
                return new Object[]{String.valueOf(t[0]), String.valueOf(t[1]), main, String.valueOf(t[3])};
            }
        }
        return null;
    }

    /** 组一份「某方言主件页签 + 锚点一列」的裸 config。 */
    private Map<String, Object> configForMainTab(String dialect) {
        Object[] anchor = anchorNode(dialect, "主件");
        assertNotNull(anchor, notReady("AC-111/112", dialect + " 的『主件』页签视图不存在", "cpq-backend #2 / B-42"));
        Object[] col = someColumnById(String.valueOf(anchor[3]));
        assertNotNull(col, notReady("AC-111/112", "锚点节点无列声明", "cpq-backend #2 / B-42"));
        return config(dialect, "主件", null,
                List.of(column(String.valueOf(anchor[0]), String.valueOf(col[0]), "测试字段")));
    }

    private static String trunc(String s) {
        return s == null ? "null" : (s.length() > 1200 ? s.substring(0, 1200) + "…(截断)" : s);
    }

    private Object[] findNodeByTable(String table) {
        List<Object[]> r = rowList(
                "SELECT node_key, physical_table FROM semantic_node "
                        + "WHERE status='ACTIVE' AND (physical_table=?1 OR physical_table=?2) LIMIT 1",
                table, "v_" + table + "_all");
        return r.isEmpty() ? null : r.get(0);
    }

    /**
     * 取<b>指定方言</b>下的料号桥节点（{@code ds_quote_material} 的 LOOKUP 节点）。
     *
     * <p>🚨 2026-09-03 实跑教训：只按 {@code physical_table} 取会命中 <b>QUOTE 侧的 SHEET 节点「物料」</b>
     * （{@code node_key=MATERIAL}）。把它当桥塞进 COST_BASIC 的配置里，编译器按当前方言解析
     * {@code MATERIAL} → {@code ds_cost_basic_material}，那张表没有 {@code material_no}
     * ⇒ 400 {@code COMPILE_COLUMN_NOT_FOUND}「节点『物料』没有列: material_no」。
     * 与 {@code api.md v9-2}「node_key 跨方言重名」同源，桥这一处是我漏掉的最后一个。
     */
    private Object[] findBridgeNode(String dialect) {
        List<Object[]> r = rowList(
                "SELECT node_key, physical_table, id::text FROM semantic_node "
                        + "WHERE status='ACTIVE' AND physical_table='ds_quote_material' "
                        + "AND dialect=?1 AND node_kind='LOOKUP' LIMIT 1", dialect);
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
