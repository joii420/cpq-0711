package com.cpq.basicdata.v6.quote;

import com.cpq.basicdata.v6.parser.ImportContext;
import com.cpq.basicdata.v6.parser.SheetRow;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * task-0717：Q06~Q10「投入料号」扩围材质模型（repair-2 RECIPE 模型扩围）专项测试。
 *
 * <p>验证：投入料号恒按材质处理 —— 原始码直接作 {@code unit_price.code}（不 resolve/不铸号），
 * 不登记 {@code material_master}，不登记 {@code material_customer_map}（system_type=QUOTE 全局唯一，
 * 防跨客户串号）。以 Q06（来料固定加工费）代表 Q06~Q09 同构逻辑。
 *
 * <p>另验证 Q10（自制加工费）§10 规则3 的「投入料号为空 → 兜底成品料号（整体加工费）」既有业务功能
 * 在本次扩围（触发条件从 resolve() 抛异常改为直接判断 raw.isBlank()）后仍保留、未被破坏。
 */
@QuarkusTest
class IncomingMaterialRecipeHandlerTest {

    @Inject Q06FixedProcessFeeHandler q06;
    @Inject Q10SelfProcessFeeHandler q10;
    @Inject EntityManager em;

    static final String CUST      = "TEST-IMR-CUST";
    static final String CODE_991  = "REC-TEST-991";
    static final String SALES_T1  = "SALES-T1";
    static final String SALES_T10 = "SALES-T10";

    @Transactional
    void cleanup() {
        em.createNativeQuery("DELETE FROM unit_price WHERE customer_no = :c")
          .setParameter("c", CUST).executeUpdate();
        em.createNativeQuery("DELETE FROM material_master WHERE material_no IN (:a,:b,:c)")
          .setParameter("a", CODE_991).setParameter("b", SALES_T1).setParameter("c", SALES_T10)
          .executeUpdate();
        em.createNativeQuery("DELETE FROM material_customer_map WHERE material_no IN (:a,:b,:c) AND system_type='QUOTE'")
          .setParameter("a", CODE_991).setParameter("b", SALES_T1).setParameter("c", SALES_T10)
          .executeUpdate();
    }

    @BeforeEach void before() { cleanup(); }
    @AfterEach  void after()  { cleanup(); }

    private ImportContext ctx() {
        ImportContext c = new ImportContext();
        c.customerNo = CUST; c.systemType = "QUOTE"; c.importedBy = null;
        return c;
    }

    private SheetRow q06Row(int rowNo) {
        Map<String, String> m = new HashMap<>();
        m.put("投入料号", CODE_991);
        m.put("投入料号名称", "测试材质");
        m.put("销售料号", SALES_T1);
        m.put("项次", "1");
        m.put("基准值", "1");
        m.put("货币", "RMB");
        m.put("计价单位", "kg");
        return new SheetRow(rowNo, m);
    }

    @Transactional
    @Test
    void q06_inputMaterialNo_rawCode_notResolvedNotRegistered() {
        q06.handle(List.of(q06Row(1)), ctx());

        Object codeResult = em.createNativeQuery(
            "SELECT code FROM unit_price WHERE customer_no=:c AND finished_material_no=:f AND is_current=TRUE")
            .setParameter("c", CUST).setParameter("f", SALES_T1).getSingleResult();
        assertEquals(CODE_991, codeResult, "unit_price.code 应为原始码（未 resolve/铸号）");

        long masterCount = ((Number) em.createNativeQuery(
            "SELECT count(*) FROM material_master WHERE material_no=:c")
            .setParameter("c", CODE_991).getSingleResult()).longValue();
        assertEquals(0L, masterCount, "投入料号（材质）不应登记 material_master");

        long mcmCount = ((Number) em.createNativeQuery(
            "SELECT count(*) FROM material_customer_map WHERE material_no=:c AND system_type='QUOTE'")
            .setParameter("c", CODE_991).getSingleResult()).longValue();
        assertEquals(0L, mcmCount, "投入料号（材质）不应登记 material_customer_map（QUOTE）");
    }

    @Transactional
    @Test
    void q10_blankInputMaterialNo_fallsBackToFinishedMaterialNo() {
        // §10 规则3：投入料号 / 投入料号名称均不填 → 兜底为成品料号（整体加工费），
        // 触发条件已从「resolve() 抛异常」改为直接判断 raw.isBlank()，本用例验证兜底功能未被破坏。
        Map<String, String> m = new HashMap<>();
        m.put("销售料号", SALES_T10);
        m.put("项次（一级）", "1");
        m.put("值", "100");
        m.put("货币", "RMB");
        m.put("计价单位", "kg");

        q10.handle(List.of(new SheetRow(1, m)), ctx());

        Object codeResult = em.createNativeQuery(
            "SELECT code FROM unit_price WHERE customer_no=:c AND finished_material_no=:f AND is_current=TRUE")
            .setParameter("c", CUST).setParameter("f", SALES_T10).getSingleResult();
        assertEquals(SALES_T10, codeResult, "投入料号为空应兜底为成品料号（§10 规则3 保留）");
    }
}
