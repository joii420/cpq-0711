package com.cpq.configure.service;

import com.cpq.configure.dto.CompositionItemDTO;
import com.cpq.configure.dto.ElementDTO;
import com.cpq.configure.dto.MaterialRecipeConfigDTO;
import com.cpq.configure.dto.MaterialRecipeConfigUpsertRequest;
import com.cpq.configure.dto.MaterialRecipeDTO;
import com.cpq.configure.dto.MaterialRecipeUpsertRequest;
import com.cpq.configure.entity.Element;
import com.cpq.configure.entity.MaterialRecipeConfig;
import com.cpq.configure.entity.MaterialRecipeElement;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * task-260901 · B-19 回归保障（<b>不改功能，只加测试</b>）。
 *
 * <p>锁住三条「元素行归属从材质下沉到配置」之后最容易静默漂移的不变量：
 * <ol>
 *   <li>{@code ElementService} 的「被引用数 / 符号锁」按 {@code element_no} 聚合 ——
 *       元素行改挂 {@code config_id} 后计数必须照样准（那两处查询不含 recipe_id，本用例把它钉死）；</li>
 *   <li>配置<b>软删</b>只改状态：元素行物理保留、{@code element_bom_item} 零影响
 *       ⇒ 已落到报价单的含量不随材质库配置删除而改变（AC-22 的后端一半）；</li>
 *   <li>AC-30 的<b>反向</b>断言：去尾随零只发生在渲染层 ——
 *       提交 {@code "90"} 后库内与接口出参都必须是 {@code 90.000000000000}。</li>
 * </ol>
 *
 * <p>全部 {@code @TestTransaction}，跑完回滚，不落任何数据。
 */
@QuarkusTest
public class MaterialRecipeConfigRegressionTest {

    @Inject
    MaterialRecipeService recipeService;

    @Inject
    MaterialRecipeConfigService configService;

    @Inject
    ElementService elementService;

    @Inject
    EntityManager em;

    private String elementNo(String symbol) {
        Element e = Element.<Element>find("elementCode", symbol).firstResult();
        assertNotNull(e, "前置：element 主表应已有 " + symbol);
        return e.elementNo;
    }

    /** 建一条「Ag 90 / Ni 10」的材质（一组配置）。 */
    private MaterialRecipeDTO seedRecipe(String symbol) {
        MaterialRecipeUpsertRequest r = new MaterialRecipeUpsertRequest();
        r.symbol = symbol;
        r.recipeType = "locked";
        MaterialRecipeUpsertRequest.ElementUpsert ag = new MaterialRecipeUpsertRequest.ElementUpsert();
        ag.elementNo = elementNo("Ag");
        ag.defaultPct = new BigDecimal("90");
        MaterialRecipeUpsertRequest.ElementUpsert ni = new MaterialRecipeUpsertRequest.ElementUpsert();
        ni.elementNo = elementNo("Ni");
        ni.defaultPct = new BigDecimal("10");
        MaterialRecipeUpsertRequest.ConfigUpsert g = new MaterialRecipeUpsertRequest.ConfigUpsert();
        g.elements = List.of(ag, ni);
        r.configs = List.of(g);
        return recipeService.create(r);
    }

    private long refCountOf(String symbol) {
        return elementService.list(symbol).stream()
            .filter(d -> symbol.equals(d.elementCode))
            .map(d -> d.referencedCount)
            .findFirst().orElseThrow(() -> new AssertionError("element 列表里找不到 " + symbol));
    }

    private boolean codeLockedOf(String symbol) {
        return elementService.list(symbol).stream()
            .filter(d -> symbol.equals(d.elementCode))
            .map(d -> d.codeLocked)
            .findFirst().orElseThrow(() -> new AssertionError("element 列表里找不到 " + symbol));
    }

    // ── ① 被引用数 / 符号锁 ──

