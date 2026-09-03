package com.cpq.task260901;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * task-260901 · 接口层验收：<b>材质 / 元素组成 / 含量配置的 CRUD 契约</b>
 * （T-I-13 ~ T-I-17 / T-I-24 / T-I-26 / T-I-27）。
 *
 * <p>断言全部派生自 {@code 需求文档.md §③} 的 AC 原文 + {@code api.md §2.1 / §2.2}。<b>不读实现</b>。
 *
 * <p>🚨 <b>本类专攻「绕过前端直接打接口」这一层</b>：AC-31 / AC-34 的原文都写了
 * 「后端必须同样拦」—— 前端置灰只是体验，后端拦才是正确性。E2E 里点不出来的路径全在这里。
 */
@QuarkusTest
class MaterialRecipeConfigApiTest extends MaterialAcTestBase {

    private static final String BASE = "/api/cpq/material-recipes";

    // ═════════════════ AC-14 / AC-15 / AC-26：配置的增删与发号 ═════════════════

    /** T-I-13 → AC-14：在真实材质 00006 上新建配置 ⇒ 编号 00006-03？不 —— 清理后基线只剩 -01，故为 00006-02。 */
    @Test
    void tI13_createConfigOnRealRecipe_allocatesNextConfigNo() {
        String recipeId = recipeIdByCode(REAL_RECIPE_CODE);
        List<String> before = activeConfigNos(REAL_RECIPE_CODE);
        assertEquals(List.of("00006-01"), before,
            "前置：清理后 00006 只剩存量迁移的 00006-01，实际=" + before);

        JsonPath created = postConfig(recipeId, cfgBody(null, "Ag", "75", "Ni", "25"), 200, 201);
        String configNo = created.getString("configNo");
        System.out.println("[AC-14] 新建配置 configNo = " + configNo);
        assertEquals("00006-02", configNo, "AC-14/M-1：configNo = <材质编号>-%02d(max(seq)+1)");

        assertEquals(List.of("00006-01", "00006-02"), activeConfigNos(REAL_RECIPE_CODE),
            "AC-14：矩阵应立即多出一行");
        assertEquals(2, count("SELECT count(*) FROM material_recipe_config c JOIN material_recipe r ON r.id=c.recipe_id "
                + "WHERE r.code='" + REAL_RECIPE_CODE + "' AND c.status='ACTIVE'"),
            "AC-14：ACTIVE 配置数 = 2");

        List<String> pcts = strList(
            "SELECT e.element_code || '=' || e.default_pct::text FROM material_recipe_element e " +
            "JOIN material_recipe_config c ON c.id=e.config_id WHERE c.config_no='00006-02' ORDER BY 1");
        assertNonEmpty(pcts, "AC-14 新配置的含量");
        assertEquals(List.of("Ag=75.000000000000", "Ni=25.000000000000"), pcts, "AC-14：75 / 25 落库");
    }

