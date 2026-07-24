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

// update-0723 B5（U0 #4 必修 bug）：新模板删除「工序编号/组装工序/要素编号」三列后，
// "项次"只出现 2 次（不再是 3 次），item_seq 改读 getIntNth("项次",2)。
// row()仍用 List 构造器模拟裸重复表头，反映新模板真实 Excel 结构。

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
    /** 裸重复表头行：2个"项次"列（一级/要素），item_seq=第2个项次，对应新模板 0723 结构（U0 #4）。 */
    private SheetRow row(int itemSeq, String val) {
        List<String[]> o = new ArrayList<>();
        o.add(new String[]{"销售料号", "TEST-Q13-FMN"});
        o.add(new String[]{"项次", "1"});               // 第1个项次=一级(seq_no,不用)
        o.add(new String[]{"组成件料号", CODE});
        o.add(new String[]{"组成件名称", "组成件1"});
        o.add(new String[]{"供应商编号", "SUP1"});
        o.add(new String[]{"供应商名称", "供应商1"});
        o.add(new String[]{"项次", String.valueOf(itemSeq)}); // 第2个项次=要素项次=item_seq（新模板，工序编号列已删除）
        o.add(new String[]{"要素名称", "表面处理"});
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

    @Transactional
    @Test void importTwice_idempotent() {
        handler.handle(List.of(row(1, "5"), row(2, "8")), ctx());
        handler.handle(List.of(row(1, "5"), row(2, "8")), ctx());
        assertEquals("2000", version());
        assertEquals(2L, total());
    }
    @Transactional
    @Test void changeValue_bumps() {
        handler.handle(List.of(row(1, "5"), row(2, "8")), ctx());
        handler.handle(List.of(row(1, "5"), row(2, "99")), ctx());
        assertEquals("2001", version());
        assertEquals(4L, total());
    }
}