    @Test
    @TestTransaction
    void elementReferencedCount_stillCountsRowsHangingOnConfigs() {
        long before = refCountOf("Ag");
        assertTrue(before >= 0);

        seedRecipe("UTREG被引用");
        em.flush();

        long after = refCountOf("Ag");
        assertEquals(before + 1, after,
            "元素行改挂 config_id 后，ElementService 的被引用数仍应把它算进去"
                + "（那条 SQL 按 element_no 聚合，不含 recipe_id）");
        assertTrue(codeLockedOf("Ag"), "被引用 ⇒ 符号锁生效，不可改符号");

        // 反面：软删配置不释放引用（元素行物理还在）
        MaterialRecipeConfigDTO cfg = configService.listConfigDTOs(
            recipeService.list("UTREG被引用", false).stream()
                .filter(d -> "UTREG被引用".equals(d.symbol)).findFirst().orElseThrow().id, false).get(0);
        UUID recipeId = recipeService.list("UTREG被引用", false).stream()
            .filter(d -> "UTREG被引用".equals(d.symbol)).findFirst().orElseThrow().id;
        configService.deleteConfig(recipeId, cfg.id);
        em.flush();
        assertEquals(before + 1, refCountOf("Ag"),
            "软删只改状态、元素行物理保留 ⇒ 被引用数不变（否则符号锁会在删配置后被意外解除）");
    }

    // ── ② 软删不动数据（AC-22 的后端一半）──

    @Test
    @TestTransaction
    void softDeletingConfig_keepsElementRows_andDoesNotTouchElementBom() {
        long bomBefore = ((Number) em.createNativeQuery(
            "SELECT count(*) FROM element_bom_item").getSingleResult()).longValue();

        MaterialRecipeDTO dto = seedRecipe("UTREG软删");
        UUID recipeId = dto.id;
        MaterialRecipeConfigDTO cfg = configService.listConfigDTOs(recipeId, false).get(0);
        UUID configId = cfg.id;

        List<MaterialRecipeElement> before = MaterialRecipeElement
            .<MaterialRecipeElement>find("configId = ?1 ORDER BY sortOrder", configId).list();
        assertEquals(2, before.size(), "前置：该配置应有 2 行元素");

        configService.deleteConfig(recipeId, configId);
        em.flush();
        em.clear();

        MaterialRecipeConfig reloaded = MaterialRecipeConfig.findById(configId);
        assertNotNull(reloaded, "AC-15/M-2：物理行必须保留");
        assertEquals("INACTIVE", reloaded.status, "软删 ⇒ status=INACTIVE");

        List<MaterialRecipeElement> after = MaterialRecipeElement
            .<MaterialRecipeElement>find("configId = ?1 ORDER BY sortOrder", configId).list();
        assertEquals(2, after.size(), "AC-22：元素行不得被连带删除");
        assertEquals(0, after.get(0).defaultPct.compareTo(before.get(0).defaultPct), "含量值不变");

        assertEquals(0, configService.listConfigDTOs(recipeId, false).size(),
            "默认只列 ACTIVE ⇒ 软删后列表为空");
        assertEquals(1, configService.listConfigDTOs(recipeId, true).size(),
            "includeInactive=true 时仍能看到它");

        long bomAfter = ((Number) em.createNativeQuery(
            "SELECT count(*) FROM element_bom_item").getSingleResult()).longValue();
        assertEquals(bomBefore, bomAfter,
            "AC-22：删材质库配置不得触碰 element_bom_item —— 已落到报价单的含量与字典侧解耦");
    }

    // ── ③ AC-30 反向：去零不进存储、不进接口 ──

