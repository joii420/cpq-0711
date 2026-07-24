package com.cpq.basicdata.v6.quote;

import com.cpq.basicdata.v6.parser.ImportContext;
import com.cpq.basicdata.v6.parser.SheetImportResult;
import com.cpq.basicdata.v6.parser.SheetRow;
import com.cpq.basicdata.v6.service.PartTypeInferenceService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * update-0723 B3：物料BOM 投入料号只填名称（蓝色必填其一）时的定型 + 解析行为（U2）。
 *
 * <p>新语义（与旧 repair-2 时代"投入料号列恒为材质"不同）：投入料号列的类型由
 * {@link PartTypeInferenceService.TypeIndex} 推断——
 * <ul>
 *   <li>推断=材质(RECIPE)：只认 material_recipe，按名查无 → 报错「未找到材质」，不铸号。</li>
 *   <li>推断=零件/外购件(默认兜底 ASSEMBLY，或命中权威 sheet)：走 {@link com.cpq.basicdata.v6.service.MaterialNoResolver}
 *       ——按名查 material_master 命中则复用，查无则发号 + 登记（material_type=零件/外购件）。</li>
 * </ul>
 */
@QuarkusTest
class MaterialBomMaterialNoResolveTest {

    @Inject MaterialBomMergeHandler handler;
    @Inject PartTypeInferenceService typeInferenceService;
    @Inject EntityManager em;

    static final String CUST = "TSTRSV0723";
    static final String MAT  = "TESTRSV0723";

    @Transactional
    void cleanup() {
        em.createNativeQuery("DELETE FROM material_bom_item WHERE material_no=:m").setParameter("m", MAT).executeUpdate();
        em.createNativeQuery("DELETE FROM material_bom WHERE material_no=:m").setParameter("m", MAT).executeUpdate();
        em.createNativeQuery("DELETE FROM material_customer_map WHERE material_no LIKE '%RSV0723%' OR material_no IN ('EXIST-0723')").executeUpdate();
        em.createNativeQuery("DELETE FROM material_master WHERE material_name LIKE 'RSV0723-%' OR material_no = 'EXIST-0723'").executeUpdate();
    }
    @BeforeEach void before() { cleanup(); }
    @AfterEach  void after()  { cleanup(); }

    private ImportContext ctx() {
        ImportContext c = new ImportContext();
        c.customerNo = CUST; c.systemType = "QUOTE"; c.importedBy = null; return c;
    }

    private SheetRow row(int seq, String comp, String name) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("销售料号", MAT); m.put("项次", String.valueOf(seq));
        if (comp != null) m.put("投入料号", comp);
        if (name != null) m.put("投入料号名称", name);
        m.put("产出料号类型", "2.非银点类"); m.put("材料毛重", "1.0"); m.put("重量单位", "KG");
        return new SheetRow(seq, m);
    }

    private String childComponentNos() {
        return em.createNativeQuery(
            "SELECT string_agg(component_no, ',' ORDER BY component_no) FROM material_bom_item " +
            "WHERE material_no=:m AND is_current=TRUE").setParameter("m", MAT).getSingleResult().toString();
    }

    @Transactional
    @Test
    void nameOnly_defaultAssembly_matchesExistingMasterByName() {
        // 预先登记一个已存在的 material_master 行（同名）
        Transactional_upsertExisting("EXIST-0723", "RSV0723-MATCH");

        SheetImportResult r = handler.merge(List.of(row(1, null, "RSV0723-MATCH")), ctx());
        assertEquals(0, r.failedRows);
        assertEquals("EXIST-0723", childComponentNos(), "按名匹配到已存在料号，复用而非发号");
    }

    @Transactional
    @Test
    void nameOnly_defaultAssembly_noExistingMatch_mintsNewCodeAndRegisters() {
        SheetImportResult r = handler.merge(List.of(row(1, null, "RSV0723-NEW-NAME")), ctx());
        assertEquals(0, r.failedRows);
        String childNo = childComponentNos();
        assertNotNull(childNo);
        assertNotEquals("RSV0723-NEW-NAME", childNo, "应发新号而非把名称当料号落库");

        Object[] master = (Object[]) em.createNativeQuery(
            "SELECT material_name, material_type FROM material_master WHERE material_no=:c")
            .setParameter("c", childNo).getSingleResult();
        assertEquals("RSV0723-NEW-NAME", master[0]);
        assertEquals("零件", master[1], "B6：默认兜底零件类型登记 material_type=零件");

        em.createNativeQuery("DELETE FROM material_master WHERE material_no=:c").setParameter("c", childNo).executeUpdate();
        em.createNativeQuery("DELETE FROM material_customer_map WHERE material_no=:c").setParameter("c", childNo).executeUpdate();
    }

    @Transactional
    @Test
    void nameOnly_recipeType_matchesMaterialRecipeByName() {
        // 991 在 material_recipe 中的 name 为 "H65带"（见 dev-docs 黄金样例）。
        ImportContext ctx = ctx();
        Map<String, String> elemRow = new LinkedHashMap<>();
        elemRow.put("销售料号", MAT); elemRow.put("材质料号", "991"); elemRow.put("材质料号名称", "H65带");
        ctx.sharedCache.put("partTypeIndex",
            typeInferenceService.buildIndex(Map.of("物料与元素BOM", List.of(new SheetRow(1, elemRow)))));

        SheetImportResult r = handler.merge(List.of(row(1, null, "H65带")), ctx);
        assertEquals(0, r.failedRows);
        assertEquals("991", childComponentNos(), "材质按名查 material_recipe 命中真实料号");
    }

    @Transactional
    @Test
    void nameOnly_recipeType_notFoundInRecipe_rejected() {
        ImportContext ctx = ctx();
        Map<String, String> elemRow = new LinkedHashMap<>();
        elemRow.put("销售料号", MAT); elemRow.put("材质料号名称", "RSV0723-GHOST-RECIPE");
        ctx.sharedCache.put("partTypeIndex",
            typeInferenceService.buildIndex(Map.of("物料与元素BOM", List.of(new SheetRow(1, elemRow)))));

        SheetImportResult r = handler.merge(List.of(row(1, null, "RSV0723-GHOST-RECIPE")), ctx);
        assertEquals(1, r.failedRows);
        assertTrue(r.errors.get(0).message.contains("未找到材质"));
        assertEquals(0L, ((Number) em.createNativeQuery(
                "SELECT count(*) FROM material_bom_item WHERE material_no=:m")
                .setParameter("m", MAT).getSingleResult()).longValue());
    }

    @Transactional
    @Test
    void emptyNoAndEmptyName_recordsError() {
        SheetImportResult r = handler.merge(List.of(row(1, null, null)), ctx());
        assertTrue(r.failedRows >= 1, "料号与名称都空→记错误跳过");
        assertEquals(0L, ((Number) em.createNativeQuery(
            "SELECT count(*) FROM material_bom_item WHERE material_no=:m").setParameter("m", MAT).getSingleResult()).longValue());
    }

    @Transactional
    void Transactional_upsertExisting(String no, String name) {
        em.createNativeQuery(
            "INSERT INTO material_master(material_no, material_name, material_type, created_at, updated_at) " +
            "VALUES (:no,:name,'零件',NOW(),NOW()) ON CONFLICT (material_no) DO UPDATE SET material_name=:name")
            .setParameter("no", no).setParameter("name", name).executeUpdate();
    }
}
