package com.cpq.repair260908;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * repair-260908 · <b>测试分片 S-3（驱动层 · 私有写）</b>的公共基座。
 *
 * <h3>本片认领的 AC</h3>
 * {@code AC-3}（B 族页签行数差 = 被缺陷① 滤掉的跨客户行数，不多不少）、
 * {@code AC-11}（快照缺 {@code axis_scope} 键 ⇒ 行为回到 {@code CLOSURE}）、
 * {@code AC-13}（无 BOM 子件的成品）。断言全部来自 {@code 问题说明.md §⑥} 原文，
 * 🚫 未读 {@code cpq-backend/src/main/java/**}。
 *
 * <h3>🚨 共库纪律（{@code CLAUDE.md §3.2}「测试也算」/ {@code testing.md §4.5}）</h3>
 * {@code test} profile 实连共享开发库 {@code 10.177.152.12:5432/cpq_db_0724}，当前有并发会话在用。故：
 * <ul>
 *   <li>造数一律带分片前缀 {@link #PREFIX}（{@code R260908S3_}）+ 本轮 {@link #RUN_ID}；</li>
 *   <li>🚫 <b>无全局计数断言</b> —— 不写「视图共 28 条」「模板共 5 个」这类；
 *       一律断言「我自己造的那些对象」或「对我点名的对象成立的不变量」；</li>
 *   <li>🚫 <b>不改任何共享对象</b>：共享 {@code template} / {@code component_sql_view} /
 *       {@code component} / 别人的报价单<b>一个字节都不动</b>。
 *       本片要「换一个 {@code axis_scope}」时，换的是<b>我自己克隆出来的那份</b>；</li>
 *   <li>清理在 {@code @AfterAll} / {@code finally} 语义位置执行，命中面被前缀限死；
 *       🚫 无 {@code TRUNCATE} / {@code DROP} / 无 WHERE 的删除 / 清库 / 全局状态重置。</li>
 * </ul>
 *
 * <h3>🔑 私有夹具为什么必须克隆这么多张表（2026-09-09 实测，不是过度设计）</h3>
 * 本片头两轮把「克隆一张 {@code template} 行 + 改 {@code line_item.template_id}」当成了私有模板，
 * <b>结果是整套克隆完全没被用上</b>，渲染走的仍是原始共享模板 —— 而症状是
 * 「三种 {@code axis_scope} 取值都给出同一个数字」，长得<b>非常像「兜底生效了」</b>。
 * 逐个实测出来的真实读取链是：
 * <pre>
 *   quotation.customer_template_id      ← 卡片渲染取模板的入口（不是 line_item.template_id）
 *   template_component (+_snapshot)     ← 页签与组件的来源（不是 template.components_snapshot）
 *   template.sql_views_snapshot         ← sql_template 与 axis_scope 的<b>权威</b>
 *                                          （实测 component_sql_view 的 live 值在此路径上不参与）
 * </pre>
 * ⇒ 五张表<b>缺一张，克隆就静默失效</b>，而失效的表现是「答案没变」，与「兜底生效」不可区分。
 * 本基座因此在 {@link Fixture} 建好后<b>强制自证「我的组件真的被渲染用上了」</b>（{@link #assertMineWasUsed}）。
 */
public abstract class S3FixtureBase {

    // ═══════════════════ 分片命名空间 ═══════════════════

    /** 分片前缀（主线分配）。 */
    protected static final String PREFIX = "R260908S3_";

    /** 本轮 JVM 唯一标记 —— 两轮运行互不撞名，也不会把上轮残留误当本轮数据。 */
    protected static final String RUN_ID = UUID.randomUUID().toString().replace("-", "").substring(0, 6);

    // ═══════════════════ 被点名的既有对象（只读引用，AC 原文写死的） ═══════════════════

    /** {@code AC-1}/{@code AC-2}/{@code AC-3} 的目标报价单（🚫 只读，本片一个字节都不写它）。 */
    protected static final String SRC_QUOTATION = "4ce0fcc4-a73b-4672-ba45-6387e03491ad";

    /** 目标单的客户。 */
    protected static final String CUSTOMER = "CUST-0004";

    /** 目标单的模板「取值测试模板2」（🚫 只读，克隆用）。 */
    protected static final String SRC_TEMPLATE = "a11b0a33-afe2-4510-92c6-d7823166ced2";

    /** 目标单的一条明细行（克隆样板，🚫 只读）。 */
    protected static final String SRC_LINE_ITEM = "c7c3bb3a-a6c4-4cf5-86df-addd703f53fa";

    /** 「产品」= 主件页签（{@code axis_scope=SELF}）。 */
    protected static final String COMP_PRODUCT = "a71947b6-8d50-4e04-8bf0-f2d4244b3cfe";
    protected static final String VIEW_PRODUCT = "builder_a71947b68d50";

    /** B 族三个页签（{@code axis_scope=CLOSURE}）—— {@code AC-3} 的被测对象。 */
    protected static final String COMP_BOM = "eb834021-ed65-4fc9-a57f-6b1662279d5d";
    protected static final String VIEW_BOM = "builder_eb834021ed65";
    protected static final String COMP_ELEMENT = "ba81142a-5ddb-4987-898f-8912d07f1095";
    protected static final String VIEW_ELEMENT = "builder_ba81142a5ddb";
    protected static final String COMP_FEE = "9cc11850-f425-45b7-8d4b-70705fde7dcf";
    protected static final String VIEW_FEE = "builder_9cc11850f425";

    @Inject
    protected EntityManager em;

    private static String cachedSession;

    /** 本轮建过的私有对象，清理时按它删（再叠一道前缀兜底）。 */
    protected static final List<Fixture> CREATED = new ArrayList<>();

    // ═══════════════════ SQL 助手 ═══════════════════

    private void bind(Query q, Object[] p) {
        for (int i = 0; i < p.length; i++) {
            q.setParameter(i + 1, p[i]);
        }
    }

    protected String scalarStr(String sql, Object... p) {
        Query q = em.createNativeQuery(sql);
        bind(q, p);
        List<?> l = q.getResultList();
        return l.isEmpty() || l.get(0) == null ? null : String.valueOf(l.get(0));
    }

    protected long scalarLong(String sql, Object... p) {
        Query q = em.createNativeQuery(sql);
        bind(q, p);
        Object o = q.getSingleResult();
        return o == null ? 0L : ((Number) o).longValue();
    }

    protected List<String> strList(String sql, Object... p) {
        Query q = em.createNativeQuery(sql);
        bind(q, p);
        List<String> out = new ArrayList<>();
        for (Object o : q.getResultList()) {
            out.add(o == null ? null : String.valueOf(o));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    protected List<Object[]> rowList(String sql, Object... p) {
        Query q = em.createNativeQuery(sql);
        bind(q, p);
        List<?> raw = q.getResultList();
        List<Object[]> out = new ArrayList<>();
        for (Object o : raw) {
            out.add(o instanceof Object[] ? (Object[]) o : new Object[]{o});
        }
        return out;
    }

    protected void exec(String sql, Object... p) {
        QuarkusTransaction.requiringNew().run(() -> {
            Query q = em.createNativeQuery(sql);
            bind(q, p);
            q.executeUpdate();
        });
    }

    /** 表的列清单（{@code information_schema} 权威）—— 克隆用，🚫 不手抄列名（漏一列就静默丢配置）。 */
    protected List<String> columnsOf(String table) {
        return strList("SELECT column_name FROM information_schema.columns "
                + "WHERE table_schema='public' AND table_name=?1 ORDER BY ordinal_position", table);
    }

    /**
     * 按主键把一行整表复制一份，{@code overrides} 里的列用给定 <b>SQL 表达式</b>替换。
     * <p>🔑 列清单从 {@code information_schema} 现取 —— 手抄列名漏一列的后果是
     * 「克隆出来的对象少一项配置」，而它<b>不会报错</b>，只会让断言得出错误结论。
     */
    protected void copyRowById(String table, String srcId, Map<String, String> overrides) {
        List<String> cols = columnsOf(table);
        assertTrue(!cols.isEmpty(), "表 " + table + " 查不到列 —— information_schema 口径不对");
        StringBuilder sel = new StringBuilder();
        for (int i = 0; i < cols.size(); i++) {
            String c = cols.get(i);
            sel.append(i == 0 ? "" : ", ").append(overrides.getOrDefault(c, "\"" + c + "\""));
        }
        String colList = String.join(", ", cols.stream().map(c -> "\"" + c + "\"").toList());
        exec("INSERT INTO " + table + " (" + colList + ") SELECT " + sel + " FROM " + table
                + " WHERE id = CAST(?1 AS uuid)", srcId);
    }

    protected static String lit(String s) {
        return s == null ? "NULL" : "'" + s.replace("'", "''") + "'";
    }

    // ═══════════════════ 登录 ═══════════════════

    protected String session() {
        if (cachedSession == null) {
            Response r = RestAssured.given().contentType(ContentType.JSON)
                    .body("{\"username\":\"admin\",\"password\":\"Admin@2026\"}")
                    .when().post("/api/cpq/auth/login");
            if (r.statusCode() != 200) {
                throw new AssertionError("[S-3] 🔴【环境未就绪，非产品缺陷】admin 登录返 " + r.statusCode()
                        + "，body=" + r.asString()
                        + "\n  依次排查：① Redis 登录限流（本类已静态缓存 session）；"
                        + "② admin 被 E2E 置成 INACTIVE（历史事故）；③ locked_until。"
                        + "\n  ⚠️ 本片全部 AC 判定为『未验证』——  skip != pass。");
            }
            cachedSession = r.cookie("CPQ_SESSION");
            assertNotNull(cachedSession, "登录 200 但没拿到 CPQ_SESSION cookie");
        }
        return cachedSession;
    }

    // ═══════════════════ 私有夹具 ═══════════════════

    /** 一套完整的私有克隆：组件 + 视图 + 模板（含两张关联表）+ 报价单 + 明细行。 */
    public record Fixture(String tag, String componentId, String viewName, String viewId,
                          String templateId, String quotationId, String lineItemId, String partNo) {
        /** 模板快照里这条视图的键：{@code <componentId>::<viewName>}。 */
        public String snapshotKey() {
            return componentId + "::" + viewName;
        }
    }

    /**
     * 造一套私有夹具。
     *
     * @param tag       本次夹具的短标签（会进对象名，便于人工在库里认领）
     * @param liveAxis  私有 {@code component_sql_view.builder_config.axisScope}：{@code "SELF"} /
     *                  {@code "CLOSURE"} / {@code null}=删掉该键
     * @param snapAxis  私有 {@code template.sql_views_snapshot.<key>.axis_scope}：同上；
     *                  传 {@link #KEEP} 表示保持克隆源的原值
     * @param partNo    明细行的销售料号
     * @param srcComp   被克隆的组件 id
     * @param srcView   被克隆的视图名
     */
    protected Fixture createFixture(String tag, String liveAxis, String snapAxis, String partNo,
                                    String srcComp, String srcView) {
        String sfx = RUN_ID + "_" + tag;
        String cid = UUID.randomUUID().toString();
        String vid = UUID.randomUUID().toString();
        String tid = UUID.randomUUID().toString();
        String tsid = UUID.randomUUID().toString();
        String qid = UUID.randomUUID().toString();
        String lid = UUID.randomUUID().toString();
        String vname = "r260908s3_" + sfx.toLowerCase();

        // ① 私有组件（driver 路径指向我自己的视图）
        copyRowById("component", srcComp, Map.of(
                "id", "CAST(" + lit(cid) + " AS uuid)",
                "code", lit(PREFIX + sfx),
                "name", lit(PREFIX + "C_" + sfx),
                "data_driver_path", "replace(data_driver_path, " + lit(srcView) + ", " + lit(vname) + ")",
                "fields", "CAST(replace(CAST(fields AS text), " + lit(srcView) + ", " + lit(vname) + ") AS jsonb)",
                "formulas", "CAST(replace(CAST(formulas AS text), " + lit(srcView) + ", " + lit(vname) + ") AS jsonb)",
                "created_at", "now()", "updated_at", "now()"));

        // ② 私有视图
        Map<String, String> vOv = new LinkedHashMap<>();
        vOv.put("id", "CAST(" + lit(vid) + " AS uuid)");
        vOv.put("component_id", "CAST(" + lit(cid) + " AS uuid)");
        vOv.put("sql_view_name", lit(vname));
        vOv.put("builder_config", liveAxis == null
                ? "(builder_config - 'axisScope')"
                : "jsonb_set(builder_config, ARRAY['axisScope'], CAST(" + lit("\"" + liveAxis + "\"") + " AS jsonb))");
        vOv.put("created_at", "now()");
        vOv.put("updated_at", "now()");
        String srcViewId = scalarStr("SELECT id::text FROM component_sql_view WHERE sql_view_name = ?1", srcView);
        assertNotNull(srcViewId, "克隆源视图 " + srcView + " 不存在 —— 环境未就绪");
        copyRowById("component_sql_view", srcViewId, vOv);

        // ③ 私有模板（含 sql_views_snapshot 里的键改名 + axis_scope 设定）
        String snapExpr = "CAST(replace(replace(CAST(sql_views_snapshot AS text), " + lit(srcView) + ", "
                + lit(vname) + "), " + lit(srcComp) + ", " + lit(cid) + ") AS jsonb)";
        copyRowById("template", SRC_TEMPLATE, Map.of(
                "id", "CAST(" + lit(tid) + " AS uuid)",
                "template_series_id", "CAST(" + lit(tsid) + " AS uuid)",
                "name", lit(PREFIX + "T_" + sfx),
                "components_snapshot", "CAST(replace(replace(CAST(components_snapshot AS text), " + lit(srcView)
                        + ", " + lit(vname) + "), " + lit(srcComp) + ", " + lit(cid) + ") AS jsonb)",
                "sql_views_snapshot", snapExpr,
                "created_at", "now()", "updated_at", "now()"));

        String key = cid + "::" + vname;
        if (!KEEP.equals(snapAxis)) {
            exec("UPDATE template SET sql_views_snapshot = " + (snapAxis == null
                            ? "jsonb_set(sql_views_snapshot, ARRAY[?2], (sql_views_snapshot -> ?2) - 'axis_scope')"
                            : "jsonb_set(sql_views_snapshot, ARRAY[?2,'axis_scope'], CAST("
                              + lit("\"" + snapAxis + "\"") + " AS jsonb))")
                    + " WHERE id = CAST(?1 AS uuid)", tid, key);
        }

        // ④ 模板的两张关联表（🔑 少了它们，克隆会静默失效 —— 见类注释）
        List<Object[]> tcs = rowList("SELECT id::text, component_id::text FROM template_component "
                + "WHERE template_id = CAST(?1 AS uuid) ORDER BY sort_order", SRC_TEMPLATE);
        assertTrue(!tcs.isEmpty(), "克隆源模板没有 template_component 行 —— 环境未就绪");
        Map<String, String> tcIdMap = new LinkedHashMap<>();
        for (Object[] r : tcs) {
            String srcTcId = String.valueOf(r[0]);
            String comp = String.valueOf(r[1]);
            String newTcId = UUID.randomUUID().toString();
            tcIdMap.put(srcTcId, newTcId);
            Map<String, String> ov = new LinkedHashMap<>();
            ov.put("id", "CAST(" + lit(newTcId) + " AS uuid)");
            ov.put("template_id", "CAST(" + lit(tid) + " AS uuid)");
            ov.put("created_at", "now()");
            if (srcComp.equals(comp)) {
                ov.put("component_id", "CAST(" + lit(cid) + " AS uuid)");
                ov.put("data_driver_path_override",
                        "nullif(replace(coalesce(data_driver_path_override,''), " + lit(srcView) + ", "
                        + lit(vname) + "), '')");
            }
            copyRowById("template_component", srcTcId, ov);
        }
        List<String> tcsSnap = strList("SELECT id::text FROM template_component_snapshot "
                + "WHERE template_id = CAST(?1 AS uuid)", SRC_TEMPLATE);
        for (String srcSnapId : tcsSnap) {
            String srcTcId = scalarStr("SELECT template_component_id::text FROM template_component_snapshot "
                    + "WHERE id = CAST(?1 AS uuid)", srcSnapId);
            String comp = scalarStr("SELECT component_id::text FROM template_component_snapshot "
                    + "WHERE id = CAST(?1 AS uuid)", srcSnapId);
            Map<String, String> ov = new LinkedHashMap<>();
            ov.put("id", "CAST(" + lit(UUID.randomUUID().toString()) + " AS uuid)");
            ov.put("template_id", "CAST(" + lit(tid) + " AS uuid)");
            String mapped = tcIdMap.get(srcTcId);
            if (mapped != null) {
                ov.put("template_component_id", "CAST(" + lit(mapped) + " AS uuid)");
            }
            if (srcComp.equals(comp)) {
                ov.put("component_id", "CAST(" + lit(cid) + " AS uuid)");
                ov.put("component_code", lit(PREFIX + sfx));
                ov.put("component_name", lit(PREFIX + "C_" + sfx));
                ov.put("data_driver_path", "replace(coalesce(data_driver_path,''), " + lit(srcView) + ", "
                        + lit(vname) + ")");
                ov.put("fields", "CAST(replace(CAST(fields AS text), " + lit(srcView) + ", " + lit(vname)
                        + ") AS jsonb)");
            }
            copyRowById("template_component_snapshot", srcSnapId, ov);
        }

        // ⑤ 私有报价单 + 明细行（🔑 customer_template_id 必须指向我的模板，否则整套克隆被绕过）
        copyRowById("quotation", SRC_QUOTATION, Map.of(
                "id", "CAST(" + lit(qid) + " AS uuid)",
                "quotation_number", lit("QT-" + PREFIX + sfx),
                "name", lit(PREFIX + "Q_" + sfx),
                "status", lit("DRAFT"),
                "submission_snapshot", "NULL",
                "referenced_versions", "NULL",
                "customer_template_id", "CAST(" + lit(tid) + " AS uuid)",
                "created_at", "now()", "updated_at", "now()"));
        Map<String, String> lOv = new LinkedHashMap<>();
        lOv.put("id", "CAST(" + lit(lid) + " AS uuid)");
        lOv.put("quotation_id", "CAST(" + lit(qid) + " AS uuid)");
        lOv.put("template_id", "CAST(" + lit(tid) + " AS uuid)");
        lOv.put("product_part_no_snapshot", lit(partNo));
        lOv.put("sort_order", "0");
        lOv.put("created_at", "now()");
        for (String c : List.of("quote_card_values", "quote_excel_values", "costing_card_values",
                "costing_excel_values", "card_snapshot_at", "quote_values_at", "excel_view_snapshot",
                "deleted_tree_nodes")) {
            lOv.put(c, "NULL");
        }
        copyRowById("quotation_line_item", SRC_LINE_ITEM, lOv);

        Fixture f = new Fixture(tag, cid, vname, vid, tid, qid, lid, partNo);
        CREATED.add(f);
        System.out.println("[S-3·fixture] " + tag + " comp=" + cid + " view=" + vname
                + " tpl=" + tid + " quo=" + qid + " part=" + partNo
                + " liveAxis=" + liveAxis + " snapAxis=" + snapAxis);
        return f;
    }

    /** {@code snapAxis} 传它表示「保持克隆源原值」。 */
    protected static final String KEEP = " KEEP";

    // ═══════════════════ 渲染 + 读数 ═══════════════════

    /** 触发一次整单重渲染（{@code POST .../refresh-snapshot}），返回 HTTP 码。 */
    protected int render(Fixture f) {
        Response r = RestAssured.given().cookie("CPQ_SESSION", session())
                .contentType(ContentType.JSON)
                .when().post("/api/cpq/configure-product/quotations/{q}/refresh-snapshot", f.quotationId());
        System.out.println("[S-3·render] " + f.tag() + " → HTTP " + r.statusCode()
                + " body=" + trunc(r.asString()));
        return r.statusCode();
    }

    /** 某组件页签渲染出的行数（{@code snapshot_rows} 长度）。返回 -1 表示该页签根本没落数据行。 */
    protected long renderedRowCount(Fixture f, String componentId) {
        String v = scalarStr("SELECT jsonb_array_length(coalesce(d.snapshot_rows, '[]'::jsonb)) "
                + "FROM quotation_line_component_data d JOIN quotation_line_item l ON l.id = d.line_item_id "
                + "WHERE l.quotation_id = CAST(?1 AS uuid) AND d.component_id = CAST(?2 AS uuid)",
                f.quotationId(), componentId);
        return v == null ? -1L : Long.parseLong(v);
    }

    /** 某组件页签渲染出的每行 {@code hf_part_no}（排序后，可直接做集合比较）。 */
    protected List<String> renderedPartNos(Fixture f, String componentId) {
        return strList("SELECT r->'driverRow'->>'hf_part_no' "
                + "FROM quotation_line_component_data d JOIN quotation_line_item l ON l.id = d.line_item_id, "
                + "     jsonb_array_elements(d.snapshot_rows) r "
                + "WHERE l.quotation_id = CAST(?1 AS uuid) AND d.component_id = CAST(?2 AS uuid) "
                + "ORDER BY 1", f.quotationId(), componentId);
    }

    /** 该页签整段渲染结果的规范化文本（用于「逐位相同」类断言）。 */
    protected String renderedRowsText(Fixture f, String componentId) {
        return scalarStr("SELECT coalesce(d.snapshot_rows, '[]'::jsonb)::text "
                + "FROM quotation_line_component_data d JOIN quotation_line_item l ON l.id = d.line_item_id "
                + "WHERE l.quotation_id = CAST(?1 AS uuid) AND d.component_id = CAST(?2 AS uuid)",
                f.quotationId(), componentId);
    }

    protected boolean hasRenderError(Fixture f) {
        Long n = Long.valueOf(scalarStr("SELECT count(*) FROM quotation_line_component_data d "
                + "JOIN quotation_line_item l ON l.id = d.line_item_id "
                + "WHERE l.quotation_id = CAST(?1 AS uuid) AND d.snapshot_rows::text LIKE '%renderError%'",
                f.quotationId()));
        return n != null && n > 0;
    }

    /**
     * 🚨 <b>夹具自证：我的私有组件真的被渲染用上了。</b>
     *
     * <p>本片实测过的最危险失效形态：克隆没接上时，渲染<b>照常成功</b>、行数<b>照常合理</b>，
     * 只是用的是共享模板。此时无论我怎么改 {@code axis_scope}，答案都不变 ——
     * 而「答案不变」正好就是「兜底生效」的长相。⇒ 每套夹具建好后必须先过这一关。
     */
    protected void assertMineWasUsed(Fixture f) {
        long n = scalarLong("SELECT count(*) FROM quotation_line_component_data d "
                + "JOIN quotation_line_item l ON l.id = d.line_item_id "
                + "WHERE l.quotation_id = CAST(?1 AS uuid) AND d.component_id = CAST(?2 AS uuid)",
                f.quotationId(), f.componentId());
        assertEquals(1L, n, "[夹具自证] " + f.tag() + " 渲染结果里找不到我的私有组件 " + f.componentId()
                + "\n  ⇒ 私有克隆没接上，渲染走的是共享模板；此时任何 axis_scope 断言都是零证据"
                + "（三种取值会给出同一个数字，长得像『兜底生效』）。"
                + "\n  排查顺序：quotation.customer_template_id → template_component →"
                + " template_component_snapshot → template.sql_views_snapshot 的键名。");
    }

    // ═══════════════════ 清理与残留核验 ═══════════════════

    /**
     * 删掉本轮造的全部私有对象。命中面被本轮 {@link #RUN_ID} 限死。
     *
     * <p>🚨 <b>匹配串一律用 {@code %<RUN_ID>%}，不要用 {@code PREFIX + RUN_ID + '%'}</b>：
     * 各类对象的命名是 {@code R260908S3_T_<run>_<tag>} / {@code R260908S3_<run>_<tag>} 等<b>不同形状</b>，
     * 前缀式匹配会漏掉一部分。2026-09-09 实测：模板名漏配 ⇒ {@code template_component} 残留 ⇒
     * <b>外键挡住 component 的删除</b>，而那个异常被 {@code finally} 里的残留断言<b>顶替掉</b>，
     * 报出来的是「清理未净」而不是真正的外键错误。
     *
     * <p>删除顺序 = 外键依赖的逆序，🚫 不许调换。
     */
    protected void destroyAll() {
        String run = "%" + RUN_ID + "%";
        String vRun = "%" + RUN_ID.toLowerCase() + "%";
        exec("DELETE FROM quotation_line_component_data WHERE line_item_id IN "
                + "(SELECT id FROM quotation_line_item WHERE quotation_id IN "
                + "(SELECT id FROM quotation WHERE quotation_number LIKE ?1))", run);
        exec("DELETE FROM quotation_view_structure WHERE quotation_id IN "
                + "(SELECT id FROM quotation WHERE quotation_number LIKE ?1)", run);
        exec("DELETE FROM quotation_component_sql_snapshot WHERE quotation_id IN "
                + "(SELECT id FROM quotation WHERE quotation_number LIKE ?1)", run);
        exec("DELETE FROM quotation_line_item WHERE quotation_id IN "
                + "(SELECT id FROM quotation WHERE quotation_number LIKE ?1)", run);
        exec("DELETE FROM quotation WHERE quotation_number LIKE ?1", run);
        exec("DELETE FROM template_component_snapshot WHERE template_id IN "
                + "(SELECT id FROM template WHERE name LIKE ?1)", run);
        exec("DELETE FROM template_component WHERE template_id IN "
                + "(SELECT id FROM template WHERE name LIKE ?1)", run);
        exec("DELETE FROM template WHERE name LIKE ?1", run);
        exec("DELETE FROM component_sql_view WHERE sql_view_name LIKE ?1", vRun);
        exec("DELETE FROM component WHERE code LIKE ?1", run);
        exec("DELETE FROM ds_quote_material_bom WHERE material_no LIKE ?1 OR input_material_no LIKE ?1", run);
        exec("DELETE FROM ds_quote_material WHERE material_no LIKE ?1", run);
        CREATED.clear();
    }

    /** 🚨 清理后的残留核验 —— 只查本轮命名空间，🚫 不做全局计数。 */
    protected void assertResidueFree() {
        Map<String, Long> left = new LinkedHashMap<>();
        String run = "%" + RUN_ID + "%";
        left.put("quotation", scalarLong("SELECT count(*) FROM quotation WHERE quotation_number LIKE ?1", run));
        left.put("template", scalarLong("SELECT count(*) FROM template WHERE name LIKE ?1", run));
        // 🚫 不查「全库孤儿 tc 行」——那是全局计数，并发线的残留会把我打红。只查引用我的组件的那些。
        left.put("template_component", scalarLong("SELECT count(*) FROM template_component tc "
                + "WHERE tc.component_id IN (SELECT id FROM component WHERE code LIKE ?1)", run));
        left.put("template_component_snapshot", scalarLong("SELECT count(*) FROM template_component_snapshot s "
                + "WHERE s.component_id IN (SELECT id FROM component WHERE code LIKE ?1)", run));
        left.put("component", scalarLong("SELECT count(*) FROM component WHERE code LIKE ?1", run));
        left.put("component_sql_view", scalarLong("SELECT count(*) FROM component_sql_view "
                + "WHERE sql_view_name LIKE ?1", "%" + RUN_ID.toLowerCase() + "%"));
        left.put("ds_quote_material", scalarLong("SELECT count(*) FROM ds_quote_material "
                + "WHERE material_no LIKE ?1", run));
        left.put("ds_quote_material_bom", scalarLong("SELECT count(*) FROM ds_quote_material_bom "
                + "WHERE material_no LIKE ?1 OR input_material_no LIKE ?1", run));
        System.out.println("── [S-3] 清理后残留核验（本轮命名空间 " + RUN_ID + "）──");
        left.forEach((k, v) -> System.out.println("   " + k + " = " + v));
        left.forEach((k, v) -> assertEquals(0L, v.longValue(), "清理未净：" + k + " 还剩 " + v + " 行"));
    }

    protected static String trunc(String s) {
        return s == null ? "null" : (s.length() > 800 ? s.substring(0, 800) + "…(截断)" : s);
    }
}