    @Test
    @TestTransaction
    void trailingZeroStripping_neverReachesStorageOrApi() {
        MaterialRecipeDTO dto = seedRecipe("UTREG去零");
        MaterialRecipeConfigDTO cfg = configService.listConfigDTOs(dto.id, false).get(0);

        String agApi = cfg.elements.stream().filter(e -> "Ag".equals(e.elementCode))
            .map(e -> e.defaultPct).findFirst().orElseThrow();
        assertEquals("90.000000000000", agApi,
            "AC-30 反向：接口出参是完整 12 位字符串，去零发生在渲染那一层");
        assertEquals("100.000000000000", cfg.totalPct, "合计同样是完整精度");

        String agDb = em.createNativeQuery(
                "SELECT e.default_pct::text FROM material_recipe_element e " +
                "WHERE e.config_id = CAST(:cid AS uuid) AND e.element_code = 'Ag'")
            .setParameter("cid", cfg.id.toString())
            .getSingleResult().toString();
        assertEquals("90.000000000000", agDb, "AC-30 反向：库内仍是 numeric(16,12) 的完整值");

        // 12 位小数无损（AC-8 的 CRUD 侧对照）
        MaterialRecipeConfigUpsertRequest req = new MaterialRecipeConfigUpsertRequest();
        MaterialRecipeConfigUpsertRequest.ElementInput a = new MaterialRecipeConfigUpsertRequest.ElementInput();
        a.elementNo = elementNo("Ag");
        a.defaultPct = new BigDecimal("12.345678901200");
        MaterialRecipeConfigUpsertRequest.ElementInput b = new MaterialRecipeConfigUpsertRequest.ElementInput();
        b.elementNo = elementNo("Ni");
        b.defaultPct = new BigDecimal("87.654321098800");
        req.elements = List.of(a, b);
        MaterialRecipeConfigDTO created = configService.createConfig(dto.id, req);

        assertEquals("12.345678901200", created.elements.stream()
            .filter(e -> "Ag".equals(e.elementCode)).map(e -> e.defaultPct).findFirst().orElseThrow(),
            "12 位小数无损");
        assertEquals("100.000000000000", created.totalPct, "两值相加恰 = 100.000000000000");
    }

    // ── ④ B-21 / AC-36：元素展示值走权威链 element_no → element 主表 ──

    /**
     * AC-36：{@code material_recipe_composition / material_recipe_element} 上的
     * {@code element_code / element_name} 只是<b>快照</b>，权威链是 {@code element_no}
     * （{@code task-0709 · B2}）。历史脏数据里有整行串位的（材质 {@code 00262}：
     * {@code element_no=10004 / element_code=10004 / element_name=Sn}，而主表 {@code 10004 = Sn / 锡}），
     * 直接渲染快照列就会在列表里显示成 {@code 10004}。
     *
     * <p>本用例<b>在事务内人工构造同款串位</b>（不依赖 dev 库那条随时可能被用户改掉的真实脏行），
     * 断言列表 / 详情 / 配置元素三处都返回主表的权威值；并断言<b>读路径不回写快照</b>（AC-36 反向断言）。
     */
    @Test
    @TestTransaction
    void elementDisplayValues_comeFromElementMaster_notFromSnapshotColumns() {
        String agNo = elementNo("Ag");
        Element agMaster = Element.<Element>find("elementCode", "Ag").firstResult();
        MaterialRecipeDTO dto = seedRecipe("UTREG串位");
        UUID recipeId = dto.id;
        em.flush();

        // 人工构造「整行串位」：编号填进符号列、符号填进名称列（与 00262 同款）
        em.createNativeQuery(
                "UPDATE material_recipe_composition SET element_code = element_no, element_name = 'Ag' " +
                "WHERE recipe_id = CAST(:rid AS uuid) AND element_no = :no")
            .setParameter("rid", recipeId.toString()).setParameter("no", agNo).executeUpdate();
        em.createNativeQuery(
                "UPDATE material_recipe_element e SET element_code = e.element_no, element_name = 'Ag' " +
                "FROM material_recipe_config c " +
                "WHERE c.id = e.config_id AND c.recipe_id = CAST(:rid AS uuid) AND e.element_no = :no")
            .setParameter("rid", recipeId.toString()).setParameter("no", agNo).executeUpdate();
        em.flush();
        em.clear();
        assertEquals(agNo, em.createNativeQuery(
                "SELECT element_code FROM material_recipe_composition " +
                "WHERE recipe_id = CAST(:rid AS uuid) AND element_no = :no")
            .setParameter("rid", recipeId.toString()).setParameter("no", agNo)
            .getSingleResult().toString(), "构造前置：快照列此刻确实是编号（串位态）");

        // ① 详情的 composition 三段全部取自主表
        MaterialRecipeDTO detail = recipeService.getDetail(recipeId);
        CompositionItemDTO ag = detail.composition.stream()
            .filter(c -> agNo.equals(c.elementNo)).findFirst().orElseThrow();
        System.out.println("[AC-36] composition = " + ag.elementNo + " / " + ag.elementCode + " / " + ag.elementName);
        assertEquals(agMaster.elementCode, ag.elementCode,
            "AC-36：elementCode 应取主表权威值（不是快照里的编号）");
        assertEquals(agMaster.elementName, ag.elementName, "AC-36：elementName 同样取主表");
        assertTrue(detail.elementCodes.contains(agMaster.elementCode),
            "AC-36：详情 elementCodes 用权威符号，实际=" + detail.elementCodes);
        assertFalse(detail.elementCodes.contains(agNo),
            "AC-36：不得再出现编号 " + agNo + "，实际=" + detail.elementCodes);

        // ② 配置矩阵的元素行同源（否则列表显示 Ag、抽屉显示编号，比不改更糟）
        String cfgCode = detail.configs.get(0).elements.stream()
            .filter(e -> agNo.equals(e.elementNo)).map(e -> e.elementCode).findFirst().orElseThrow();
        assertEquals(agMaster.elementCode, cfgCode, "AC-36：配置元素行的展示符号同样走权威链");

        // ③ 列表页（SQL 侧的 LEFT JOIN element）
        MaterialRecipeDTO lite = recipeService.list("UTREG串位", false).stream()
            .filter(d -> recipeId.equals(d.id)).findFirst().orElseThrow();
        System.out.println("[AC-36] list elementCodes = " + lite.elementCodes);
        assertTrue(lite.elementCodes.contains(agMaster.elementCode), "AC-36：列表用权威符号");
        assertFalse(lite.elementCodes.contains(agNo), "AC-36：列表不得显示编号");

        // ④ 🚫 反向断言：读路径一个字节都没回写
        assertEquals(agNo, em.createNativeQuery(
                "SELECT element_code FROM material_recipe_composition " +
                "WHERE recipe_id = CAST(:rid AS uuid) AND element_no = :no")
            .setParameter("rid", recipeId.toString()).setParameter("no", agNo)
            .getSingleResult().toString(),
            "AC-36 反向断言：只改显示不改数据 —— 库内快照列必须原样保留");
    }

