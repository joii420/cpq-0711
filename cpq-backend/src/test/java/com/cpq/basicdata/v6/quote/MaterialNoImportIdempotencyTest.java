package com.cpq.basicdata.v6.quote;

import com.cpq.basicdata.v6.parser.ImportContext;
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

/** C5：同一 Excel 连导两次，第二次按名称命中第一次生成的号，不再新增（决策 #2/#3）。 */
@QuarkusTest
class MaterialNoImportIdempotencyTest {

    @Inject MaterialBomMergeHandler handler;
    @Inject EntityManager em;

    static final String CUST = "TSTIDEM0615";
    static final String MAT  = "TESTIDEM0615";

    @Transactional
    void cleanup() {
        em.createNativeQuery("DELETE FROM material_bom_item WHERE material_no=:m").setParameter("m", MAT).executeUpdate();
        em.createNativeQuery("DELETE FROM material_bom WHERE material_no=:m").setParameter("m", MAT).executeUpdate();
        em.createNativeQuery("DELETE FROM material_master WHERE material_no LIKE '9%' OR material_name LIKE 'IDEM-%'").executeUpdate();
    }
    @BeforeEach void before() { cleanup(); }
    @AfterEach  void after()  { cleanup(); }

    private ImportContext ctx() {
        ImportContext c = new ImportContext();
        c.customerNo = CUST; c.systemType = "QUOTE"; c.importedBy = null; return c;
    }
    /** repair-2 后物料BOM 组件列恒为材质料号原始码（"投入料号"/"材质料号"任一非空即用），不再按名 resolve/生成。 */
    private SheetRow matRow(int seq, String code) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("宏丰料号", MAT); m.put("项次", String.valueOf(seq));
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
     * task-0717 repair-2 更新：本用例原前提"投入料号空+名称有值 → 首次按名生成 2 个报价料号，
     * 第二次按名命中不新增"，对应 repair-2 前旧语义（彼时物料BOM 组件列走
     * {@code materialNoResolver.resolve()}，按名生成/命中内部料号）。
     *
     * <p>repair-2（决策 A/B）后物料BOM 组件列恒为材质料号原始码，不再 resolve/不再按名生成——
     * "幂等"这一有价值的覆盖点改为验证：同一份 Excel（给出材质料号原始码，不涉及铸号）连续导入
     * 两次，第二次不产生新的 material_bom/material_bom_item 版本（VersionedV6Writer
     * 的 multisetEqual 命中 → 直接复用旧版本号不写库，见 writeVersionedMasterDetail 步骤 1）、
     * 不产生重复的 material_bom_item 行（总行数含历史应保持不变，而非仅 is_current 行数不变——
     * 若误升版，旧版本会被 flip 但保留，总行数会翻倍，仅看 is_current 计数掩盖不了这种情况）、
     * 也不新增 material_master 料号（mat 分支从不写 master，与是否重复导入无关）。
     */
    @Test
    void reimportSameExcel_noNewBomVersionOrDuplicateRows() {
        handler.merge(List.of(matRow(1, "IDEM-A001"), matRow(2, "IDEM-B001")), List.of(), ctx());
        List<String> firstComponentNos = currentComponentNos();
        assertEquals(List.of("IDEM-A001", "IDEM-B001"), firstComponentNos, "首次导入子行按材质料号原始码落库");
        String firstVersion = currentBomVersion();
        assertNotNull(firstVersion, "首次导入应产生一个 bom_version");
        long firstTotalRows = totalBomItemRowCount();
        assertEquals(2L, firstTotalRows);

        handler.merge(List.of(matRow(1, "IDEM-A001"), matRow(2, "IDEM-B001")), List.of(), ctx());
        assertEquals(firstVersion, currentBomVersion(),
            "内容完全相同的重复导入不应升版（VersionedV6Writer multisetEqual 命中→复用旧版本不写库）");
        assertEquals(firstComponentNos, currentComponentNos(), "重复导入不改变当前生效子行集合");
        assertEquals(firstTotalRows, totalBomItemRowCount(), "重复导入不产生新版本/不产生重复行（幂等）");
        assertEquals(0L, ((Number) em.createNativeQuery(
                "SELECT count(*) FROM material_master WHERE material_no IN ('IDEM-A001','IDEM-B001')")
                .getSingleResult()).longValue(),
            "材质料号不登记 material_master（mat 分支不写 master，与是否重复导入无关）");
    }
}
