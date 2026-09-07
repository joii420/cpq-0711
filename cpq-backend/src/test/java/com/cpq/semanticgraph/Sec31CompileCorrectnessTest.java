package com.cpq.semanticgraph;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.*;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 🪦 <b>本类原覆盖 需求文档.md §3.1（v8 期 AC-1 ~ AC-10）—— 其中 7 条已于 2026-09-05 作废留碑</b>，
 * 2 条换成 v9 对象后保留（见下表）。
 *
 * <h3>裁决来源</h3>
 * <ul>
 *   <li><b>用户 2026-09-05 裁决</b>（{@code 需求文档.md} 的 {@code D-123}，「本任务顺手退役掉 v8 期语义图用例」）；</li>
 *   <li>其技术前提是 <b>{@code D-77}</b>（{@code dialect} 扩三值，{@code COSTING} 作废）与
 *       <b>{@code D-78}</b>（原 56 条绑死 V6 的 AC 整块作废、按新数据集重写）——
 *       {@code V413} 已把 V6 的 23 节点 / 25 边 / 7 页签视图 / 145 列声明从 {@code semantic_*} 删光，
 *       本类下列方法的<b>断言对象整个消失</b>。</li>
 * </ul>
 *
 * <h3>📌 被作废的 7 个方法（保留历史价值说明，勿删）</h3>
 * <table>
 *   <tr><th>方法</th><th>它验的是什么</th><th>为什么退役</th><th>接替者</th></tr>
 *   <tr><td>{@code ac1_materialElementBaseRecipeAndPriceStrategy}</td>
 *       <td>材质元素页签七项：{@code ebi.material_no AS hf_part_no} / 别名前缀 / 价格策略函数
 *           {@code f_material_element_price(...) cep} 双条件 JOIN / 无 {@code COALESCE(...,0)} 兜底 /
 *           <b>{@code is_current}·{@code system_type} 只许出现在顶层 WHERE、不许进 JOIN…ON</b>（铁律⑦）</td>
 *       <td>锚点 {@code ELEMENT_BOM_ITEM} 与查名节点 {@code LOOKUP_MATERIAL_RECIPE}/{@code LOOKUP_ELEMENT}
 *           已随 {@code V413} 删除；铁律⑦ 更是被 {@code S-22} <b>反转</b> ——
 *           新表根本没有 {@code is_current}/{@code system_type} 两列，{@code applyFullScope()} 退化为只做轴收窄</td>
 *       <td>铁律⑦ → <b>{@code AC-107}</b>（产物不含 {@code system_type}/{@code customer_no}）；<br>
 *           价格策略原子组 → 本轮保留的 {@link Sec34PriceStrategyTest}（已换 v9 对象，
 *           {@code FUNC_ELEMENT_PRICE} 节点在 v9 图里仍然存在且仍挂 QUOTE/材质元素）</td></tr>
 *   <tr><td>{@code ac4_autoLookupJoinNoDuplication}</td>
 *       <td>查名连线自动生成：{@code COALESCE(mr.name, mm2.material_name)} 双路径合并、
 *           {@code material_master} 只 JOIN 一次不重复、别名不冲突</td>
 *       <td><b>v9 图里一条查名边都没有</b>（2026-09-05 实测：{@code semantic_edge.edge_kind} 的 distinct 值
 *           只有 {@code NARROW}(28) 与 {@code PRICE}(1)，{@code LOOKUP} = 0）——
 *           新数据集一表一 sheet、列自带中文名，不再需要「按码查名」这一层</td>
 *       <td>无（判据随对象一并消失）。防线由 {@link #retiredCompileAcs_tombstone_premiseStillHolds()} 守着：
 *           一旦 {@code LOOKUP}/{@code AUX} 边回来，本条变红要求重新评估</td></tr>
 *   <tr><td>{@code ac5_auxSourceAsScalarSubquery}</td>
 *       <td>附属源列编译为<b>相关标量子查询</b>（{@code (SELECT … LIMIT 1)}）而不是 {@code LEFT JOIN}，
 *           因而行粒度不被附属源改变（拖入前后 {@code grain} 相等）</td>
 *       <td>v9 的页签视图<b>每个只挂 1 个 SHEET 节点</b>（实测：{@code semantic_tab_view_node} 里
 *           {@code role='AUX'} 且节点是 SHEET 的行 = 0；唯一的 AUX 是 QUOTE/材质元素 上的
 *           {@code FUNC_ELEMENT_PRICE}，那是 FUNCTION 不是 Sheet）⇒ 「附属源 Sheet」这个对象不存在</td>
 *       <td>同上（前提守卫）。行数不翻倍这件事在 v9 由 <b>{@code AC-112①}</b>（桥不扇出）承担</td></tr>
 *   <tr><td>{@code ac6_bomDiscriminatorDerivedFromTabType}</td>
 *       <td>物料 BOM 判别式由页签类型推导：外购件产物含 {@code characteristic='OUTSOURCED'}，
 *           BOM 树产物不含任何 {@code characteristic} 过滤</td>
 *       <td>v9 全部 44 个节点 {@code discriminator} 均为 NULL（实测）；且 {@code D-112} 查明
 *           三方言的「零件 / 外购件 / BOM 树」<b>共用同一锚点、{@code tab_view_node} 逐字相同</b>
 *           ⇒ 零件/外购件本就是 BOM 树的重复读法，判别式无从谈起</td>
 *       <td>并发任务 <b>{@code task-260904} 页签类型收缩</b>（{@code D-112}：把 3 方言 × {零件,外购件}
 *           共 6 行 {@code semantic_tab_view} 置 {@code INACTIVE}）</td></tr>
 *   <tr><td>{@code ac7_mainPartCustomerNarrowingSpecialCase}</td>
 *       <td>主件页签的客户收窄特例：{@code JOIN material_customer_map mcm ON … AND mcm.customer_no = :customerCode}，
 *           且不含 {@code mm.is_current}/{@code mm.system_type}，不出现空 {@code WHERE}</td>
 *       <td>{@code material_customer_map} 与 {@code material_master} 双双出图（{@code AC-102} 点名的 8 张 V6 表之一）；
 *           新数据集<b>只有 {@code ds_quote_customer_part} 带客户列</b>，而它按 {@code N-19} 明确不进图
 *           ⇒ 「客户收窄」在 v9 编译器里整个不存在</td>
 *       <td><b>{@code AC-107}</b>（产物不含 {@code customer_no}）+ <b>{@code AC-108}</b>（三方言轴收窄）</td></tr>
 *   <tr><td>{@code ac8_expenseTabDualSourceDiscriminator}</td>
 *       <td>费用类双源判别式：单拖 → {@code price_type = 'INCOMING_MATERIAL_PROCESS'}；
 *           双拖 → {@code price_type IN ('INCOMING_MATERIAL_PROCESS','INCOMING_MATERIAL_OTHER')}；
 *           投入料号名称 {@code COALESCE(mm.material_name, mr.name)} 双 LEFT JOIN</td>
 *       <td>整条建立在 <b>V6「多 sheet 挤一张 {@code unit_price} 表、靠 {@code discriminator} 分流」</b> 之上；
 *           {@code §9.1.3} 起新模型是<b>一表一 sheet</b>（费用类变体 报价 8 / 基础核价 7 / 明细核价 15，
 *           各自独立物理表），判别式与双源查名一起消失</td>
 *       <td><b>{@code AC-106}</b>（三方言 {@code (tab_type, variant_key)} 组合数逐格相等，费用类 8/7/15）</td></tr>
 *   <tr><td>{@code ac10_pathAmbiguityMustError_negativeCase}</td>
 *       <td>反证型：两条可达路径时编译必须 400 {@code COMPILE_PATH_AMBIGUOUS} 并列出全部候选路径
 *           ——「编译器不猜」</td>
 *       <td>① 该用例<b>自始至终没真跑过</b>：它依赖测试侧虚构的 {@code __testOnlyForcePathAmbiguity} 钩子，
 *           后端从未实现，2026-08-21 起恒 SKIP（{@code skip != pass}）；
 *           ② v9 图是<b>星形</b>的 —— 28 条 {@code NARROW} 边全部指向同一个料号桥、桥本身<b>没有出边</b>
 *           （实测二跳边 = 0）⇒ 只读手段构造不出第二条路径</td>
 *       <td>🚨 <b>无接替者，这是一个交付缺口</b>：{@code AC-123} 只覆盖四道校验里的第③道「物理存在性」，
 *           <b>{@code PATH_UNIQUENESS} 在 v9 AC 集合里没有任何一条覆盖</b>。
 *           已随本次退役一并上报主线（同 {@code Sec36a.ac55}，两者是同一缺口的编译期/保存期两面）</td></tr>
 * </table>
 *
 * <h3>✅ 换 v9 对象后保留的 2 个方法</h3>
 * <ul>
 *   <li>{@link #ac3_artifactShapeInvariants_onV9Graph()} —— 原 {@code ac3}。判据（{@code D-50~D-53}
 *       闭包统一为「主树供数组」：产物只发 {@code = ANY(:total_material_no)}，
 *       <b>不得再有 {@code WITH RECURSIVE}/{@code bom_closure}</b>，顶层 {@code FROM} 保持裸表）
 *       与数据集无关，v9 上依然成立且<b>没有任何 v9 AC 覆盖「不得出现 WITH RECURSIVE」这一条</b>。</li>
 *   <li>{@link #ac9_generatedShapeMustBeRewriterRecognizable()} —— 原 {@code ac9①}。
 *       {@code rewriterCompatible}（{@code TABLE_TOKEN} 回扫命中）是编译产物的<b>形状契约</b>，与数据集无关。
 *       原 {@code ac9②} 的「畸形产物必须被拒」因依赖虚构钩子从未跑通，随本轮一并作废（见方法内注释）。</li>
 * </ul>
 *
 * <h3>🚫 没有留成「永久 skip 的死用例」</h3>
 * 项目规矩 {@code skip != pass}，故本类<b>不用 {@code @Disabled}</b>。
 * {@link #retiredCompileAcs_tombstone_premiseStillHolds()} 是一条<b>会真的执行</b>的「作废前提守卫」。
 */
@QuarkusTest
@TestProfile(SemanticGraphTestSupport.RbacOffProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Sec31CompileCorrectnessTest — 🪦 v8 期 AC-1/4/5/6/7/8/10 已作废（2026-09-05 D-123），AC-3/AC-9① 换 v9 对象保留")
class Sec31CompileCorrectnessTest {

    @Inject
    EntityManager em;
    @Inject
    UserTransaction utx;

    private UUID componentId;

    @BeforeEach
    void setUp() {
        componentId = createBlankComponent();
    }

    private UUID createBlankComponent() {
        Response resp = RestAssured.given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"" + SemanticGraphTestSupport.TAG + "compile-" + UUID.randomUUID() + "\"}")
                .post("/api/cpq/components");
        assertEquals(200, resp.statusCode(), "建组件失败: " + resp.getBody().asString());
        return UUID.fromString(resp.jsonPath().getString("data.id"));
    }

    private Response compile(String builderConfigJson) {
        return RestAssured.given()
                .contentType(ContentType.JSON)
                .body(builderConfigJson)
                .post("/api/cpq/components/" + componentId + "/builder/compile");
    }

    private long scalar(String sql) {
        return ((Number) em.createNativeQuery(sql).getSingleResult()).longValue();
    }

    // ===================================================================
    // 🪦 作废前提守卫（会真的执行；前提被推翻就变红）
    // ===================================================================
    @Test
    @Order(1)
    @DisplayName("🪦 作废前提守卫: V6 物理源全部出图 + 查名/附属边为 0 + 判别式为 0 + 无二跳路径")
    void retiredCompileAcs_tombstone_premiseStillHolds() {
        long activeNodes = scalar("SELECT count(*) FROM semantic_node WHERE status='ACTIVE'");
        long v6Nodes = scalar("SELECT count(*) FROM semantic_node WHERE physical_table IN ("
                + "'element_bom_item','material_bom_item','unit_price','capacity','material_master',"
                + "'material_customer_map','material_recipe','element','plating_scheme','annual_discount')");
        long lookupOrAuxEdges = scalar("SELECT count(*) FROM semantic_edge WHERE edge_kind IN ('LOOKUP','AUX')");
        long auxSheetNodes = scalar("SELECT count(*) FROM semantic_tab_view_node tvn "
                + "JOIN semantic_node n ON n.id = tvn.node_id "
                + "WHERE tvn.role='AUX' AND n.node_kind='SHEET' AND tvn.status='ACTIVE'");
        long withDiscriminator = scalar("SELECT count(*) FROM semantic_node "
                + "WHERE discriminator IS NOT NULL AND status='ACTIVE'");
        long twoHopPaths = scalar("SELECT count(*) FROM semantic_edge e1 JOIN semantic_edge e2 "
                + "ON e2.from_node_id = e1.to_node_id WHERE e1.status='ACTIVE' AND e2.status='ACTIVE'");

        System.out.println("[🪦 Sec31 留碑] ACTIVE 节点=" + activeNodes + " · V6 物理源节点=" + v6Nodes
                + " · LOOKUP/AUX 边=" + lookupOrAuxEdges + " · AUX 挂 SHEET=" + auxSheetNodes
                + " · 带 discriminator 节点=" + withDiscriminator + " · 二跳路径=" + twoHopPaths);

        assertTrue(activeNodes > 0, "🚨 语义图为空（ACTIVE 节点 " + activeNodes + " 个）——"
                + "这不是「作废前提成立」，是种子没就位。本条判定为【未验证】，🚫 不许当成通过。");

        assertEquals(0L, v6Nodes,
                "🚦 AC-1/4/5/6/7/8 的作废前提被推翻：V6 物理源又回到语义图里了（命中 " + v6Nodes + " 个节点）。\n"
                        + "  这些用例是 2026-09-05 按 D-123（技术前提 D-77/D-78 + V413 删 V6 图）作废的，"
                        + "唯一理由就是「断言对象整个消失」。\n"
                        + "  对象回来了 ⇒ 必须重新评估：要么把对应用例接回来（历史实现见本类 git 历史），"
                        + "要么解释清楚新回来的 V6 节点为什么不需要这些编译产物断言。");

        assertEquals(0L, lookupOrAuxEdges,
                "🚦 AC-4（查名连线自动生成、不重复 JOIN）的作废前提被推翻：图里出现了 "
                        + lookupOrAuxEdges + " 条 LOOKUP/AUX 边。\n"
                        + "  作废理由是「v9 一条查名边都没有」。现在有了 ⇒ 「自动生成的查名 JOIN 会不会重复/别名冲突」"
                        + "这道防线必须重新接上。");

        assertEquals(0L, auxSheetNodes,
                "🚦 AC-5（附属源编译为相关标量子查询、不改行粒度）的作废前提被推翻：出现了 "
                        + auxSheetNodes + " 个以 AUX 角色挂在页签视图上的 SHEET 节点。\n"
                        + "  作废理由是「v9 每个页签视图只挂 1 个 SHEET」。现在多源了 ⇒ 行数翻倍风险回来了，必须重新评估。");

        assertEquals(0L, withDiscriminator,
                "🚦 AC-6/AC-8（判别式由页签类型推导 / 费用类双源判别式）的作废前提被推翻："
                        + withDiscriminator + " 个节点重新带上了 discriminator。\n"
                        + "  作废理由是「新模型一表一 sheet，不再靠判别式分流」。判别式回来了 ⇒ 必须重新评估。");

        assertEquals(0L, twoHopPaths,
                "🚦 AC-10（路径歧义编译期报错）的作废前提被推翻：图里出现了 " + twoHopPaths + " 条二跳路径。\n"
                        + "  作废理由之一是「v9 是星形图、桥无出边，只读手段构造不出第二条路径」。\n"
                        + "  🚨 顺带提醒：PATH_UNIQUENESS 在 v9 AC 集合（AC-101~126）里本来就没有任何一条覆盖，"
                        + "这是已上报主线的交付缺口 —— 现在有多跳路径了，缺口的风险等级要重新评。");
    }

    // ===================================================================
    // ✅ 保留（换 v9 对象）：原 AC-3 —— 编译产物的形状不变量
    // ===================================================================
    @Test
    @Order(2)
    @DisplayName("AC-3(v9): 产物只用 = ANY(:total_material_no) 收窄；不含 WITH RECURSIVE/bom_closure；顶层 FROM 是裸表")
    void ac3_artifactShapeInvariants_onV9Graph() {
        // 换对象取证：v9 QUOTE/材质元素 的锚点是 ds_quote_element_bom（V410 种子），
        // 由本方法自己从库里查出来，🚫 不写死表名（种子由脚本机器生成，命名不由测试定）。
        Object[] anchor = (Object[]) em.createNativeQuery(
                        "SELECT n.node_key, n.physical_table FROM semantic_tab_view v "
                                + "JOIN semantic_node n ON n.id = v.anchor_node_id "
                                + "WHERE v.dialect='QUOTE' AND v.tab_type='材质元素' AND v.status='ACTIVE'")
                .getSingleResult();
        String anchorKey = String.valueOf(anchor[0]);
        String anchorTable = String.valueOf(anchor[1]);
        System.out.println("[AC-3(v9)] QUOTE/材质元素 锚点 node_key=" + anchorKey + " physical_table=" + anchorTable);
        assertNotNull(anchorTable, "锚点物理表不应为空 —— 环境前置未就绪，本条判定为【未验证】");

        String config = """
                { "dialect": "QUOTE", "tabType": "材质元素", "columns": [
                  {"sourceNodeKey":"%s","sourceColumn":"material_part_no","fieldName":"材质料号","isRowKey":true,"isPartNo":true},
                  {"sourceNodeKey":"%s","sourceColumn":"element_code","fieldName":"元素"},
                  {"sourceNodeKey":"%s","sourceColumn":"content_pct","fieldName":"组成含量"}
                ]}
                """.formatted(anchorKey, anchorKey, anchorKey);
        Response resp = compile(config);
        assertEquals(200, resp.statusCode(), "编译应成功: " + resp.getBody().asString());
        String sql = resp.jsonPath().getString("sql");
        assertNotNull(sql, "sql 字段不应为空");
        assertFalse(sql.isBlank(), "sql 不应为空字符串");
        System.out.println("[AC-3(v9)] 编译产物:\n" + sql);

        // ① 锚点轴列上生成 = ANY(:total_material_no)（参数名逐字，允许 ANY(:x) / ANY( :x ) 两种空白写法）
        assertTrue(sql.matches("(?is).*\\.material_no\\s*=\\s*ANY\\(\\s*:total_material_no\\s*\\).*"),
                "① 锚点轴列应生成 = ANY(:total_material_no) 收窄谓词，实际:\n" + sql);

        // ② D-50~D-53 闭包统一：全文不得再有 WITH RECURSIVE / bom_closure（A 机制已停用）
        assertFalse(sql.toUpperCase().contains("WITH RECURSIVE"),
                "② 不应含 WITH RECURSIVE（D-50 闭包机制已统一为「主树供数组」），实际:\n" + sql);
        assertFalse(sql.contains("bom_closure"), "② 不应含 bom_closure，实际:\n" + sql);

        // ③ 顶层 FROM 仍是裸表（改写器可识别的形状；与下面 ac9 的 rewriterCompatible 互为印证）
        assertTrue(sql.matches("(?is).*\\bFROM\\s+" + anchorTable + "\\s+\\w+\\b.*"),
                "③ 顶层 FROM 应是裸表 " + anchorTable + "，实际:\n" + sql);
    }

    // ===================================================================
    // ✅ 保留（换 v9 对象）：原 AC-9① —— 产物形状必须被改写器识别
    // ===================================================================
    // 🪦 原 ac9② 一并作废：它靠测试侧虚构的 `__testOnlyForceWrapFromAsSubquery` 开关制造「顶层 FROM 被包成
    //    子查询」的畸形产物，后端从未实现该开关（架构上编译器完全自己控制 SQL 模板，没有这种合法配置路径），
    //    2026-08-21 起该分支恒 SKIP —— 它从来没有验证过任何东西，退役掉的是一个从未生效的断言。
    //    要真正坐实这条反证，需要开发侧给出一个可从公开 API 触达的畸形入口，或证明该畸形在当前架构下不可能发生。
    @Test
    @Order(3)
    @DisplayName("AC-9①(v9): 三个页签类型的 v9 产物 rewriterCompatible 均为 true（TABLE_TOKEN 回扫命中≥1）")
    void ac9_generatedShapeMustBeRewriterRecognizable() {
        // 每个页签类型用它自己锚点上的一个真实列 —— 锚点与列都从库里现查，不写死。
        for (String tabType : java.util.List.of("材质元素", "外购件", "主件")) {
            Object[] anchor = (Object[]) em.createNativeQuery(
                            "SELECT n.node_key, n.id FROM semantic_tab_view v "
                                    + "JOIN semantic_node n ON n.id = v.anchor_node_id "
                                    + "WHERE v.dialect='QUOTE' AND v.tab_type=:tt AND v.status='ACTIVE'")
                    .setParameter("tt", tabType).getSingleResult();
            String nodeKey = String.valueOf(anchor[0]);
            String column = String.valueOf(em.createNativeQuery(
                            "SELECT c.db_column FROM semantic_node_column c "
                                    + "WHERE c.node_id = :nid AND c.status='ACTIVE' AND 'PART_NO' = ANY(c.roles) LIMIT 1")
                    .setParameter("nid", anchor[1]).getSingleResult());
            System.out.println("[AC-9①(v9)] " + tabType + " → 锚点 " + nodeKey + " 料号列 " + column);

            String config = "{\"dialect\":\"QUOTE\",\"tabType\":\"" + tabType + "\",\"columns\":["
                    + "{\"sourceNodeKey\":\"" + nodeKey + "\",\"sourceColumn\":\"" + column
                    + "\",\"fieldName\":\"料号\",\"isRowKey\":true,\"isPartNo\":true}]}";
            Response resp = compile(config);
            assertEquals(200, resp.statusCode(), tabType + " 编译应成功: " + resp.getBody().asString());
            Boolean compat = resp.jsonPath().getBoolean("rewriterCompatible");
            assertNotNull(compat, tabType + " 响应应带 rewriterCompatible 字段，实际=" + resp.getBody().asString());
            assertTrue(compat, "① " + tabType + " 的产物 rewriterCompatible 应为 true（TABLE_TOKEN 命中≥1），"
                    + "实际 sql=\n" + resp.jsonPath().getString("sql"));
        }
    }
}
