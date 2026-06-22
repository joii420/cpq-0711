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

/** ① 客户料号映射 replace-per-customer：脏导入留下的多余 customer_product_no 在清洗后重导必须被清掉。 */
@QuarkusTest
class Q02CustomerMapReplaceTest {

    @Inject Q02CustomerMapHandler handler;
    @Inject EntityManager em;

    static final String CUST = "TST-Q02REPLACE";
    static final String HF   = "TST5121115551";

    @Transactional
    void cleanup() {
        em.createNativeQuery("DELETE FROM material_customer_map WHERE customer_no=:c")
            .setParameter("c", CUST).executeUpdate();
        em.createNativeQuery("DELETE FROM material_master WHERE material_no=:m")
            .setParameter("m", HF).executeUpdate();
    }
    @BeforeEach void before() { cleanup(); }
    @AfterEach  void after()  { cleanup(); }

    private ImportContext ctx() {
        ImportContext c = new ImportContext();
        c.customerNo = CUST; c.systemType = "QUOTE"; c.importedBy = null; return c;
    }
    private SheetRow row(int seq, String cpn) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("宏丰料号", HF);
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
        // 第一次脏导入：同一宏丰料号映射 3 个客户产品编号
        handler.handle(List.of(row(1, "DIRTY-A"), row(2, "DIRTY-B"), row(3, "DIRTY-C")), ctx());
        assertEquals(3, count(), "脏导入后应有 3 行");

        // 用户清洗文件后重导：同一宏丰料号只剩 1 个客户产品编号
        handler.handle(List.of(row(1, "CLEAN-A")), ctx());
        assertEquals(1, count(), "清洗后重导应只剩 1 行（旧的 3 行被替换）");
    }
}
