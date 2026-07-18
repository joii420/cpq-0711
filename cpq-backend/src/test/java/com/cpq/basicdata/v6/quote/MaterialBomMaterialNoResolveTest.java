package com.cpq.basicdata.v6.quote;

import com.cpq.basicdata.v6.parser.ImportContext;
import com.cpq.basicdata.v6.parser.SheetImportResult;
import com.cpq.basicdata.v6.parser.SheetRow;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** §3 物料BOM：投入料号为空+名称匹配/生成（决策 #1~#4/#11）。 */
@QuarkusTest
class MaterialBomMaterialNoResolveTest {

    @Inject MaterialBomMergeHandler handler;
    @Inject EntityManager em;

    static final String CUST = "TSTRSV0615";
    static final String MAT  = "TESTRSV0615";

    @Transactional
    void cleanup() {
        em.createNativeQuery("DELETE FROM material_bom_item WHERE material_no=:m").setParameter("m", MAT).executeUpdate();
        em.createNativeQuery("DELETE FROM material_bom WHERE material_no=:m").setParameter("m", MAT).executeUpdate();
        em.createNativeQuery("DELETE FROM material_master WHERE material_no LIKE '9%' OR material_name LIKE 'RSV-%'").executeUpdate();
    }
    @BeforeEach void before() { cleanup(); }
    @AfterEach  void after()  { cleanup(); }

    private ImportContext ctx() {
        ImportContext c = new ImportContext();
        c.customerNo = CUST; c.systemType = "QUOTE"; c.importedBy = null; return c;
    }
    private SheetRow matRow(int seq, String comp, String name) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("宏丰料号", MAT); m.put("项次", String.valueOf(seq));
        if (comp != null) m.put("投入料号", comp);
        if (name != null) m.put("投入料号名称", name);
        m.put("产出料号类型", "2.非银点类"); m.put("材料毛重", "1.0"); m.put("重量单位", "KG");
        return new SheetRow(seq, m);
    }
    private long masterCount(String name) {
        return ((Number) em.createNativeQuery("SELECT count(*) FROM material_master WHERE material_name=:n")
            .setParameter("n", name).getSingleResult()).longValue();
    }
    private String childComponentNos() {
        return em.createNativeQuery(
            "SELECT string_agg(component_no, ',' ORDER BY component_no) FROM material_bom_item " +
            "WHERE material_no=:m AND is_current=TRUE").setParameter("m", MAT).getSingleResult().toString();
    }

    /**
     * task-0717 repair-2 更新：本用例原断言"投入料号空+名称有值→按名匹配/生成报价料号，
     * BOM 子行用生成号"，对应 repair-2 前旧语义（彼时物料BOM 组件列走
     * {@code materialNoResolver.resolve()}，名称可作为落库依据按名生成内部料号）。
     *
     * <p>repair-2（决策 A/B）后物料BOM 的组件列恒定语义为"材质料号"——直接引用材质库
     * （{@code material_recipe}），只认 Excel 原始码（"投入料号"/"材质料号"两列任一非空即用），
     * 不再 resolve/不再按名生成料号。本用例按新语义拆成两点重写：①给出材质料号原始码的行 →
     * 子行 component_no 原样落库为该原始码，且不登记 material_master（mat 分支从不写 master）；
     * ②料号列（投入料号/材质料号）全空 → 记 recordError("材质料号","为空")，整行跳过、不落
     * material_bom_item（不再有"按名生成"兜底路径）。
     */
    @Test
    void rawMaterialCode_childUsesRawCode_notRegisteredToMaster() {
        SheetImportResult r = handler.merge(List.of(matRow(1, "RSV-RAW-001", "RSV-GEN-1")), List.of(), ctx());
        assertEquals(0, r.failedRows);
        assertEquals("RSV-RAW-001", childComponentNos(), "BOM 子行 component_no 应原样为 Excel 材质料号原始码");
        assertEquals(0L, masterCount("RSV-GEN-1"), "材质料号不登记 material_master（mat 分支不再 upsert，与是否给名称无关）");
    }

    @Test
    void emptyMaterialCode_recordsError_notLandedToBomItem() {
        SheetImportResult r = handler.merge(List.of(matRow(1, null, "RSV-GEN-1")), List.of(), ctx());
        assertEquals(1, r.failedRows, "投入料号/材质料号均空应记为失败行");
        assertEquals(1, r.errors.size());
        assertTrue(r.errors.get(0).message.contains("为空"),
            "错误信息应含「为空」，实际=" + r.errors.get(0).message);
        assertEquals(0L, ((Number) em.createNativeQuery(
                "SELECT count(*) FROM material_bom_item WHERE material_no=:m")
                .setParameter("m", MAT).getSingleResult()).longValue(),
            "空材质料号行不应落 material_bom_item（不再按名生成兜底）");
    }

    @Test
    void emptyNoWithName_matchesExistingMaster() {
        handler.merge(List.of(matRow(1, "EXIST-001", "RSV-MATCH")), List.of(), ctx());
        handler.merge(List.of(matRow(1, null, "RSV-MATCH")), List.of(), ctx());
        assertEquals("EXIST-001", childComponentNos());
        assertEquals(0L, ((Number) em.createNativeQuery(
            "SELECT count(*) FROM material_master WHERE material_no LIKE '9%'").getSingleResult()).longValue());
    }

    @Test
    void emptyNoAndEmptyName_recordsError() {
        SheetImportResult r = handler.merge(List.of(matRow(1, null, null)), List.of(), ctx());
        assertTrue(r.failedRows >= 1, "料号与名称都空→记错误跳过");
        assertEquals(0L, ((Number) em.createNativeQuery(
            "SELECT count(*) FROM material_bom_item WHERE material_no=:m").setParameter("m", MAT).getSingleResult()).longValue());
    }
}