    /**
     * T-I-14 → AC-15：删除 = 软删；物理行保留、状态转 INACTIVE；<b>编号不回收</b>。
     * <p>🚨 本用例是 FT-1 证伪实验的靶子：把发号的 max(seq) 改成只统计 ACTIVE，这里必须变红。
     */
    @Test
    void tI14_deleteConfigIsSoftDelete_andSeqNotRecycled() {
        String recipeId = recipeIdByCode(REAL_RECIPE_CODE);
        String c02 = postConfig(recipeId, cfgBody(null, "Ag", "85", "Ni", "15"), 200, 201).getString("configNo");
        String c03 = postConfig(recipeId, cfgBody(null, "Ag", "75", "Ni", "25"), 200, 201).getString("configNo");
        assertEquals("00006-02", c02);
        assertEquals("00006-03", c03);

        String c02Id = scalar("SELECT id::text FROM material_recipe_config WHERE config_no='00006-02'");
        RestAssured.given().delete(BASE + "/" + recipeId + "/configs/" + c02Id)
            .then().statusCode(200);

        assertEquals(List.of("00006-01", "00006-03"), activeConfigNos(REAL_RECIPE_CODE),
            "AC-15：矩阵剩 -01 与 -03");
        assertEquals(2, count("SELECT count(*) FROM material_recipe_config c JOIN material_recipe r ON r.id=c.recipe_id "
                + "WHERE r.code='" + REAL_RECIPE_CODE + "' AND c.status='ACTIVE'"),
            "AC-15：ACTIVE 计数 = 2");
        assertEquals(3, count("SELECT count(*) FROM material_recipe_config c JOIN material_recipe r ON r.id=c.recipe_id "
                + "WHERE r.code='" + REAL_RECIPE_CODE + "'"),
            "AC-15：不带状态过滤仍 = 3（物理行保留）");
        assertEquals("INACTIVE", scalar("SELECT status FROM material_recipe_config WHERE config_no='00006-02'"),
            "AC-15：软删 ⇒ status='INACTIVE'");

        String c04 = postConfig(recipeId, cfgBody(null, "Ag", "60", "Ni", "40"), 200, 201).getString("configNo");
        System.out.println("[AC-15] 删 -02 后再建，得到 = " + c04);
        assertEquals("00006-04", c04,
            "AC-15：🚨 编号不回收 —— 再新建应得 00006-04 而不是复用 00006-02");

        // 幂等（api.md §2.2）：已 INACTIVE 再删返 200
        RestAssured.given().delete(BASE + "/" + recipeId + "/configs/" + c02Id).then().statusCode(200);

        // 🚨 补强（2026-09-02 由 FT-1 证伪实验驱动）：上面删的是**中间**那条 -02，
        //    此时 max(ACTIVE seq)=3 恰好等于 max(全部 seq)=3 —— 把发号改成「只统计 ACTIVE」
        //    行为完全不变，用例照样绿。⇒ 上面那段**证伪不了**「编号不回收」。
        //    真正锁住水位的场景是删掉**当前最大**那条：删 -04 后若只数 ACTIVE，
        //    max 会退回 3 → 重发已用过的 00006-04。
        //    🚫 别把这段删了「精简」—— 没有它，AC-15 的「编号不回收」是空验。
        String c04Id = scalar("SELECT id::text FROM material_recipe_config WHERE config_no='00006-04'");
        assertNotNull(c04Id, "前置：00006-04 应已存在");
        RestAssured.given().delete(BASE + "/" + recipeId + "/configs/" + c04Id).then().statusCode(200);
        String c05 = postConfig(recipeId, cfgBody(null, "Ag", "55", "Ni", "45"), 200, 201).getString("configNo");
        System.out.println("[AC-15·水位] 删掉当前最大的 -04 后再建，得到 = " + c05);
        assertEquals("00006-05", c05,
            "AC-15/M-2：🚨 seq 水位不释放 —— 删掉最大那条后再建应得 00006-05，"
            + "得到 00006-04 说明发号只数了 ACTIVE，编号会被回收重发");
    }

    /**
     * T-I-26b → AC-26（边界，接口层）：某材质已有 99 条配置，再建一条 ⇒ 编号扩位为三位 `-100`。
     * <p>前置态用 SQL 把「唯一那条配置」的 seq 直接抬到 99（只动本用例自建的 AC测% 材质），
     * 免去发 99 次 HTTP；发号器仍走真实接口。
     * <p>🚨 判据兼验 M-1 的「不得用 PG lpad(x,2,'0')」—— 那会把 '100' 截成 '10'。
     */
    @Test
    void tI26b_configNoWidensToThreeDigitsAt100() {
        JsonPath r = createRecipe(AC_PREFIX + "扩位", List.of(cfg("Ag", "90", "Ni", "10")), 200, 201);
        String recipeId = r.getString("id");
        String code = r.getString("code");
        assertNotNull(code);

        QuarkusTransaction.requiringNew().run(() -> em.createNativeQuery(
                "UPDATE material_recipe_config SET seq = 99, config_no = :cn WHERE recipe_id = CAST(:rid AS uuid)")
            .setParameter("cn", code + "-99").setParameter("rid", recipeId).executeUpdate());

        String next = postConfig(recipeId, cfgBody(null, "Ag", "85", "Ni", "15"), 200, 201).getString("configNo");
        System.out.println("[AC-26] 99 → next = " + next);
        assertEquals(code + "-100", next,
            "AC-26/M-1：seq=100 时自然给出三位 `-100`，不报错、不覆盖 -01、不被截成 -10");
    }

