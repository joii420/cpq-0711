package com.cpq.task260907r;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>T-13② —— AC-13② 列维度守卫</b>：被表征行里<b>页签没暴露的列</b>必须逐字未变（🚫 不得写 NULL）。
 *
 * <h3>为什么要单独造一个组件</h3>
 * 现网组件「T260907-物料与元素BOM」有 <b>12</b> 个字段 = 主表 {@code ds_quote_element_bom} 的
 * <b>12</b> 个业务列 ⇒ 「未暴露的列」是<b>空集</b> ⇒ 断言会 0 次循环恒真（假绿，不是覆盖）。
 * ⇒ 本类自造一个<b>只暴露 5 列</b>的 ds 组件 + 专属模板，让另外 <b>7 列</b>成为「未暴露的列」。
 *
 * <h3>🚫 为什么不能用 {@code inRollback}</h3>
 * 主线建议走 {@code inRollback}（构造性零残留）。<b>这条路在本场景结构上不成立</b>：
 * 夹具要经 <b>HTTP</b>（{@code PUT /draft} / {@code GET preview}）驱动，
 * 而 HTTP 请求跑在<b>自己的事务</b>里，<b>看不见未提交的组件/模板</b>。
 * 本任务早期已实证过同型约束（{@code RecordFixtureSelfTest} 的 committed 夹具就是这么来的）。
 * ⇒ 只能 committed + 前缀清理；清理覆盖 {@code template_component / template /
 * component_sql_view / component} 四张配置表，见 {@link #cleanupConfig()}。
 *
 * <h3>🚨 不碰共享配置</h3>
 * 🚫 <b>不往现网模板 {@code df379593} 上挂组件</b> —— 那是别的会话在用的共享配置，
 * 属 {@code testing.md} §4.3「不得改变全局状态」。本类<b>另建</b>一套 {@code T260907R-} 模板，纯增量。
 */
@QuarkusTest
@DisplayName("自造组件专项 · AC-13②列维度 / AC-3 extend_column")
class PartialColumnScopeAcTest extends Task260907RBase {

    /** 源组件（只读复制模板，🚫 不修改它）。 */
    private static final UUID SRC_COMPONENT = UUID.fromString("196aadee-b89f-4f81-984b-4c6747b59149");
    private static final String SRC_VIEW = "builder_196aadeeb89f";

    /** 只保留这 5 列 ⇒ 其余 7 列成为「页签未暴露的列」。 */
    private static final List<String> KEEP = List.of(
            "material_no", "material_part_no", "item_seq", "element_code", "content_pct");
    /** 🚨 AC-13② 的被验对象：这 7 列必须逐字未变、🚫 不得写 NULL。 */
    private static final List<String> UNEXPOSED = List.of(
            "loss_rate", "gross_usage", "gross_usage_unit",
            "net_usage", "net_usage_unit", "recovery_discount", "recovery_qty");

    /** 主表没有对应物理列的字段名 ⇒ 它只能进 extend_column。 */
    private static final String EXTRA_FIELD = "T260907R-自定义列A";

    private UUID newComponentId;
    private UUID newTemplateId;
    private String newViewName;

    @AfterEach
    void tearDown() {
        cleanupOwnFixtures();
        cleanupConfig();
    }

    @Test
    @DisplayName("T-13② · 页签只暴露 5 列 → 另 7 列逐字未变且不为 NULL；preserved 非空")
    void t13b_unexposedColumnsPreserved() {
        requireRecordLayer();

        // ══ 前置 1：先用**现网全列模板**把主表这一组造出来（12 列都有值）
        //    ⇒ 之后才谈得上「未暴露的 7 列有没有被抹掉」。全 NULL 的话断言仍是空跑。
        String mat = PREFIX + "COL-" + UUID.randomUUID().toString().substring(0, 6);
        seedMainViaCreatedOrder("AC13bSeed", mat, List.of(
                new EbomRow(1, PREFIX + "EL1", "10.0", "1.1"),
                new EbomRow(2, PREFIX + "EL2", "20.0", "2.2")));

        Map<String, String> unexposedBefore = unexposedValues(mat);
        assertFixtureNonEmpty(unexposedBefore.size(), "未暴露列的取值集");
        long nulls = unexposedBefore.values().stream().filter(v -> v == null || "~".equals(v)).count();
        assertEquals(0L, nulls,
                "🚨 阳性对照失败：未暴露的 7 列里有 " + nulls + " 个是 NULL/空 ⇒ 「不得写 NULL」这条断言会空跑"
                        + "（分不清「本来就空」和「被抹成空」）。实际=" + unexposedBefore);
        System.out.println("[T-13②] 未暴露 7 列的基线 = " + unexposedBefore);

        // ══ 前置 2：造只暴露 5 列的组件 + 专属模板
        buildPartialColumnTemplate();

        // ══ 操作：用部分列模板建单，只改 content_pct（5 列里的一个），提交并确认
        Fx fx = newFixture("AC13b");
        requireStatusBeforeDiff(saveDraftPartial(fx, mat), 200, "T-13② saveDraft（部分列模板）");
        requireStatusBeforeDiff(submit(fx), 200, "T-13② submit");

        JsonNode g = findGroup(dsBackfill(ok(getPreview(fx.quotationId()), "T-13② 预览")), EBOM, mat);
        assertNotNull(g, "T-13②：预览里应出现轴值 " + mat + " 的组（组件没绑上则说明自造模板不成立）");
        System.out.println("[T-13②] 预览组形状 = " + g);

        // 🚨 阳性对照（主线明确要求）：preserved 必须**非空**，否则造组件这件事白做，
        //    后面的「未暴露列未变」仍是 0 次循环恒真。
        List<String> preserved = new ArrayList<>();
        g.path("columnScope").path("preserved").forEach(n -> preserved.add(n.asText()));
        assertFalse(preserved.isEmpty(),
                "🚨 阳性对照失败：自造了只暴露 5 列的组件，但 columnScope.preserved 仍为空 ⇒ "
                        + "「未暴露的列」集合没有真的形成，AC-13② 仍然验不了。group=" + g);
        for (String c : UNEXPOSED) {
            assertTrue(preserved.contains(c),
                    "T-13②：未暴露列 " + c + " 应出现在 columnScope.preserved 里，实际 preserved=" + preserved);
        }

        approveWithPreview(fx, "AC13b");

        // ══ 🚨 AC-13②：未暴露的 7 列逐字未变，且不得被写 NULL
        Map<String, String> unexposedAfter = unexposedValues(mat);
        List<String> broken = new ArrayList<>();
        for (Map.Entry<String, String> e : unexposedBefore.entrySet()) {
            String now = unexposedAfter.get(e.getKey());
            if (!e.getValue().equals(now)) broken.add(e.getKey() + ": " + e.getValue() + " → " + now);
        }
        assertTrue(broken.isEmpty(),
                "🚨 AC-13②：被表征行里页签没暴露的列必须逐字未变（🚫 不得写 NULL）。实测 "
                        + broken.size() + " 处变化 —— " + broken
                        + "。AP-60 实证：element_bom_item.base_qty 由 0.624610 变 NULL 而预览显示 0 变更。");

        // ══ 反向：暴露并改了的那一列确实变了（防修成「什么都不写」）
        Map<Integer, String> pct = new LinkedHashMap<>();
        for (Object[] r : rows("SELECT item_seq, coalesce(content_pct::text,'~') FROM " + EBOM
                + " WHERE material_no = '" + mat + "' ORDER BY item_seq")) {
            pct.put(((Number) r[0]).intValue(), String.valueOf(r[1]));
        }
        assertTrue(String.valueOf(pct.get(1)).startsWith("88"),
                "T-13② 反向：暴露并改成 88.8 的 content_pct 应写入，实际 " + pct);
    }

    // ─────────────────────────── 自造配置 ───────────────────────────

    /** 复制源组件 → 砍到 5 列 → 建专属模板并挂上。🚫 全程不修改源组件/源模板。 */
    private void buildPartialColumnTemplate() {
        newComponentId = UUID.randomUUID();
        newTemplateId = UUID.randomUUID();
        newViewName = "builder_" + newComponentId.toString().replace("-", "").substring(0, 12);

        Object seriesId = scalar("SELECT template_series_id FROM template WHERE id = '" + DS_TEMPLATE_ID + "'");
        assertNotNull(seriesId, "前置：源模板的 template_series_id 读不到");

        inTx(() -> {
            // ① component：整行复制，只改 id / name / code / fields / data_driver_path
            em.createNativeQuery(
                            "INSERT INTO component (id, name, code, tab_type, data_driver_path, fields, "
                                    + "  status, created_at, updated_at) "
                                    + "SELECT :nid, :nm, :cd, tab_type, :ddp, "
                                    + "  (SELECT jsonb_agg(f ORDER BY (f->>'sort_order')::int) FROM jsonb_array_elements(fields) f "
                                    + "   WHERE f->>'name' IN (:f1,:f2,:f3,:f4,:f5)), "
                                    + "  status, now(), now() FROM component WHERE id = :src")
                    .setParameter("nid", newComponentId)
                    .setParameter("nm", PREFIX + "部分列元素BOM")
                    .setParameter("cd", PREFIX + newComponentId.toString().substring(0, 8))
                    .setParameter("ddp", "$" + newViewName)
                    .setParameter("f1", "销售料号").setParameter("f2", "材质料号").setParameter("f3", "项次")
                    .setParameter("f4", "元素").setParameter("f5", "组成含量（%）")
                    .setParameter("src", SRC_COMPONENT)
                    .executeUpdate();

            // ② component_sql_view：builder_config.columns 过滤到 5 列；
            //    🔑 tabType / variantKey / dialect 等坐标**原样照抄** —— 改了就解不出锚表。
            em.createNativeQuery(
                            "INSERT INTO component_sql_view (id, component_id, sql_view_name, sql_template, "
                                    + "  declared_columns, required_variables, scope, status, builder_config, builder_version, created_at) "
                                    + "SELECT gen_random_uuid(), :nid, :vn, sql_template, declared_columns, "
                                    + "  required_variables, scope, status, "
                                    + "  jsonb_set(builder_config, '{columns}', "
                                    + "    (SELECT jsonb_agg(c) FROM jsonb_array_elements(builder_config->'columns') c "
                                    + "     WHERE c->>'sourceColumn' IN (:c1,:c2,:c3,:c4,:c5))), "
                                    + "  builder_version, now() FROM component_sql_view WHERE sql_view_name = :src")
                    .setParameter("nid", newComponentId)
                    .setParameter("vn", newViewName)
                    .setParameter("c1", KEEP.get(0)).setParameter("c2", KEEP.get(1))
                    .setParameter("c3", KEEP.get(2)).setParameter("c4", KEEP.get(3))
                    .setParameter("c5", KEEP.get(4))
                    .setParameter("src", SRC_VIEW)
                    .executeUpdate();

            // ③ template：复制源模板行（🚫 不改源模板），只挂我这一个组件
            em.createNativeQuery(
                            "INSERT INTO template (id, template_series_id, name, status, version, created_at, updated_at) "
                                    + "VALUES (:tid, CAST(:sid AS uuid), :nm, 'PUBLISHED', 'v1.0', now(), now())")
                    .setParameter("tid", newTemplateId)
                    .setParameter("sid", seriesId.toString())
                    .setParameter("nm", PREFIX + "部分列模板-" + newTemplateId.toString().substring(0, 6))
                    .executeUpdate();
            em.createNativeQuery(
                            "INSERT INTO template_component (id, template_id, component_id, sort_order) "
                                    + "VALUES (gen_random_uuid(), :tid, :cid, 0)")
                    .setParameter("tid", newTemplateId).setParameter("cid", newComponentId)
                    .executeUpdate();
        });

        long cols = count("SELECT jsonb_array_length(builder_config->'columns') FROM component_sql_view "
                + "WHERE sql_view_name = '" + newViewName + "'");
        assertEquals(5L, cols, "自造组件的 builder_config.columns 应为 5 列，实际 " + cols);
        System.out.println("[T-13②] 已造部分列组件 " + newComponentId + " / 视图 " + newViewName
                + " / 模板 " + newTemplateId + "（columns=" + cols + "）");
    }

    private io.restassured.response.Response saveDraftPartial(Fx fx, String mat) {
        // 只给 5 个字段，且把 组成含量（%） 改成 88.8
        String rowData = "[{\"销售料号\":\"" + mat + "\",\"材质料号\":\"" + PREFIX + "MAT\","
                + "\"项次\":\"1\",\"元素\":\"" + PREFIX + "EL1\",\"组成含量（%）\":\"88.8\"}]";
        String body = "{\"baseVersion\":0,\"added\":[{"
                + "\"id\":null,\"tempId\":\"" + PREFIX + "tp\","
                + "\"templateId\":\"" + newTemplateId + "\","
                + "\"sortOrder\":0,\"compositeType\":\"SIMPLE\","
                + "\"productPartNo\":\"" + mat + "\",\"annualVolume\":1,"
                + "\"componentData\":[{\"componentId\":\"" + newComponentId + "\","
                + "\"tabName\":\"" + PREFIX + "部分列元素BOM\","
                + "\"rowData\":" + jsonStr(rowData) + ",\"sortOrder\":0}]}],"
                + "\"modified\":[],\"removed\":[]}";
        return io.restassured.RestAssured.given().cookies(adminCookies())
                .contentType(io.restassured.http.ContentType.JSON).body(body)
                .when().put("/api/cpq/quotations/" + fx.quotationId() + "/draft").thenReturn();
    }

    /** {@code seq.列名 -> 值}，只取未暴露的 7 列。 */
    private Map<String, String> unexposedValues(String mat) {
        Map<String, String> out = new LinkedHashMap<>();
        StringBuilder sel = new StringBuilder("SELECT item_seq");
        for (String c : UNEXPOSED) sel.append(", coalesce(").append(c).append("::text,'~')");
        sel.append(" FROM ").append(EBOM).append(" WHERE material_no = '")
           .append(mat.replace("'", "''")).append("' ORDER BY item_seq, id");
        for (Object[] r : rows(sel.toString())) {
            for (int i = 0; i < UNEXPOSED.size(); i++) {
                out.put(r[0] + "." + UNEXPOSED.get(i), String.valueOf(r[i + 1]));
            }
        }
        return out;
    }

    private JsonNode findGroup(JsonNode ds, String tableName, String axisValue) {
        for (JsonNode t : ds.path("tables")) {
            if (!tableName.equals(t.path("tableName").asText())) continue;
            for (JsonNode g : t.path("groups")) {
                if (axisValue.equals(g.path("axisValue").asText())) return g;
            }
        }
        return null;
    }

    // ═══════════════════════ T-03 · AC-3 extend_column ═══════════════════════

    /**
     * <b>T-03（AC-3）</b>：{@code extend_column} <b>只留痕，不参与任何写主表的动作</b>。
     *
     * <h3>为什么要自造组件（现网验不了）</h3>
     * ds 原生模板的 13 个组件<b>每个字段都被 builder 映射到物理列</b> ⇒ {@code extendFields} 恒空
     * ⇒ {@code extend_column} 恒 <code>{}</code> ⇒ 三条断言全会空跑。
     * ⇒ 本条沿用 AC-13② 那次证明可行的手法：<b>自造一个带「主表没有的字段」的组件</b>
     * （字段进 {@code component.fields}，但<b>不进</b> {@code builder_config.columns}）。
     *
     * <p>🚨 <b>阳性对照先行</b>：先断言 {@code extend_column} <b>非空</b>，再谈「不进主表、不进指纹」——
     * 否则造完组件而 {@code extend_column} 仍是 <code>{}</code> 时，后两条断言依旧恒真。
     */
    @Test
    @DisplayName("T-03 · 自定义列进 extend_column；不渗主表；只改它 → UNCHANGED 不升版")
    void t03_extendColumnLeavesNoTraceInMainTable() {
        requireRecordLayer();

        String mat = PREFIX + "EXT-" + UUID.randomUUID().toString().substring(0, 6);
        List<EbomRow> rows = List.of(new EbomRow(1, PREFIX + "EL1", "10.0", "1.1"));
        seedMainViaCreatedOrder("AC3Seed", mat, rows);

        int verBefore = intVal("SELECT max(version_no) FROM " + EBOM + " WHERE material_no = '" + mat + "'");
        List<Object> mainColsBefore = col("SELECT column_name FROM information_schema.columns "
                + "WHERE table_schema='public' AND table_name='" + EBOM + "' ORDER BY column_name");
        assertFixtureNonEmpty(mainColsBefore.size(), "主表列集基线");

        // ── 自造：12 列照旧全映射，但**多一个没有 sourceColumn 的字段**
        buildComponentWithExtraField();

        String customValue = PREFIX + "自定义值-" + UUID.randomUUID().toString().substring(0, 6);
        Fx fx = newFixture("AC3");
        requireStatusBeforeDiff(saveDraftWithExtra(fx, mat, customValue), 200, "T-03 saveDraft");

        // ══ 🚨 阳性对照：extend_column 必须非空，否则本条整体是空跑 ══
        long hit = count("SELECT count(*) FROM " + EBOM + "_record WHERE quotation_id = '"
                + fx.quotationId() + "' AND extend_column::text LIKE '%" + customValue + "%'");
        assertFixtureNonEmpty(hit,
                "🚨 阳性对照失败：自造了带「主表没有的字段」的组件，但 extend_column 里找不到该值 '"
                        + customValue + "' ⇒ 「不进主表 / 不进指纹」两条断言会恒真恒通过，本条等于没验。"
                        + " 实际 extend_column 取值="
                        + col("SELECT extend_column::text FROM " + EBOM + "_record WHERE quotation_id = '"
                        + fx.quotationId() + "'"));
        System.out.println("[T-03] AC-3① 阳性：extend_column 命中 " + hit + " 行，值=" + customValue);

        // ══ AC-3②：主表没新增列，且该值没渗进主表任何一列 ══
        assertEquals(mainColsBefore,
                col("SELECT column_name FROM information_schema.columns "
                        + "WHERE table_schema='public' AND table_name='" + EBOM + "' ORDER BY column_name"),
                "AC-3②：主表 " + EBOM + " 不应因自定义列而新增列");
        long leaked = count("SELECT count(*) FROM " + EBOM + " x WHERE x.material_no = '" + mat + "' "
                + "AND to_jsonb(x)::text LIKE '%" + customValue + "%'");
        assertEquals(0L, leaked,
                "AC-3②：自定义列的值渗进了主表某一列（命中 " + leaked + " 行）—— extend_column 应只留痕");

        // ══ AC-3③：只改自定义列 ⇒ 该组判 UNCHANGED、version_no 不变 ══
        requireStatusBeforeDiff(submit(fx), 200, "T-03 submit");
        JsonNode g = findGroup(dsBackfill(ok(getPreview(fx.quotationId()), "T-03 预览")), EBOM, mat);
        assertNotNull(g, "T-03：预览里应出现轴值 " + mat + " 的组");
        System.out.println("[T-03] 预览组 = " + g);
        assertEquals("UNCHANGED", g.path("result").asText(),
                "AC-3③：只有自定义列有值（它不进 row_fingerprint）⇒ 该组应判 UNCHANGED。"
                        + "实际 " + g.path("result").asText() + " ⇒ extend_column 被算进指纹了。group=" + g);

        long histBefore = count("SELECT count(*) FROM " + EBOM + "_history WHERE material_no = '" + mat + "'");
        approveWithPreview(fx, "AC3");
        assertEquals(verBefore, intVal("SELECT max(version_no) FROM " + EBOM
                        + " WHERE material_no = '" + mat + "'"),
                "AC-3③：UNCHANGED ⇒ version_no 不变（应仍为 v" + verBefore + "）");
        assertEquals(histBefore, count("SELECT count(*) FROM " + EBOM + "_history WHERE material_no = '"
                        + mat + "'"),
                "AC-3③：UNCHANGED 不应往 _history 写行");
    }

    /** 复制源组件，12 列映射照旧，但 {@code fields} 里<b>多一个没有物理列的字段</b>。 */
    private void buildComponentWithExtraField() {
        newComponentId = UUID.randomUUID();
        newTemplateId = UUID.randomUUID();
        newViewName = "builder_" + newComponentId.toString().replace("-", "").substring(0, 12);
        Object seriesId = scalar("SELECT template_series_id FROM template WHERE id = '" + DS_TEMPLATE_ID + "'");
        assertNotNull(seriesId, "前置：源模板 template_series_id 读不到");

        inTx(() -> {
            em.createNativeQuery(
                            "INSERT INTO component (id,name,code,tab_type,data_driver_path,fields,status,created_at,updated_at) "
                                    + "SELECT :nid,:nm,:cd,tab_type,:ddp, "
                                    + "  fields || jsonb_build_array(jsonb_build_object("
                                    + "    'name', :extra, 'field_type','INPUT_TEXT','sort_order',99,"
                                    + "    'is_amount',false,'is_subtotal',false,'notes','','content','')), "
                                    + "  status, now(), now() FROM component WHERE id = :src")
                    .setParameter("nid", newComponentId)
                    .setParameter("nm", PREFIX + "带自定义列元素BOM")
                    .setParameter("cd", PREFIX + newComponentId.toString().substring(0, 8))
                    .setParameter("ddp", "$" + newViewName)
                    .setParameter("extra", EXTRA_FIELD)
                    .setParameter("src", SRC_COMPONENT)
                    .executeUpdate();
            // builder_config 原样照抄（12 列全映射）⇒ 多出来那个字段没有物理列 ⇒ 只能进 extend_column
            em.createNativeQuery(
                            "INSERT INTO component_sql_view (id,component_id,sql_view_name,sql_template,"
                                    + "  declared_columns,required_variables,scope,status,builder_config,builder_version,created_at) "
                                    + "SELECT gen_random_uuid(),:nid,:vn,sql_template,declared_columns,"
                                    + "  required_variables,scope,status,builder_config,builder_version,now() "
                                    + "FROM component_sql_view WHERE sql_view_name = :src")
                    .setParameter("nid", newComponentId).setParameter("vn", newViewName)
                    .setParameter("src", SRC_VIEW).executeUpdate();
            em.createNativeQuery(
                            "INSERT INTO template (id,template_series_id,name,status,version,created_at,updated_at) "
                                    + "VALUES (:tid, CAST(:sid AS uuid), :nm, 'PUBLISHED','v1.0', now(), now())")
                    .setParameter("tid", newTemplateId).setParameter("sid", seriesId.toString())
                    .setParameter("nm", PREFIX + "自定义列模板-" + newTemplateId.toString().substring(0, 6))
                    .executeUpdate();
            em.createNativeQuery("INSERT INTO template_component (id,template_id,component_id,sort_order) "
                            + "VALUES (gen_random_uuid(), :tid, :cid, 0)")
                    .setParameter("tid", newTemplateId).setParameter("cid", newComponentId).executeUpdate();
        });

        long nf = count("SELECT jsonb_array_length(fields) FROM component WHERE id = '" + newComponentId + "'");
        long nc = count("SELECT jsonb_array_length(builder_config->'columns') FROM component_sql_view "
                + "WHERE sql_view_name = '" + newViewName + "'");
        assertEquals(13L, nf, "自造组件应有 13 个字段（12 映射 + 1 自定义），实际 " + nf);
        assertEquals(12L, nc, "builder_config.columns 应仍是 12 列，实际 " + nc);
        System.out.println("[T-03] 已造组件：fields=" + nf + " / builder columns=" + nc
                + " ⇒ 多出的「" + EXTRA_FIELD + "」没有物理列");
    }

    private io.restassured.response.Response saveDraftWithExtra(Fx fx, String mat, String customValue) {
        String rowData = "[{\"销售料号\":\"" + mat + "\",\"材质料号\":\"" + PREFIX + "MAT\","
                + "\"项次\":\"1\",\"元素\":\"" + PREFIX + "EL1\",\"组成含量（%）\":\"10.0\","
                + "\"损耗率%\":\"1\",\"毛用量\":\"2.5\",\"毛用量单位\":\"kg\","
                + "\"净用量\":\"1.1\",\"净用量单位\":\"kg\",\"回收折扣(%)\":\"10\",\"回收量\":\"0.1\","
                + "\"" + EXTRA_FIELD + "\":\"" + customValue + "\"}]";
        String body = "{\"baseVersion\":0,\"added\":[{"
                + "\"id\":null,\"tempId\":\"" + PREFIX + "tx\","
                + "\"templateId\":\"" + newTemplateId + "\","
                + "\"sortOrder\":0,\"compositeType\":\"SIMPLE\","
                + "\"productPartNo\":\"" + mat + "\",\"annualVolume\":1,"
                + "\"componentData\":[{\"componentId\":\"" + newComponentId + "\","
                + "\"tabName\":\"" + PREFIX + "带自定义列元素BOM\","
                + "\"rowData\":" + jsonStr(rowData) + ",\"sortOrder\":0}]}],"
                + "\"modified\":[],\"removed\":[]}";
        return io.restassured.RestAssured.given().cookies(adminCookies())
                .contentType(io.restassured.http.ContentType.JSON).body(body)
                .when().put("/api/cpq/quotations/" + fx.quotationId() + "/draft").thenReturn();
    }

    private int intVal(String sql) {
        Object v = scalar(sql);
        assertNotNull(v, "查询无结果：" + sql);
        return ((Number) v).intValue();
    }

    /** 清掉本类自造的四张配置表的行（先 count 再删，只删本轮 id）。 */
    private void cleanupConfig() {
        if (newComponentId == null && newTemplateId == null) return;
        inTx(() -> {
            if (newTemplateId != null) {
                em.createNativeQuery("DELETE FROM template_component WHERE template_id = :t")
                        .setParameter("t", newTemplateId).executeUpdate();
                em.createNativeQuery("DELETE FROM template WHERE id = :t AND name LIKE :p")
                        .setParameter("t", newTemplateId).setParameter("p", PREFIX + "%").executeUpdate();
            }
            if (newComponentId != null) {
                em.createNativeQuery("DELETE FROM component_sql_view WHERE component_id = :c")
                        .setParameter("c", newComponentId).executeUpdate();
                em.createNativeQuery("DELETE FROM component WHERE id = :c AND name LIKE :p")
                        .setParameter("c", newComponentId).setParameter("p", PREFIX + "%").executeUpdate();
            }
        });
        newComponentId = null;
        newTemplateId = null;
    }
}