    /** AC-36 边界：element_no 在主表查无（导入自动建档前）⇒ 回退快照，不得空白、不得报错。 */
    @Test
    @TestTransaction
    void elementDisplayValues_fallBackToSnapshotWhenMasterMisses() {
        MaterialRecipeDTO dto = seedRecipe("UTREG回退");
        UUID recipeId = dto.id;
        em.flush();

        // 把某一行的权威链指向一个主表里不存在的编号，快照仍是 Ag / 银
        em.createNativeQuery(
                "UPDATE material_recipe_composition SET element_no = 'UTNOMASTER' " +
                "WHERE recipe_id = CAST(:rid AS uuid) AND element_code = 'Ag'")
            .setParameter("rid", recipeId.toString()).executeUpdate();
        em.flush();
        em.clear();

        MaterialRecipeDTO detail = recipeService.getDetail(recipeId);
        CompositionItemDTO orphan = detail.composition.stream()
            .filter(c -> "UTNOMASTER".equals(c.elementNo)).findFirst().orElseThrow();
        System.out.println("[AC-36 回退] " + orphan.elementNo + " / " + orphan.elementCode + " / " + orphan.elementName);
        assertEquals("Ag", orphan.elementCode, "主表查无 ⇒ 回退快照 element_code，不得空白");
        assertEquals("银", orphan.elementName, "主表查无 ⇒ 回退快照 element_name");

        MaterialRecipeDTO lite = recipeService.list("UTREG回退", false).stream()
            .filter(d -> recipeId.equals(d.id)).findFirst().orElseThrow();
        assertTrue(lite.elementCodes.contains("Ag"),
            "列表侧 COALESCE 同样回退快照，实际=" + lite.elementCodes);
    }

    /** ElementDTO 字段存在性（编译期就能挡住字段被误删）。 */
    @Test
    void elementDtoStillCarriesReferenceFields() {
        ElementDTO d = new ElementDTO();
        d.referencedCount = 0L;
        d.codeLocked = false;
        assertEquals(0L, d.referencedCount);
        assertFalse(d.codeLocked);
    }
}
