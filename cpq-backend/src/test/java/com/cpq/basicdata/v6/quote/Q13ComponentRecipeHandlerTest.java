package com.cpq.basicdata.v6.quote;

import com.cpq.basicdata.v6.parser.ImportContext;
import com.cpq.basicdata.v6.parser.SheetImportResult;
import com.cpq.basicdata.v6.parser.SheetRow;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * task-0717 Part A：Q13「组成件其他费用」组成件料号命中"本次导入材质料号集"(repair-2 决策 D) 专项测试。
 *
 * <p>验证：{@code ctx.sharedCache.get("quoteMaterialNoSet")} 命中 → 按材质处理（原始码直接作
 * {@code unit_price.code}，不 resolve/不登记 {@code material_master}）；未命中 → 维持原真组成件
 * resolve 路径（{@code material_master} 按 material_type='组成件' 登记）。
 *
 * <p>与 {@link MaterialBomMergeHandler} 组成件BOM 分支同款判定逻辑（该文件 §3.3-2 决策 D）。
 */
@QuarkusTest
class Q13ComponentRecipeHandlerTest {

    @Inject Q13ComponentOtherFeeHandler handler;
    @Inject EntityManager em;

    static final String CUST = "TEST-Q13R-CUST";
    static final String CODE_HIT = "REC-HIT-0717";
    static final String CODE_MISS = "Q13R-REALCOMP-0717";
    static final String NAME_MISS = "Q13R-真组件-0717";
    static final String FIN = "Q13R-FIN-0717";

    @Transactional
    void cleanup() {
        em.createNativeQuery("DELETE FROM unit_price WHERE customer_no = :c")
          .setParameter("c", CUST).executeUpdate();
        em.createNativeQuery("DELETE FROM material_master WHERE material_no IN (:a,:b)")
          .setParameter("a", CODE_HIT).setParameter("b", CODE_MISS).executeUpdate();
        em.createNativeQuery("DELETE FROM material_master WHERE material_name = :n")
          .setParameter("n", NAME_MISS).executeUpdate();
    }

    @BeforeEach void before() { cleanup(); }
    @AfterEach  void after()  { cleanup(); }

    private ImportContext ctx(Set<String> matNoSet) {
        ImportContext c = new ImportContext();
        c.customerNo = CUST; c.systemType = "QUOTE"; c.importedBy = null;
        if (matNoSet != null) c.sharedCache.put("quoteMaterialNoSet", matNoSet);
        return c;
    }

    /** 裸重复表头行：3 个"项次"列（一级/二级/要素），对应真实 Q13 模板（见 Q13ComponentOtherFeeHandlerTest）。 */
    private SheetRow row(String componentNo, String componentName) {
        List<String[]> o = new ArrayList<>();
        if (componentNo != null) o.add(new String[]{"组成件料号", componentNo});
        if (componentName != null) o.add(new String[]{"组成件名称", componentName});
        o.add(new String[]{"要素名称", "表面处理"});
        o.add(new String[]{"宏丰料号", FIN});
        o.add(new String[]{"工序编号", "OP13"});
        o.add(new String[]{"供应商编号", "SUP1"});
        o.add(new String[]{"项次", "1"});
        o.add(new String[]{"项次", "2"});
        o.add(new String[]{"项次", "1"});
        o.add(new String[]{"值", "10"});
        o.add(new String[]{"货币", "CNY"});
        o.add(new String[]{"计价单位", "PCS"});
        return new SheetRow(1, o);
    }

    /** getSingleResult()：命中分支若意外写出重复行/0 行会直接炸出来，而非静默只看第一行。 */
    private String upCode() {
        return String.valueOf(em.createNativeQuery(
            "SELECT code FROM unit_price WHERE customer_no=:c AND finished_material_no=:f AND is_current=TRUE")
            .setParameter("c", CUST).setParameter("f", FIN).getSingleResult());
    }

    private long masterCount(String materialNo) {
        return ((Number) em.createNativeQuery(
            "SELECT count(*) FROM material_master WHERE material_no=:m")
            .setParameter("m", materialNo).getSingleResult()).longValue();
    }

    @Test
    void componentNo_hitsMaterialNoSet_treatedAsRecipe_rawCode_notRegistered() {
        SheetImportResult r = handler.handle(
            List.of(row(CODE_HIT, "不应被采用的组件名")), ctx(Set.of(CODE_HIT)));

        assertEquals(0, r.failedRows);
        assertEquals(CODE_HIT, upCode(), "命中材质料号集 → unit_price.code 应为原始码（未 resolve/铸号）");
        assertEquals(0L, masterCount(CODE_HIT), "命中材质料号集不应登记 material_master");
    }

    @Test
    void componentNo_missesMaterialNoSet_resolvesAsRealComponent_registersMaster() {
        SheetImportResult r = handler.handle(
            List.of(row(CODE_MISS, NAME_MISS)), ctx(Set.of("REC-OTHER")));

        assertEquals(0, r.failedRows);
        assertEquals(CODE_MISS, upCode(), "未命中材质料号集 → 走 resolve，原始码本身可解析时直接采用");
        assertEquals(1L, masterCount(CODE_MISS), "未命中材质料号集应保留原 resolve 路径 → 登记 material_master");

        Object type = em.createNativeQuery(
            "SELECT material_type FROM material_master WHERE material_no=:m")
            .setParameter("m", CODE_MISS).getSingleResult();
        assertEquals("组成件", type, "未命中路径登记的 material_type 应为「组成件」");
    }
}