    // ═════════════════ AC-16 / AC-17：自定义含量开关 与 0 配置态 ═════════════════

    /** T-I-15 → AC-16：新建材质开关默认关；置 true 后持久化。 */
    @Test
    void tI15_allowCustomContentDefaultsFalse_andPersists() {
        JsonPath r = createRecipe(AC_PREFIX + "开关", List.of(cfg("Ag", "90", "Ni", "10")), 200, 201);
        String id = r.getString("id");
        assertEquals(false, r.getBoolean("allowCustomContent"),
            "AC-16/M-5：新建材质（含导入自动创建的）一律默认关");
        assertEquals("false", scalar("SELECT allow_custom_content::text FROM material_recipe "
                + "WHERE id = CAST('" + id + "' AS uuid)"), "AC-16：库内默认 false");

        RestAssured.given().contentType(ContentType.JSON)
            .body(Map.of("symbol", AC_PREFIX + "开关", "recipeType", "locked", "allowCustomContent", true))
            .put(BASE + "/" + id).then().statusCode(200);

        JsonPath after = RestAssured.given().get(BASE + "/" + id).then().statusCode(200).extract().jsonPath();
        System.out.println("[AC-16] 重新打开后 allowCustomContent = " + after.getBoolean("allowCustomContent"));
        assertEquals(true, after.getBoolean("allowCustomContent"), "AC-16：重新打开抽屉该开关仍为开");
        assertEquals("true", scalar("SELECT allow_custom_content::text FROM material_recipe "
                + "WHERE id = CAST('" + id + "' AS uuid)"), "AC-16：库内该字段为 true");
    }

    /**
     * T-I-16 → AC-17：删光配置后，<b>元素组成仍在</b>（M-0：组成是材质的显式属性，不是配置的派生物）；
     * 列表项的 elementCodes 照常有值（BC-2b）；此时开自定义含量 ⇒ 409 CUSTOM_CONTENT_NEEDS_CONFIG。
     */
    @Test
    void tI16_recipeWithZeroConfigs_keepsComposition() {
        JsonPath r = createRecipe(AC_PREFIX + "新材", List.of(cfg("Ag", "70", "Cu", "30")), 200, 201);
        String id = r.getString("id");
        String cfgId = scalar("SELECT id::text FROM material_recipe_config WHERE recipe_id = CAST('" + id + "' AS uuid)");
        assertNotNull(cfgId, "前置：应有一条配置");

        RestAssured.given().delete(BASE + "/" + id + "/configs/" + cfgId).then().statusCode(200);

        JsonPath detail = RestAssured.given().get(BASE + "/" + id).then().statusCode(200).extract().jsonPath();
        System.out.println("[AC-17] detail = " + detail.prettify());
        List<Map<String, Object>> comp = detail.getList("composition");
        assertNonEmpty(comp, "AC-17 0 配置材质的 composition");
        assertEquals(List.of("Ag", "Cu"), comp.stream().map(m -> String.valueOf(m.get("elementCode"))).toList(),
            "AC-17：元素组成区照常显示 Ag、Cu 两项");
        assertEquals(0, detail.getList("configs").size(), "AC-17：ACTIVE 配置为空");
        assertEquals(0, detail.getInt("configCount"), "AC-17：configCount=0 ⇒ 列表显示「未配置含量」");

        List<String> compDb = strList("SELECT element_code FROM material_recipe_composition "
                + "WHERE recipe_id = CAST('" + id + "' AS uuid) ORDER BY sort_order");
        assertEquals(List.of("Ag", "Cu"), compDb, "AC-17：组成表未随配置消失");

        Response bad = RestAssured.given().contentType(ContentType.JSON)
            .body(Map.of("symbol", AC_PREFIX + "新材", "recipeType", "locked", "allowCustomContent", true))
            .put(BASE + "/" + id).thenReturn();
        System.out.println("[AC-17] 0 配置开自定义 → " + bad.statusCode() + " " + bad.asString());
        assertEquals(409, bad.statusCode(), "AC-17/api.md：0 配置时开自定义含量应 409");
        assertTrue(bad.asString().contains("CUSTOM_CONTENT_NEEDS_CONFIG")
                || bad.asString().contains("尚未配置任何含量"),
            "AC-17：错误码/文案应为 CUSTOM_CONTENT_NEEDS_CONFIG，实际=" + bad.asString());
    }

