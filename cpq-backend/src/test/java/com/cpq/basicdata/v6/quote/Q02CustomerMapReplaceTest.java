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

/**
 * ① 客户料号映射 replace-per-customer：脏导入留下的多余映射行在清洗后重导必须被清掉。
 *
 * <p>新契约（报价料号统一 Spec1）：material_customer_map QUOTE 行 material_no 全局唯一，
 * 同一报价料号只能挂一个客户产品编号（1:1，冲突键 = material_no，见 upsertQuote）。原用例
 * 「同一宏丰料号挂 3 个客户产品编号」在新语义下已结构性非法（会被 upsert 折叠成 1 行而非报错
 * 3 行）；改写为等价意图的合法数据：脏导入 3 个不同报价料号各自挂一个客户产品编号，
 * 清洗重导后只剩 1 个报价料号 —— 验证 replace-per-customer 仍会清掉重导文件里不再出现的
 * 陈旧映射行。
 */
@QuarkusTest
class Q02CustomerMapReplaceTest {

    @Inject Q02CustomerMapHandler handler;
    @Inject EntityManager em;

    static final String CUST = "TST-Q02REPLACE";
    static final String HF_A = "TST5121115551";
    static final String HF_B = "TST5121115552";
    static final String HF_C = "TST5121115553";

    @Transactional
    void cleanup() {
        em.createNativeQuery("DELETE FROM material_customer_map WHERE customer_no=:c")
            .setParameter("c", CUST).executeUpdate();
        em.createNativeQuery("DELETE FROM material_master WHERE material_no IN (:a,:b,:c)")
            .setParameter("a", HF_A).setParameter("b", HF_B).setParameter("c", HF_C).executeUpdate();
    }
    @BeforeEach void before() { cleanup(); }
    @AfterEach  void after()  { cleanup(); }

    private ImportContext ctx() {
        ImportContext c = new ImportContext();
        c.customerNo = CUST; c.systemType = "QUOTE"; c.importedBy = null; return c;
    }
    private SheetRow row(int seq, String hf, String cpn) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("宏丰料号", hf);
        m.put("客户产品编号", cpn);
        m.put("基础货币", "RMB");
        m.put("报价货币", "RMB");
        m.put("汇率", "1");
        return new SheetRow(seq, m);
    }
    @Transactional
    long count() {
        return ((Number) em.createNativeQuery(
                "SELECT count(*) FROM material_customer_map WHERE customer_no=:c")
                .setParameter("c", CUST).getSingleResult()).longValue();
    }

    @Test
    void reimport_clean_file_removes_stale_rows() {
        // 第一次脏导入：3 个不同报价料号，各自挂一个客户产品编号
        handler.handle(List.of(row(1, HF_A, "DIRTY-A"), row(2, HF_B, "DIRTY-B"), row(3, HF_C, "DIRTY-C")), ctx());
        assertEquals(3, count(), "脏导入后应有 3 行");

        // 用户清洗文件后重导：只剩 1 个报价料号
        handler.handle(List.of(row(1, HF_A, "CLEAN-A")), ctx());
        assertEquals(1, count(), "清洗后重导应只剩 1 行（陈旧的 HF_B/HF_C 映射行被清掉）");
    }
}
