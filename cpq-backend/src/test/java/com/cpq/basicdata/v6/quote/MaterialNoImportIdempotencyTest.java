package com.cpq.basicdata.v6.quote;

import com.cpq.basicdata.v6.parser.ImportContext;
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

/** C5：同一 Excel 连导两次，第二次不应重复升版/重复落库（决策 #2/#3，update-0723 B3 单表改造后沿用）。 */
@QuarkusTest
class MaterialNoImportIdempotencyTest {

    @Inject MaterialBomMergeHandler handler;
    @Inject PartTypeInferenceService typeInferenceService;
    @Inject EntityManager em;

    static final String CUST = "TSTIDEM0723";
    static final String MAT  = "TESTIDEM0723";
    /** 真实存在于 material_recipe 的材质料号（见 dev-docs 黄金样例）。 */
    static final String RECIPE_991 = "991";
    static final String RECIPE_992 = "992";

    @Transactional
    void cleanup() {
        em.createNativeQuery("DELETE FROM material_bom_item WHERE material_no=:m").setParameter("m", MAT).executeUpdate();
        em.createNativeQuery("DELETE FROM material_bom WHERE material_no=:m").setParameter("m", MAT).executeUpdate();
    }
    @BeforeEach void before() { cleanup(); }
    @AfterEach  void after()  { cleanup(); }

    private ImportContext ctx() {
        ImportContext c = new ImportContext();
        c.customerNo = CUST; c.systemType = "QUOTE"; c.importedBy = null; return c;
    }
    /** 单 sheet 物料BOM 行：投入料号=材质料号原始码（不建 partTypeIndex 时命中 material_recipe 库内兜底=RECIPE）。 */
    private SheetRow matRow(int seq, String code) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("销售料号", MAT); m.put("项次", String.valueOf(seq));
        m.put("投入料号", code); m.put("产出料号类型", "2.非银点类");
        m.put("材料毛重", "1.0"); m.put("重量单位", "KG");
        return new SheetRow(seq, m);
    }
    @SuppressWarnings("unchecked")
    private List<String> currentComponentNos() {
        return em.createNativeQuery(
                "SELECT component_no FROM material_bom_item WHERE material_no=:m AND is_current=TRUE ORDER BY component_no")
            .setParameter("m", MAT).getResultList();
    }
    private String currentBomVersion() {
        var r = em.createNativeQuery(
                "SELECT bom_version FROM material_bom WHERE material_no=:m AND is_current=TRUE")
            .setParameter("m", MAT).getResultList();
        return r.isEmpty() ? null : String.valueOf(r.get(0));
    }
    private long totalBomItemRowCount() {
        return ((Number) em.createNativeQuery(
                "SELECT count(*) FROM material_bom_item WHERE material_no=:m")
            .setParameter("m", MAT).getSingleResult()).longValue();
    }

    /**
     * 材质料号 991/992 是 material_recipe 库内已存在的真实码。显式建 RECIPE 权威索引（比依赖
     * handler 的 typeIndex==null 默认零件兜底更贴近真实导入路径——Phase1 校验器总会先建好
     * typeIndex 再进 Phase2 写入，见 {@code QuoteImportService#processImport}）。
     */
    @Transactional
    @Test
    void reimportSameExcel_noNewBomVersionOrDuplicateRows() {
        ImportContext ctx = ctx();
        // 显式建 RECIPE 权威索引（比依赖 null 兜底更贴近真实导入路径：Phase1 总会建好 typeIndex）。
        Map<String, String> elemRowA = new LinkedHashMap<>();
        elemRowA.put("销售料号", MAT); elemRowA.put("材质料号", RECIPE_991);
        Map<String, String> elemRowB = new LinkedHashMap<>();
        elemRowB.put("销售料号", MAT); elemRowB.put("材质料号", RECIPE_992);
        ctx.sharedCache.put("partTypeIndex", typeInferenceService.buildIndex(
            Map.of("物料与元素BOM", List.of(new SheetRow(1, elemRowA), new SheetRow(2, elemRowB)))));

        handler.merge(List.of(matRow(1, RECIPE_991), matRow(2, RECIPE_992)), ctx);
        List<String> firstComponentNos = currentComponentNos();
        assertEquals(List.of(RECIPE_991, RECIPE_992), firstComponentNos, "首次导入子行按材质料号原始码落库");
        String firstVersion = currentBomVersion();
        assertNotNull(firstVersion, "首次导入应产生一个 bom_version");
        long firstTotalRows = totalBomItemRowCount();
        assertEquals(2L, firstTotalRows);

        handler.merge(List.of(matRow(1, RECIPE_991), matRow(2, RECIPE_992)), ctx);
        assertEquals(firstVersion, currentBomVersion(),
            "内容完全相同的重复导入不应升版（VersionedV6Writer multisetEqual 命中→复用旧版本不写库）");
        assertEquals(firstComponentNos, currentComponentNos(), "重复导入不改变当前生效子行集合");
        assertEquals(firstTotalRows, totalBomItemRowCount(), "重复导入不产生新版本/不产生重复行（幂等）");
        assertEquals(0L, ((Number) em.createNativeQuery(
                "SELECT count(*) FROM material_master WHERE material_no IN (:a,:b)")
                .setParameter("a", RECIPE_991).setParameter("b", RECIPE_992)
                .getSingleResult()).longValue(),
            "材质料号不登记 material_master（RECIPE 分支不写 master，与是否重复导入无关）");
    }
}