    // ═════════════════ AC-31：元素组成的两段式可改性（M-0b） ═════════════════

    /**
     * T-I-24 → AC-31：无 ACTIVE 配置时元素组成可改；有配置后 <b>后端必须拦</b>（409 COMPOSITION_LOCKED）。
     * <p>🚨 AC-31 原文：「直接调 PUT 接口绕过前端时后端必须同样拦，返 409」——
     * 只验前端置灰不算验过这条。
     */
    @Test
    void tI24_compositionEditableOnlyWhenNoActiveConfig() {
        JsonPath r = createRecipe(AC_PREFIX + "新材", List.of(cfg("Ag", "70", "Cu", "30")), 200, 201);
        String id = r.getString("id");
        String cfgId = scalar("SELECT id::text FROM material_recipe_config WHERE recipe_id = CAST('" + id + "' AS uuid)");
        RestAssured.given().delete(BASE + "/" + id + "/configs/" + cfgId).then().statusCode(200);

        // ① 无 ACTIVE 配置 ⇒ 把 Cu 换成 Ni，保存成功
        Response ok = RestAssured.given().contentType(ContentType.JSON)
            .body(Map.of("symbol", AC_PREFIX + "新材", "recipeType", "locked",
                "composition", List.of(
                    Map.of("elementNo", elementNo("Ag"), "sortOrder", 1),
                    Map.of("elementNo", elementNo("Ni"), "sortOrder", 2))))
            .put(BASE + "/" + id).thenReturn();
        System.out.println("[AC-31①] " + ok.statusCode() + " " + ok.asString());
        assertEquals(200, ok.statusCode(), "AC-31①：无 ACTIVE 配置时元素组成可自由增删改");
        List<String> comp = strList("SELECT element_code FROM material_recipe_composition "
                + "WHERE recipe_id = CAST('" + id + "' AS uuid) ORDER BY sort_order");
        assertEquals(List.of("Ag", "Ni"), comp, "AC-31①：组成变为 Ag + Ni，实际=" + comp);

        // ② 新组成下建一条配置
        JsonPath c = postConfig(id, cfgBody(null, "Ag", "70", "Ni", "30"), 200, 201);
        assertNotNull(c.getString("configNo"), "AC-31②：与新组成匹配的配置应保存成功");

        // ③ 有 ACTIVE 配置 ⇒ 再改组成必须 409
        Response locked = RestAssured.given().contentType(ContentType.JSON)
            .body(Map.of("symbol", AC_PREFIX + "新材", "recipeType", "locked",
                "composition", List.of(
                    Map.of("elementNo", elementNo("Ag"), "sortOrder", 1),
                    Map.of("elementNo", elementNo("Cu"), "sortOrder", 2))))
            .put(BASE + "/" + id).thenReturn();
        System.out.println("[AC-31③] " + locked.statusCode() + " " + locked.asString());
        assertEquals(409, locked.statusCode(), "AC-31③：🚨 有 ACTIVE 配置后后端必须拦，返 409");
        assertTrue(locked.asString().contains("COMPOSITION_LOCKED"),
            "AC-31③：错误码须为 COMPOSITION_LOCKED，实际=" + locked.asString());
        assertEquals(List.of("Ag", "Ni"),
            strList("SELECT element_code FROM material_recipe_composition "
                + "WHERE recipe_id = CAST('" + id + "' AS uuid) ORDER BY sort_order"),
            "AC-31③：被拦后组成不得被改掉");
    }

