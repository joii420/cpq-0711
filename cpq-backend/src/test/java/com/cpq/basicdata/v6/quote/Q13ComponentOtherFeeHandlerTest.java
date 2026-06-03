package com.cpq.basicdata.v6.quote;

import com.cpq.basicdata.v6.parser.ImportContext;
import com.cpq.basicdata.v6.parser.SheetRow;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

// Note: Q13 真实模板含3个"项次"列（一级/二级/要素），item_seq=getIntNth("项次",3)。
// row()改用List构造器模拟裸重复表头，反映真实 Excel 结构。

/** Task 3 集成测试：Q13 组成件其他费用（动态 cost_type, 行集维度=item_seq, seq_no 丢列）→ unit_price 版本化。 */
@QuarkusTest
class Q13ComponentOtherFeeHandlerTest {

    @Inject Q13ComponentOtherFeeHandler handler;
    @Inject EntityManager em;

    static final String CODE = "TEST-Q13-CODE";
    static final UUID UID = UUID.fromString("00000000-0000-0000-0000-000000000013");

    @Transactional void cleanup() {
        em.createNativeQuery("DELETE FROM unit_price WHERE code=:c").setParameter("c", CODE).executeUpdate();
    }
    @BeforeEach void before() { cleanup(); }
    @AfterEach  void after()  { cleanup(); }

    private ImportContext ctx() {
        ImportContext c = new ImportContext();
        c.customerNo = "C1"; c.systemType = "QUOTE"; c.importedBy = UID; return c;
    }
    /** 裸重复表头行：3个"项次"列（一级/二级/要素），item_seq=第3个项次，对应真实Q13模板。 */
    private SheetRow row(int itemSeq, String val) {
        List<String[]> o = new ArrayList<>();
        o.add(new String[]{"组成件料号", CODE});
        o.add(new String[]{"要素名称", "表面处理"});
        o.add(new String[]{"宏丰料号", "TEST-Q13-FMN"});
        o.add(new String[]{"工序编号", "OP13"});
        o.add(new String[]{"供应商编号", "SUP1"});
        o.add(new String[]{"项次", "1"});               // 第1个项次=一级(seq_no,不用)
        o.add(new String[]{"项次", "2"});               // 第2个项次=二级(不用)
        o.add(new String[]{"项次", String.valueOf(itemSeq)}); // 第3个项次=要素项次=item_seq
        o.add(new String[]{"值", val});
        o.add(new String[]{"货币", "CNY"});
        o.add(new String[]{"计价单位", "PCS"});
        return new SheetRow(itemSeq, o);
    }
    private String version() {
        List<?> r = em.createNativeQuery(
            "SELECT version_no FROM unit_price WHERE code=:c AND is_current=true LIMIT 1")
            .setParameter("c", CODE).getResultList();
        return r.isEmpty() ? null : String.valueOf(r.get(0));
    }
    private long total() {
        return ((Number) em.createNativeQuery("SELECT count(*) FROM unit_price WHERE code=:c")
            .setParameter("c", CODE).getSingleResult()).longValue();
    }

    @Test void importTwice_idempotent() {
        handler.handle(List.of(row(1, "5"), row(2, "8")), ctx());
        handler.handle(List.of(row(1, "5"), row(2, "8")), ctx());
        assertEquals("2000", version());
        assertEquals(2L, total());
    }
    @Test void changeValue_bumps() {
        handler.handle(List.of(row(1, "5"), row(2, "8")), ctx());
        handler.handle(List.of(row(1, "5"), row(2, "99")), ctx());
        assertEquals("2001", version());
        assertEquals(4L, total());
    }
}
