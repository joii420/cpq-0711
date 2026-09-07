package com.cpq.semanticgraph;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 需求文档.md §3.5 费用类页签 · 预览 · 体检 · 保存 · 漂移 —— AC-25 ~ AC-34。
 *
 * <h3>🔄 2026-09-05（用户裁决 {@code D-123}）：8 条换 v9 对象后保留，2 条作废留碑</h3>
 *
 * <h4>📌 被作废的 2 个方法（保留历史价值说明，勿删）</h4>
 * <table>
 *   <tr><th>方法</th><th>它验的是什么</th><th>为什么退役</th><th>接替者</th></tr>
 *   <tr><td>{@code ac26_realPreviewReturnsRealRows}</td>
 *       <td>预览返回真实行：甲组（{@code includeChildParts=false}，仅料号自身）2 行 /
 *           乙组（{@code true}，自身 + 闭包后代）5 行 / 差额 3 行 = 后代实际行数。
 *           数据由用例自建（{@code material_bom_item} 建 ROOT→CHILD 闭包链 +
 *           {@code element_bom_item} 上挂 2+3 行）</td>
 *       <td>① 夹具的五张表全是 V6 表，锚点 {@code ELEMENT_BOM_ITEM} 已随 {@code V413} 出图；<br>
 *           ② 更要命的是<b>被测机制本身换了</b>：{@code D-110} 把料号桥从「输出 LOOKUP」改成
 *           「输入收窄（半连接）」、{@code D-119} 又定「无 {@code partNo} 的核价预览绕过桥走直接轴收窄」，
 *           {@code :total_material_no} 的装填口径与「甲/乙组」这个二分法都不再成立</td>
 *       <td><b>{@code AC-117}</b>（COST_BASIC 预览非空、行数 = 紧邻取的基准）/
 *           <b>{@code AC-119}</b>（COST_DETAIL 同口径）/ <b>{@code AC-120}</b>（QUOTE 同口径）；
 *           「不扇出」这一半由 <b>{@code AC-112①}</b> 承担</td></tr>
 *   <tr><td>{@code ac29_defaultBindingsAreCorrect}</td>
 *       <td>费用值默认绑对列：费用类绑 {@code base_value} 而不是 {@code pricing_price}；
 *           主件的成品其他费用绑 {@code cost_ratio}；两者预览均非 NULL</td>
 *       <td>整条建立在 <b>V6 {@code unit_price} 一张表多 sheet 共用列</b>之上 ——
 *           「哪一列才是真源」这个问题正是因为 {@code base_value}/{@code pricing_price}/{@code cost_ratio}
 *           挤在同一张表里才存在。{@code §9.1.3} 起<b>一表一 sheet</b>，每个费用变体有自己的物理表和列，
 *           不存在「绑错兄弟列」这个类别</td>
 *       <td>「列声明与物理表一致」由 <b>{@code AC-104}</b>（{@code semantic_node_column} ⇄
 *           {@code information_schema} 双向无差集）钉死；<br>
 *           「绑了一列结果整列 NULL」这类<b>运行期</b>绑错由本类保留的
 *           {@link #ac28_allNullColumnVsIndividualRowMissingDistinguished()} 继续守着（已换 v9 对象）</td></tr>
 * </table>
 *
 * <h4>✅ 换 v9 对象后保留的 8 个方法</h4>
 * AC-25（费用类可选可存、不改动存量组件）/ AC-27（0 行给可操作诊断）/ AC-28（整列 NULL 诊断）/
 * AC-30（缺标识列阻断）/ AC-31（删列影响面二次确认）/ AC-32（存量手写视图零影响）/
 * AC-33（转手写不可逆）/ AC-34（版本过期提示不自动改写 SQL）——
 * 判据全都与数据集无关，且 {@code AC-101~AC-126} 里<b>没有任何一条覆盖它们</b>。
 *
 * <h4>🔑 AC-28 的换对象取证（2026-09-05 实查 {@code cpq_db_0724}，先证明前提再改用例）</h4>
 * <pre>
 * SELECT count(*), count(gross_usage), count(net_usage) FROM ds_quote_element_bom;
 *   → 53 | 0 | 0     ⇒ 表里有 53 行真实业务数据，而 gross_usage/net_usage 两列「结构存在但恒空」
 * </pre>
 * 这正是 AC-28 要的 misbound 语义（列存在 → 不会走「列不存在」分支；整列 NULL → 能触发
 * {@code COLUMN_ALL_NULL} 诊断），<b>且不需要往共享库里插任何数据</b>。
 * 本用例仍在运行期重取这两个计数，🚫 不照抄上面的 53/0。
 *
 * <p>层级：T-2/T-3。
 */
@QuarkusTest
@TestProfile(SemanticGraphTestSupport.RbacOffProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Sec35FeeTabPreviewInspectTest — AC-25/27/28/30/31/32/33/34 换 v9 对象保留；🪦 AC-26/AC-29 已作废（2026-09-05 D-123）")
class Sec35FeeTabPreviewInspectTest {

    @Inject
    EntityManager em;
    @Inject
    UserTransaction utx;

    private UUID componentId;

    /** v9 QUOTE/材质元素 锚点（各用例的通用夹具来源）。 */
    private String elemAnchorKey;
    private String elemPartNoColumn;
    /** v9 QUOTE/费用类 的第一个变体（variantKey = 锚点 node_key，实测同名）。 */
    private String feeVariantKey;
    private String feeAnchorKey;
    private String feePartNoColumn;
    private String feeAmountColumn;

    // -------------------------------------------------------------------
    // AC-25③ 夹具：自建「仿存量」组件（tab_type=NULL + sql_template 含 $ll_view）。
    // 验的不变量是「新增/保存费用类页签这件事，不应反过来改动任何既有组件的 tab_type」——
    // 与现网到底有多少这类组件无关，故自建、按自己的 id 精确核对，不做全局 LIKE 扫描。
    // -------------------------------------------------------------------
    private static final String AC25_LEGACY_PREFIX = SemanticGraphTestSupport.TAG + "AC25LEGACY-";

    private List<UUID> seedAc25LegacyComponents(int count) {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Response resp = RestAssured.given().contentType(ContentType.JSON)
                    .body("{\"name\":\"" + AC25_LEGACY_PREFIX + UUID.randomUUID() + "\"}")
                    .post("/api/cpq/components");
            assertEquals(200, resp.statusCode(), resp.getBody().asString());
            ids.add(UUID.fromString(resp.jsonPath().getString("data.id")));
        }
        QuarkusTransaction.requiringNew().run(() -> {
            for (UUID id : ids) {
                em.createNativeQuery(
                                "INSERT INTO component_sql_view (component_id, sql_view_name, sql_template) "
                                        + "VALUES (:cid, :name, :sql)")
                        .setParameter("cid", id)
                        .setParameter("name", "ll_view_legacy_" + id.toString().substring(0, 8))
                        .setParameter("sql", "SELECT * FROM $ll_view")
                        .executeUpdate();
            }
        });
        return ids;
    }

    private void cleanupAc25LegacyComponents(List<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        QuarkusTransaction.requiringNew().run(() -> {
            for (UUID id : ids) {
                // component_sql_view 的 FK 挂 ON DELETE CASCADE，删 component 即可；WHERE 限死在自建 id 上。
                em.createNativeQuery("DELETE FROM component WHERE id = :id")
                        .setParameter("id", id).executeUpdate();
            }
        });
    }

    // -------------------------------------------------------------------
    // AC-31 夹具：自建一张最小模板 + template_component 绑定，让 refCount>0。
    // template.template_series_id 是 NOT NULL 但无外键（实测），塞随机 UUID 占位即可；
    // template_component.template_id 挂 ON DELETE CASCADE，删 template 一条即清理干净。
    // -------------------------------------------------------------------
    private static final String AC31_TEMPLATE_NAME = SemanticGraphTestSupport.TAG + "AC31-template";

    private void seedAc31TemplateReferencingComponent(UUID compId) {
        UUID templateId = UUID.randomUUID();
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery(
                            "INSERT INTO template (id, template_series_id, name, status, template_kind) "
                                    + "VALUES (:id, :series, :name, 'DRAFT', 'QUOTATION')")
                    .setParameter("id", templateId).setParameter("series", UUID.randomUUID())
                    .setParameter("name", AC31_TEMPLATE_NAME).executeUpdate();
            em.createNativeQuery("INSERT INTO template_component (template_id, component_id) VALUES (:t, :c)")
                    .setParameter("t", templateId).setParameter("c", compId).executeUpdate();
        });
    }

    @AfterEach
    void cleanupAc31SyntheticData() {
        QuarkusTransaction.requiringNew().run(() ->
                em.createNativeQuery("DELETE FROM template WHERE name = :n")
                        .setParameter("n", AC31_TEMPLATE_NAME).executeUpdate());
    }

    @BeforeEach
    void setUp() {
        componentId = createBlankComponent();

        Object[] elemAnchor = (Object[]) em.createNativeQuery(
                        "SELECT n.node_key, n.id FROM semantic_tab_view v "
                                + "JOIN semantic_node n ON n.id = v.anchor_node_id "
                                + "WHERE v.dialect='QUOTE' AND v.tab_type='材质元素' AND v.status='ACTIVE'")
                .getSingleResult();
        elemAnchorKey = String.valueOf(elemAnchor[0]);
        elemPartNoColumn = String.valueOf(em.createNativeQuery(
                        "SELECT c.db_column FROM semantic_node_column c "
                                + "WHERE c.node_id=:nid AND c.status='ACTIVE' AND 'PART_NO' = ANY(c.roles) LIMIT 1")
                .setParameter("nid", elemAnchor[1]).getSingleResult());

        Object[] feeView = (Object[]) em.createNativeQuery(
                        "SELECT v.variant_key, n.node_key, n.id FROM semantic_tab_view v "
                                + "JOIN semantic_node n ON n.id = v.anchor_node_id "
                                + "WHERE v.dialect='QUOTE' AND v.tab_type='费用类' AND v.status='ACTIVE' "
                                + "ORDER BY v.variant_key LIMIT 1")
                .getSingleResult();
        feeVariantKey = String.valueOf(feeView[0]);
        feeAnchorKey = String.valueOf(feeView[1]);
        feePartNoColumn = String.valueOf(em.createNativeQuery(
                        "SELECT c.db_column FROM semantic_node_column c "
                                + "WHERE c.node_id=:nid AND c.status='ACTIVE' AND 'PART_NO' = ANY(c.roles) LIMIT 1")
                .setParameter("nid", feeView[2]).getSingleResult());
        feeAmountColumn = String.valueOf(em.createNativeQuery(
                        "SELECT c.db_column FROM semantic_node_column c "
                                + "WHERE c.node_id=:nid AND c.status='ACTIVE' AND c.data_type='NUMBER' "
                                + "AND NOT ('SORT' = ANY(c.roles)) ORDER BY c.sort_order LIMIT 1")
                .setParameter("nid", feeView[2]).getSingleResult());

        System.out.println("[Sec35 换对象] 材质元素锚点=" + elemAnchorKey + "/" + elemPartNoColumn
                + " · 费用类变体=" + feeVariantKey + " 锚点=" + feeAnchorKey
                + " 料号列=" + feePartNoColumn + " 金额列=" + feeAmountColumn);
    }

    private UUID createBlankComponent() {
        Response resp = RestAssured.given().contentType(ContentType.JSON)
                .body("{\"name\":\"" + SemanticGraphTestSupport.TAG + "fee-" + UUID.randomUUID() + "\"}")
                .post("/api/cpq/components");
        assertEquals(200, resp.statusCode(), resp.getBody().asString());
        return UUID.fromString(resp.jsonPath().getString("data.id"));
    }

    /**
     * {@code PUT /builder} / {@code POST /inspect} / {@code POST /preview} 都<b>不吃</b>
     * {@code {"builderConfig": {...}}} 包装（2026-08-21 真跑教训：包一层会读到 {@code tabType=null}
     * 并报 {@code COMPILE_TABVIEW_NOT_FOUND}）。额外参数要平级合并进 builder_config 本身。
     */
    private static String withExtraFields(String configJson, String extraFieldsJson) {
        int idx = configJson.indexOf('{');
        return configJson.substring(0, idx + 1) + extraFieldsJson + "," + configJson.substring(idx + 1);
    }

    private Response save(String builderConfig) {
        return RestAssured.given().contentType(ContentType.JSON)
                .body(builderConfig).put("/api/cpq/components/" + componentId + "/builder");
    }

    private Response inspect(String builderConfig) {
        return RestAssured.given().contentType(ContentType.JSON)
                .body(builderConfig).post("/api/cpq/components/" + componentId + "/builder/inspect");
    }

    private Response preview(String builderConfig, String partNo) {
        String extra = partNo == null ? "\"customerCode\":null"
                : "\"partNo\":\"" + partNo + "\"";
        return RestAssured.given().contentType(ContentType.JSON)
                .body(withExtraFields(builderConfig, extra))
                .post("/api/cpq/components/" + componentId + "/builder/preview");
    }

    private String elemCfg(String... extraColumns) {
        String base = "{\"sourceNodeKey\":\"" + elemAnchorKey + "\",\"sourceColumn\":\"" + elemPartNoColumn
                + "\",\"fieldName\":\"材质料号\",\"isRowKey\":true,\"isPartNo\":true}";
        List<String> cols = new ArrayList<>();
        cols.add(base);
        cols.addAll(List.of(extraColumns));
        return "{\"dialect\":\"QUOTE\",\"tabType\":\"材质元素\",\"columns\":[" + String.join(",", cols) + "]}";
    }

    private String feeCfg() {
        return "{\"dialect\":\"QUOTE\",\"tabType\":\"费用类\",\"variantKey\":\"" + feeVariantKey + "\",\"columns\":["
                + "{\"sourceNodeKey\":\"" + feeAnchorKey + "\",\"sourceColumn\":\"" + feePartNoColumn
                + "\",\"fieldName\":\"投入料号\",\"isRowKey\":true,\"isPartNo\":true},"
                + "{\"sourceNodeKey\":\"" + feeAnchorKey + "\",\"sourceColumn\":\"" + feeAmountColumn
                + "\",\"fieldName\":\"费用值\",\"isAmount\":true}]}";
    }

    // ===================================================================
    // 🪦 AC-26 / AC-29 作废前提守卫（会真的执行）
    // ===================================================================
    @Test
    @Order(1)
    @DisplayName("🪦 AC-26/AC-29 作废前提守卫: V6 的 element_bom_item / unit_price 已不在图内，且新图无 discriminator")
    void tombstone_ac26Ac29PremiseStillHolds() {
        long activeNodes = ((Number) em.createNativeQuery(
                "SELECT count(*) FROM semantic_node WHERE status='ACTIVE'").getSingleResult()).longValue();
        long v6FeeNodes = ((Number) em.createNativeQuery(
                "SELECT count(*) FROM semantic_node WHERE physical_table IN "
                        + "('element_bom_item','material_bom_item','unit_price')").getSingleResult()).longValue();
        long withDiscriminator = ((Number) em.createNativeQuery(
                "SELECT count(*) FROM semantic_node WHERE discriminator IS NOT NULL AND status='ACTIVE'")
                .getSingleResult()).longValue();
        System.out.println("[🪦 Sec35 留碑] ACTIVE 节点=" + activeNodes + " · V6 费用/BOM 源节点=" + v6FeeNodes
                + " · 带 discriminator 的节点=" + withDiscriminator);

        assertTrue(activeNodes > 0, "🚨 语义图为空 —— 这不是「作废前提成立」，是种子没就位。"
                + "本条判定为【未验证】，🚫 不许当成通过。");
        assertEquals(0L, v6FeeNodes,
                "🚦 AC-26（闭包甲/乙组预览行数）的作废前提被推翻：V6 的 element_bom_item / material_bom_item / "
                        + "unit_price 又回到图里了（命中 " + v6FeeNodes + " 个节点）。\n"
                        + "  作废理由是「夹具的五张表全出图 + 桥/闭包机制已被 D-110/D-119 改写」。\n"
                        + "  对象回来了 ⇒ 必须重新评估闭包预览这条链还要不要单独验（历史实现见本类 git 历史）。");
        assertEquals(0L, withDiscriminator,
                "🚦 AC-29（费用值默认绑对列）的作废前提被推翻：" + withDiscriminator
                        + " 个节点重新带上了 discriminator。\n"
                        + "  作废理由是「新模型一表一 sheet，不再有『多个费用 sheet 挤一张 unit_price 表、"
                        + "因而可能绑错兄弟列』这个类别」。判别式回来了 ⇒ 必须重新评估。");
    }

    // ===================================================================
    // AC-25（单点）『费用类』作为第 6 个页签类型可选可存
    // ===================================================================
    @Test
    @Order(2)
    @DisplayName("AC-25(v9): 页签类型清单含『费用类』；保存后 tab_type='费用类'；自建的仿存量组件 tab_type 一条未被改动")
    void ac25_expenseTabAsSixthType() {
        List<UUID> legacyIds = seedAc25LegacyComponents(2);
        try {
            Response ft = RestAssured.given().queryParam("tabType", "材质元素").queryParam("dialect", "QUOTE")
                    .get("/api/cpq/config/semantic-graph/field-tree");
            assertEquals(200, ft.statusCode(), ft.getBody().asString());
            List<String> tabTypes = ft.jsonPath().getList("availableTabTypes");
            assertNotNull(tabTypes, "① availableTabTypes 不应为空，原始响应=" + ft.getBody().asString());
            assertFalse(tabTypes.isEmpty(), "① 页签类型清单不应为空");
            System.out.println("[AC-25(v9)] availableTabTypes=" + tabTypes);

            // ⚠️ 🚫 不断言「恰好 6 项」：D-112 已登记并发任务 task-260904 会把 3 方言 × {零件,外购件}
            //    共 6 行 tab_view 置 INACTIVE，届时这个数字必然变 —— 那是预期内的交接，不是回归。
            //    真正属于 AC-25 的不变量是「『费用类』出现在清单里」+「清单里没有野生的第 7 类」。
            assertTrue(tabTypes.contains("费用类"), "① 清单应含『费用类』，实际=" + tabTypes);
            // 🔑 这份清单是拿来比对 **API 返回值** 的，而 availableTabTypes 由图里的
            //    semantic_tab_view.tab_type 派生 ⇒ 里面装的是**库值**，不是 Select 的 label。
            //    D-39：BOM 树页签的存储值 = 'BOM'，显示名 =「BOM 树」，两者故意不同。
            //    V417（B-54，用户 2026-09-05 批准）已把种子里误写成显示名的 3 行改回 'BOM'
            //    ⇒ 本清单必须跟着用 'BOM'，否则 containsAll 恒假（2026-09-06 实测红：
            //      实际=[主件, 材质元素, 零件, 外购件, 费用类, BOM]）。
            List<String> known = List.of("主件", "材质元素", "零件", "外购件", "费用类", "BOM");
            assertTrue(known.containsAll(tabTypes),
                    "① 清单里出现了 6 类之外的页签类型（已知 6 类=" + known + "），实际=" + tabTypes);

            Response saveResp = save(feeCfg());
            assertEquals(200, saveResp.statusCode(), "② 保存不应返回 400: " + saveResp.getBody().asString());

            List<Object> rows = em.createNativeQuery("SELECT tab_type FROM component WHERE id=:id")
                    .setParameter("id", componentId).getResultList();
            assertFalse(rows.isEmpty(), "组件行应存在");
            assertEquals("费用类", rows.get(0), "② tab_type 应为『费用类』，实际=" + rows.get(0));

            // ③ 自建的 2 个仿存量组件 tab_type 应仍为 NULL —— 按自己的 id 逐个精确核对
            long unchanged = legacyIds.stream().filter(id -> {
                List<Object> tt = em.createNativeQuery("SELECT tab_type FROM component WHERE id = :id")
                        .setParameter("id", id).getResultList();
                return !tt.isEmpty() && tt.get(0) == null;
            }).count();
            assertEquals(legacyIds.size(), (int) unchanged,
                    "③ 本用例自建的 " + legacyIds.size() + " 个仿存量组件 tab_type 应全部仍为空，实际未被改动=" + unchanged);
        } finally {
            cleanupAc25LegacyComponents(legacyIds);
        }
    }

    // ===================================================================
    // AC-27（边界）0 行给可操作诊断而非空表格
    // ===================================================================
    @Test
    @Order(3)
    @DisplayName("AC-27(v9): 传一个库里不存在的料号 → 0 行 + 指名道姓的可操作诊断（非空表格）")
    void ac27_zeroRowsGivesActionableDiagnostics() {
        String ghost = SemanticGraphTestSupport.TAG + "NO-SUCH-PART-" + System.currentTimeMillis();
        // 前置取证：这个料号必须真的不存在，否则本边界用例没意义（防「断言从未执行」型假绿）
        long hit = ((Number) em.createNativeQuery(
                        "SELECT count(*) FROM ds_quote_element_bom WHERE material_no = :p")
                .setParameter("p", ghost).getSingleResult()).longValue();
        assertEquals(0L, hit, "前置：幽灵料号必须真的不存在，实际命中=" + hit);

        Response resp = preview(elemCfg(), ghost);
        assertEquals(200, resp.statusCode(), resp.getBody().asString());
        Integer rowCount = resp.jsonPath().getObject("rowCount", Integer.class);
        assertNotNull(rowCount, "rowCount 不应为空，body=" + resp.getBody().asString());
        assertEquals(0, rowCount.intValue(), "该料号应返回 0 行，实际=" + rowCount);

        List<Map<String, Object>> diagnostics = resp.jsonPath().getList("diagnostics");
        assertNotNull(diagnostics, "0 行时 diagnostics 不应为 null（不得只返回空表格）");
        assertFalse(diagnostics.isEmpty(), "0 行时 diagnostics 不应为空列表——必须给出可操作诊断");
        System.out.println("[AC-27(v9)] 0 行诊断=" + diagnostics);

        // 🔑 「可操作」的判据：诊断得指名道姓说清「查什么」，而不是一句「无数据」。
        //    D-115 明写：0 行本身没有任何机械信号，唯一的排查线索就是这句话。
        boolean actionable = diagnostics.stream().anyMatch(d -> {
            String msg = String.valueOf(d.get("message"));
            return msg.contains(ghost)
                    || msg.contains("不存在") || msg.contains("无此类基础数据")
                    || msg.contains("子件") || msg.contains("导入");
        });
        assertTrue(actionable, "诊断信息应指名道姓（点名料号 / 该客户无基础数据 / 数据挂在子件上 / 需导入 之一），"
                + "实际=" + diagnostics);
    }

    // ===================================================================
    // AC-28（单点）整列全 NULL 与个别行无记录要能区分
    // ===================================================================
    @Test
    @Order(4)
    @DisplayName("AC-28(v9): 绑一个「结构存在但整列恒空」的列 → 有行返回 + COLUMN_ALL_NULL『疑似绑错列』诊断")
    void ac28_allNullColumnVsIndividualRowMissingDistinguished() {
        // 🔑 换对象取证（运行期重取，🚫 不照抄文档里的数字）：
        //    在 ds_quote_element_bom 上找一个「表里有行、但该列 100% 为 NULL」的列。
        Object[] probe = (Object[]) em.createNativeQuery(
                "SELECT count(*), count(gross_usage), count(net_usage) FROM ds_quote_element_bom")
                .getSingleResult();
        long total = ((Number) probe[0]).longValue();
        long grossNonNull = ((Number) probe[1]).longValue();
        long netNonNull = ((Number) probe[2]).longValue();
        System.out.println("[AC-28(v9)] 取证 ds_quote_element_bom 行数=" + total
                + " gross_usage 非空=" + grossNonNull + " net_usage 非空=" + netNonNull);
        assertTrue(total > 0, "前置未就绪：ds_quote_element_bom 一行都没有，"
                + "整列 NULL 与「表本来就空」区分不开 ⇒ 本条判定为【未验证】，🚫 不许当成通过");
        String allNullColumn = grossNonNull == 0 ? "gross_usage" : (netNonNull == 0 ? "net_usage" : null);
        assertNotNull(allNullColumn, "前置未就绪：ds_quote_element_bom 上找不到「结构存在但整列恒空」的列"
                + "（gross_usage 非空=" + grossNonNull + "，net_usage 非空=" + netNonNull + "）⇒ 本条判定为【未验证】");

        // 选一个真有数据的料号，保证预览「有行」——整列 NULL 与 0 行是两种不同情况，必须区分开
        String partNo = String.valueOf(em.createNativeQuery(
                "SELECT material_no FROM ds_quote_element_bom GROUP BY material_no "
                        + "ORDER BY count(*) DESC LIMIT 1").getSingleResult());
        System.out.println("[AC-28(v9)] 选中整列恒空的列=" + allNullColumn + " 料号=" + partNo);

        String misbound = elemCfg("{\"sourceNodeKey\":\"" + elemAnchorKey + "\",\"sourceColumn\":\""
                + allNullColumn + "\",\"fieldName\":\"疑似绑错的费用列\",\"isAmount\":true}");
        Response resp = preview(misbound, partNo);
        assertEquals(200, resp.statusCode(), "预览不应失败: " + resp.getBody().asString());
        Integer rowCount = resp.jsonPath().getObject("rowCount", Integer.class);
        assertNotNull(rowCount, "rowCount 不应为空，body=" + resp.getBody().asString());
        assertTrue(rowCount > 0, "① 该料号有真实数据，应 > 0 行（0 行一律不算通过），实际=" + rowCount
                + " body=" + resp.getBody().asString());

        List<Map<String, Object>> diagnostics = resp.jsonPath().getList("diagnostics");
        assertNotNull(diagnostics, "diagnostics 不应为空");
        assertFalse(diagnostics.isEmpty(), "整列绑错应产生诊断，body=" + resp.getBody().asString());
        System.out.println("[AC-28(v9)] 诊断=" + diagnostics);
        boolean hasMisboundWarn = diagnostics.stream().anyMatch(d ->
                "COLUMN_ALL_NULL".equals(d.get("code")) && String.valueOf(d.get("message")).contains("疑似绑错列"));
        assertTrue(hasMisboundWarn, "① 应有 COLUMN_ALL_NULL『疑似绑错列』诊断，实际=" + diagnostics);

        // ③ 与「个别行无记录」可区分：诊断必须带结构化 code（而不是只有一句自然语言）
        boolean hasCode = diagnostics.stream().allMatch(d -> d.get("code") != null);
        assertTrue(hasCode, "③ 每条诊断都应带 code 字段，才能与『个别行无记录』机械区分，实际=" + diagnostics);
    }

    // ===================================================================
    // AC-30（边界）标识列缺失阻断保存
    // ===================================================================
    @Test
    @Order(5)
    @DisplayName("AC-30(v9): 移除全部可推导为料号列/名称列的列后保存 → err 阻断『两者至少要配一个』")
    void ac30_missingIdentifierColumnsBlocksSave() {
        String noIdentifier = "{\"dialect\":\"QUOTE\",\"tabType\":\"材质元素\",\"columns\":["
                + "{\"sourceNodeKey\":\"" + elemAnchorKey + "\",\"sourceColumn\":\"content_pct\","
                + "\"fieldName\":\"组成含量\"}]}";
        Response inspectResp = inspect(noIdentifier);
        assertEquals(200, inspectResp.statusCode(), inspectResp.getBody().asString());
        List<Map<String, Object>> items = inspectResp.jsonPath().getList("items");
        assertNotNull(items, "原始响应=" + inspectResp.getBody().asString());
        assertFalse(items.isEmpty(), "items 不应为空——缺标识列必须产生提示，原始响应=" + inspectResp.getBody().asString());
        System.out.println("[AC-30(v9)] items=" + items);

        // 措辞不是逐字契约（实际文案是「至少要配一个」，AC 原文写的是「至少配一个」）——
        // 只要求「至少」+「配一个」两个语义片段同时出现。
        boolean hasErr = items.stream().anyMatch(c -> "ERR".equalsIgnoreCase(String.valueOf(c.get("level")))
                && String.valueOf(c.get("message")).contains("至少")
                && String.valueOf(c.get("message")).contains("配一个"));
        assertTrue(hasErr, "应有 err 级『(两者)至少(要)配一个』提示，实际=" + items);

        Response saveResp = save(noIdentifier);
        assertEquals(400, saveResp.statusCode(), "保存应被拒绝(INSPECT_BLOCKED): " + saveResp.getBody().asString());
    }

    // ===================================================================
    // AC-31（序列）删除列的影响面二次确认与三者同步
    // ===================================================================
    @Test
    @Order(6)
    @DisplayName("AC-31(v9)【序列】: 删列返 409 列出受影响模板 → 带 confirmedImpact 重发成功 → 组件字段同步消失")
    void ac31_deleteColumnImpactConfirmationAndThreeWaySync() {
        String twoCol = elemCfg("{\"sourceNodeKey\":\"" + elemAnchorKey
                + "\",\"sourceColumn\":\"item_seq\",\"fieldName\":\"项次\"}");
        Response saveResp = save(twoCol);
        assertEquals(200, saveResp.statusCode(), saveResp.getBody().asString());

        seedAc31TemplateReferencingComponent(componentId);
        Number refCount = (Number) em.createNativeQuery(
                        "SELECT count(*) FROM template_component tc WHERE tc.component_id = :id")
                .setParameter("id", componentId).getSingleResult();
        assertTrue(refCount.intValue() > 0,
                "前置夹具应已就绪：本用例自建了 1 个模板绑定，refCount 应 > 0，实际=" + refCount);

        String oneCol = elemCfg();
        Response deleteAttempt = save(oneCol);
        assertEquals(409, deleteAttempt.statusCode(),
                "① 删除列未带 confirmedImpact 应返 409: " + deleteAttempt.getBody().asString());
        assertEquals("IMPACT_CONFIRM_REQUIRED", deleteAttempt.jsonPath().getString("code"));
        // affectedTemplates 是顶层字段（不在 detail 之下），元素是 {name,id} 对象而非字符串
        List<Map<String, Object>> affectedTemplates = deleteAttempt.jsonPath().getList("affectedTemplates");
        assertNotNull(affectedTemplates, "① 应列出受影响的模板，body=" + deleteAttempt.getBody().asString());
        assertFalse(affectedTemplates.isEmpty(), "① 受影响模板列表不应为空，body=" + deleteAttempt.getBody().asString());
        List<String> names = deleteAttempt.jsonPath().getList("affectedTemplates.name");
        assertTrue(names.contains(AC31_TEMPLATE_NAME),
                "① 受影响模板应包含本用例自建的模板名，实际=" + names);

        Response confirmedResp = RestAssured.given().contentType(ContentType.JSON)
                .body(withExtraFields(oneCol, "\"confirmedImpact\":true"))
                .put("/api/cpq/components/" + componentId + "/builder");
        assertEquals(200, confirmedResp.statusCode(),
                "② 带 confirmedImpact 重发应成功: " + confirmedResp.getBody().asString());

        List<Object> fieldsRows = em.createNativeQuery("SELECT fields FROM component WHERE id=:id")
                .setParameter("id", componentId).getResultList();
        assertFalse(fieldsRows.isEmpty(), "组件行应存在");
        String fieldsJson = String.valueOf(fieldsRows.get(0));
        assertFalse(fieldsJson.contains("项次"), "② 组件字段中『项次』应已消失，实际=" + fieldsJson);
    }

    // ===================================================================
    // AC-32（边界）存量手写视图零影响
    // ===================================================================
    @Test
    @Order(7)
    @DisplayName("AC-32: 存量手写视图（builder_config IS NULL）打开『取数配置』Tab 显示引导页，不进入拖拽态")
    void ac32_legacyHandwrittenViewsUnaffected() {
        List<Object> legacyIds = em.createNativeQuery(
                        "SELECT c.id FROM component c JOIN component_sql_view v ON v.component_id=c.id "
                                + "WHERE v.builder_config IS NULL AND v.sql_template IS NOT NULL LIMIT 3")
                .getResultList();
        assertFalse(legacyIds.isEmpty(),
                "库中应存在至少一个存量手写视图组件（builder_config IS NULL）供本用例验证 —— "
                        + "若为空说明环境前置未就绪，本条判定为【未验证】");
        System.out.println("[AC-32] 抽到存量手写视图组件 " + legacyIds.size() + " 个");

        for (Object idObj : legacyIds) {
            UUID legacyId = idObj instanceof UUID ? (UUID) idObj : UUID.fromString(String.valueOf(idObj));
            Response builderGet = RestAssured.given().get("/api/cpq/components/" + legacyId + "/builder");
            assertEquals(200, builderGet.statusCode(), builderGet.getBody().asString());
            assertTrue(builderGet.jsonPath().getBoolean("isLegacyHandwritten")
                            || builderGet.jsonPath().get("builderConfig") == null,
                    "① 存量视图应显示引导页(不进入拖拽态)，即 builderConfig 为空 / isLegacyHandwritten=true，"
                            + "组件=" + legacyId + " 实际=" + builderGet.getBody().asString());
        }
    }

    // ===================================================================
    // AC-33（边界）转为手写不可逆
    // ===================================================================
    @Test
    @Order(8)
    @DisplayName("AC-33(v9): 转手写 SQL 后 builder_config 变 NULL，再打开显示引导页而非拖拽态")
    void ac33_convertToHandwrittenIsIrreversible() {
        Response saveResp = save(elemCfg());
        assertEquals(200, saveResp.statusCode(), saveResp.getBody().asString());

        Response detachResp = RestAssured.given().contentType(ContentType.JSON)
                .body("{}").post("/api/cpq/components/" + componentId + "/builder/detach");
        // 实测返回 204（No Content）——对「转为手写」这类无响应体的成功动作，204 与 200 同为合法成功语义。
        assertTrue(detachResp.statusCode() >= 200 && detachResp.statusCode() < 300,
                "② 转为手写应成功(2xx): " + detachResp.statusCode() + " " + detachResp.getBody().asString());

        List<Object> rows = em.createNativeQuery(
                        "SELECT builder_config FROM component_sql_view WHERE component_id=:id")
                .setParameter("id", componentId).getResultList();
        assertFalse(rows.isEmpty(), "视图行应存在");
        assertNull(rows.get(0), "② builder_config 应变为 NULL，实际=" + rows.get(0));

        Response reopen = RestAssured.given().get("/api/cpq/components/" + componentId + "/builder");
        assertEquals(200, reopen.statusCode(), reopen.getBody().asString());
        assertNull(reopen.jsonPath().get("builderConfig"),
                "③ 重新打开应显示引导页(builderConfig 为空)而非旧拖拽态，实际=" + reopen.getBody().asString());
    }

    // ===================================================================
    // AC-34（单点）打开旧版本视图给过期提醒，且不自动改写 SQL
    // ===================================================================
    @Test
    @Order(9)
    @DisplayName("AC-34(v9): builder_version 低于当前编译器版本 → isStale=true，且仅打开不自动改写 sql_template")
    void ac34_openingOldVersionShowsStaleWarning() {
        Response saveResp = save(elemCfg());
        assertEquals(200, saveResp.statusCode(), saveResp.getBody().asString());

        // 人为把该视图的 builder_version 降到 0，模拟「低于当前编译器版本」。
        // WHERE 限死在本用例自建的 componentId 上；测试方法没有活跃事务，必须手工开事务。
        try {
            utx.begin();
            em.joinTransaction();
            em.createNativeQuery("UPDATE component_sql_view SET builder_version = 0 WHERE component_id=:id")
                    .setParameter("id", componentId).executeUpdate();
            utx.commit();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        String sqlBefore = String.valueOf(em.createNativeQuery(
                        "SELECT sql_template FROM component_sql_view WHERE component_id=:id")
                .setParameter("id", componentId).getSingleResult());

        Response getResp = RestAssured.given().get("/api/cpq/components/" + componentId + "/builder");
        assertEquals(200, getResp.statusCode(), getResp.getBody().asString());
        Boolean isStale = getResp.jsonPath().getBoolean("isStale");
        assertNotNull(isStale, "① 应返回 isStale 标记，实际=" + getResp.getBody().asString());
        assertTrue(isStale, "① builder_version=0 应判定为过期，实际=" + getResp.getBody().asString());
        Integer currentVersion = getResp.jsonPath().getInt("currentCompilerVersion");
        assertNotNull(currentVersion, "① 应带当前编译器版本号");
        System.out.println("[AC-34(v9)] isStale=" + isStale + " currentCompilerVersion=" + currentVersion);

        String sqlAfter = String.valueOf(em.createNativeQuery(
                        "SELECT sql_template FROM component_sql_view WHERE component_id=:id")
                .setParameter("id", componentId).getSingleResult());
        assertEquals(sqlBefore, sqlAfter, "④ 仅打开不应自动改写 sql_template");
    }
}