    /** 配套 AC-14：绕过前端直接打接口时，配置的元素集合必须与元素组成相等（api.md CONFIG_ELEMENT_SET_MISMATCH）。 */
    @Test
    void tI24b_configElementSetMustEqualComposition() {
        String recipeId = recipeIdByCode(REAL_RECIPE_CODE);   // 组成 = Ag + Ni
        Response more = RestAssured.given().contentType(ContentType.JSON)
            .body(cfgBody(null, "Ag", "50", "Cu", "50")).post(BASE + "/" + recipeId + "/configs").thenReturn();
        System.out.println("[CONFIG_ELEMENT_SET_MISMATCH] " + more.statusCode() + " " + more.asString());
        assertEquals(400, more.statusCode(), "元素集合与组成不等 ⇒ 400");
        assertTrue(more.asString().contains("CONFIG_ELEMENT_SET_MISMATCH"),
            "错误码须为 CONFIG_ELEMENT_SET_MISMATCH，实际=" + more.asString());
        assertEquals(List.of("00006-01"), activeConfigNos(REAL_RECIPE_CODE), "被拒的配置不得落库");
    }

    // ═════════════════ AC-33 / AC-34：新建材质 = 配方卡片 ═════════════════

    /** T-I-26 → AC-33：POST 一次建成 材质 + 元素组成（取自配方1 的元素与顺序）+ 两条配置。 */
    @Test
    void tI26_createRecipeWithTwoConfigCards() {
        String base = baselineMaxCode5;
        JsonPath r = createRecipe(AC_PREFIX + "配方卡",
            List.of(cfg("Ag", "90", "Ni", "10"), cfg("Ag", "85", "Ni", "15")), 200, 201);
        String id = r.getString("id");
        String code = r.getString("code");
        System.out.println("[AC-33] code = " + code + "（基线 " + base + "）");
        assertEquals(nextCode(base, 1), code, "AC-33③：材质编号 = 基线+1");

        assertEquals(1, count("SELECT count(*) FROM material_recipe WHERE id = CAST('" + id + "' AS uuid)"),
            "AC-33③：material_recipe 恰 1 行");

        List<String> comp = strList("SELECT element_code || '#' || sort_order FROM material_recipe_composition "
                + "WHERE recipe_id = CAST('" + id + "' AS uuid) ORDER BY sort_order");
        assertNonEmpty(comp, "AC-33 元素组成");
        assertEquals(List.of("Ag#1", "Ni#2"), comp,
            "AC-33③：组成恰两行 Ag(sort_order=1) / Ni(sort_order=2)，取自配方1 的元素及其顺序，实际=" + comp);

        List<String> cfgs = strList("SELECT config_no FROM material_recipe_config "
                + "WHERE recipe_id = CAST('" + id + "' AS uuid) ORDER BY seq");
        assertEquals(List.of(code + "-01", code + "-02"), cfgs,
            "AC-33③：material_recipe_config 恰两行 -01 / -02，实际=" + cfgs);

        JsonPath detail = RestAssured.given().get(BASE + "/" + id).then().statusCode(200).extract().jsonPath();
        assertEquals(2, detail.getInt("configCount"), "AC-33④：列表「含量配置」列显示 2 组");
        assertEquals(List.of("Ag", "Ni"), detail.getList("elementCodes"),
            "AC-33④：列表「元素组成」列显示 Ag / Ni");
    }

    /**
     * T-I-27 → AC-34：各配方卡片元素种类不一致 ⇒ 整体拒绝（400），
     * <b>材质 / 组成 / 配置三张表都 0 行，且不消耗材质编号</b>。
     * <p>🚨 FT-4 证伪靶子：AC-34（UI 入口）与 AC-32（导入入口）必须共用同一份判据（M-0a）。
     */
    @Test
    void tI27_inconsistentConfigCards_rejectedWholesale() {
        String base = baselineMaxCode5;
        Response res = RestAssured.given().contentType(ContentType.JSON)
            .body(Map.of("symbol", AC_PREFIX + "配方卡", "recipeType", "locked",
                "configs", List.of(cfg("Ag", "50", "Ni", "50"), cfg("Ag", "50", "Cu", "50"))))
            .post(BASE).thenReturn();
        System.out.println("[AC-34] " + res.statusCode() + " " + res.asString());

        assertEquals(400, res.statusCode(), "AC-34：后端必须同样拦，返 400");
        String body = res.asString();
        assertTrue(body.contains("COMPOSITION_INCONSISTENT_ACROSS_CONFIGS"),
            "AC-34：错误码须为 COMPOSITION_INCONSISTENT_ACROSS_CONFIGS，实际=" + body);
        assertTrue(body.contains("配方1") && body.contains("配方2"),
            "AC-34：文案须指名道姓到具体配方，实际=" + body);
        assertTrue(body.contains("Ag") && body.contains("Ni") && body.contains("Cu"),
            "AC-34：文案须列出两边的元素集合（配方1={Ag, Ni}，配方2={Ag, Cu}），实际=" + body);

        assertEquals(0, count("SELECT count(*) FROM material_recipe WHERE symbol='" + AC_PREFIX + "配方卡'"),
            "AC-34：material_recipe 0 行");
        assertEquals(0, count("SELECT count(*) FROM material_recipe_composition c "
                + "JOIN material_recipe r ON r.id=c.recipe_id WHERE r.symbol='" + AC_PREFIX + "配方卡'"),
            "AC-34：material_recipe_composition 0 行");
        assertEquals(0, count("SELECT count(*) FROM material_recipe_config c "
                + "JOIN material_recipe r ON r.id=c.recipe_id WHERE r.symbol='" + AC_PREFIX + "配方卡'"),
            "AC-34：material_recipe_config 0 行");
        assertEquals(base, maxRecipeCode5(), "AC-34：🚨 保存失败不消耗材质编号（max 应仍是 " + base + "）");
    }

    // ═════════════════ AC-30 的存储/接口侧一半 ═════════════════

    /**
     * T-I-28 → AC-30（存储与接口精度不变）：去零只发生在渲染层。
     * <p>🚨 FT-3b 证伪靶子：把去零函数换成会真改值的实现（{@code Number(s).toString()}），
     * 这里的 SQL 断言必须变红。
     */
    @Test
    void tI28_trailingZeroStrippingMustNotTouchStorageOrApi() {
        String recipeId = recipeIdByCode(REAL_RECIPE_CODE);
        JsonPath created = postConfig(recipeId,
            cfgBody(null, "Ag", "12.345678901200", "Ni", "87.654321098800"), 200, 201);
        String configNo = created.getString("configNo");

        List<String> db = strList(
            "SELECT e.element_code || '=' || e.default_pct::text FROM material_recipe_element e " +
            "JOIN material_recipe_config c ON c.id=e.config_id WHERE c.config_no='" + configNo + "' ORDER BY 1");
        assertNonEmpty(db, "AC-30 库内原始值");
        assertEquals(List.of("Ag=12.345678901200", "Ni=87.654321098800"), db,
            "AC-30：库内仍是 numeric(16,12) 的完整 12 位，去零不得改变存储");

        // 同时验「整数含量也要原样存成 12 位」——这是 AC-30 显示 `91%` 的反面。
        // ⚠️ 这里用 91/9 而不是 90/10：90/10 与存量配置 00006-01 逐值相同，
        //    会被 M-4 的 CONFIG_DUPLICATED 正确拒掉（2026-09-02 首跑实测到，是本用例的数据错，
        //    不是产品缺陷）。🚫 别"顺手"改回 90/10。
        JsonPath ninety = postConfig(recipeId, cfgBody(null, "Ag", "91", "Ni", "9"), 200, 201);
        assertEquals("91.000000000000",
            scalar("SELECT e.default_pct::text FROM material_recipe_element e " +
                   "JOIN material_recipe_config c ON c.id=e.config_id " +
                   "WHERE c.config_no='" + ninety.getString("configNo") + "' AND e.element_code='Ag'"),
            "AC-30：提交 `91` 后库内仍是 91.000000000000（整数入、12 位存）");

        List<Map<String, Object>> apiEls = ninety.getList("elements");
        assertNonEmpty(apiEls, "AC-30 接口出参 elements");
        String agPct = apiEls.stream().filter(m -> "Ag".equals(m.get("elementCode")))
            .map(m -> String.valueOf(m.get("defaultPct"))).findFirst().orElse(null);
        System.out.println("[AC-30] 接口出参 Ag defaultPct = " + agPct);
        assertEquals("91.000000000000", agPct,
            "AC-30：接口出参仍是完整字符串 —— 去零发生在渲染那一层，不发生在接口里");
    }

    // ═════════════════ 辅助 ═════════════════

    private String recipeIdByCode(String code) {
        String id = scalar("SELECT id::text FROM material_recipe WHERE code='" + code + "'");
        assertNotNull(id, "前置：材质 " + code + " 应存在于 test 库");
        return id;
    }

    private String elementNo(String symbol) {
        String no = scalar("SELECT element_no FROM element WHERE element_code='" + symbol + "' LIMIT 1");
        assertNotNull(no, "前置：element 主表应已有 " + symbol);
        return no;
    }

    private List<String> activeConfigNos(String recipeCode) {
        return strList("SELECT c.config_no FROM material_recipe_config c JOIN material_recipe r ON r.id=c.recipe_id "
            + "WHERE r.code='" + recipeCode + "' AND c.status='ACTIVE' ORDER BY c.seq");
    }

    /** api.md §2.2 的配置请求体：elements[].elementNo + elements[].defaultPct。 */
    private Map<String, Object> cfgBody(String remark, String s1, String p1, String s2, String p2) {
        return Map.of(
            "remark", remark == null ? "" : remark,
            "elements", List.of(
                Map.of("elementNo", elementNo(s1), "defaultPct", p1),
                Map.of("elementNo", elementNo(s2), "defaultPct", p2)));
    }

    /**
     * api.md §2.1 的 POST /material-recipes 里的一张「配方卡片」。
     * <p>字段名 <b>统一为 {@code defaultPct}</b>（主线 2026-09-02 定稿：api.md §2.1 初稿误写成
     * {@code pct}，已更正为与 §2.2 配置端点一致）。🚫 {@code pct} 只在 §2.4 的选配请求里用
     * （那是既有代码字段名），材质与配置两侧的写入一律 {@code defaultPct}。
     */
    private Map<String, Object> cfg(String s1, String p1, String s2, String p2) {
        return Map.of("elements", List.of(
            Map.of("elementNo", elementNo(s1), "defaultPct", p1),
            Map.of("elementNo", elementNo(s2), "defaultPct", p2)));
    }

    private JsonPath createRecipe(String symbol, List<Map<String, Object>> configs, int... okCodes) {
        Response res = RestAssured.given().contentType(ContentType.JSON)
            .body(Map.of("symbol", symbol, "name", symbol, "recipeType", "locked", "configs", configs))
            .post(BASE).thenReturn();
        assertOneOf(res, okCodes, "新建材质 " + symbol);
        return res.jsonPath();
    }

    private JsonPath postConfig(String recipeId, Map<String, Object> body, int... okCodes) {
        Response res = RestAssured.given().contentType(ContentType.JSON).body(body)
            .post(BASE + "/" + recipeId + "/configs").thenReturn();
        assertOneOf(res, okCodes, "新建配置");
        return res.jsonPath();
    }

    private void assertOneOf(Response res, int[] okCodes, String what) {
        for (int c : okCodes) if (res.statusCode() == c) return;
        throw new AssertionError(what + " 期望状态 " + java.util.Arrays.toString(okCodes)
            + "，实际 " + res.statusCode() + " body=" + res.asString());
    }
}
