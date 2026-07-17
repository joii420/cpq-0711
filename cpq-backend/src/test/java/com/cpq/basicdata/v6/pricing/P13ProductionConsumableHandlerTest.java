package com.cpq.basicdata.v6.pricing;

import com.cpq.basicdata.v6.parser.ImportContext;
import com.cpq.basicdata.v6.parser.SheetRow;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * P13 生产耗材 production_no 回填：
 *   ② 同一次导入、同料号(code)其它行的首个非空生产料号回填空行；
 *   ④ 整组全空时从 material_master(销售料号→生产料号 权威主档)兜底。
 * （③「继承上一版」由 VersionedV6WriterTest 覆盖；文件本行非空 ① 保留。）
 */
@QuarkusTest
class P13ProductionConsumableHandlerTest {

    @Inject P13ProductionConsumableHandler handler;
    @Inject EntityManager em;

    static final String CODE = "TEST-P13-CODE";
    static final String CODE_MM = "TEST-P13-MM";
    static final UUID UID = UUID.fromString("00000000-0000-0000-0000-000000000013");

    @Transactional void cleanup() {
        em.createNativeQuery("DELETE FROM unit_price WHERE code IN (:c1,:c2)")
          .setParameter("c1", CODE).setParameter("c2", CODE_MM).executeUpdate();
        em.createNativeQuery("DELETE FROM material_master WHERE material_no=:c")
          .setParameter("c", CODE_MM).executeUpdate();
    }
    @BeforeEach void before() { cleanup(); }
    @AfterEach  void after()  { cleanup(); }

    @Transactional void insertMaster(String code, String prodNo) {
        em.createNativeQuery("INSERT INTO material_master (material_no, production_no) VALUES (:c, :p) "
            + "ON CONFLICT (material_no) DO UPDATE SET production_no = :p")
          .setParameter("c", code).setParameter("p", prodNo).executeUpdate();
    }

    private ImportContext ctx() {
        ImportContext c = new ImportContext();
        c.customerNo = "_GLOBAL_"; c.systemType = "PRICING"; c.importedBy = UID; return c;
    }
    private SheetRow row(String code, String op, String price, String prodNo) {
        Map<String,String> m = new LinkedHashMap<>();
        m.put("销售料号", code); m.put("工序编号", op); m.put("耗材成本单价", price);
        m.put("币种", "CNY"); m.put("计量单位", "KG");
        if (prodNo != null) m.put("生产料号", prodNo);   // null = 该格为空(不放键)
        return new SheetRow(1, m);
    }
    private String prodNoOf(String code, String op) {
        List<?> r = em.createNativeQuery(
            "SELECT production_no FROM unit_price WHERE code=:c AND operation_no=:op AND is_current=true")
            .setParameter("c", code).setParameter("op", op).getResultList();
        return r.isEmpty() || r.get(0) == null ? null : String.valueOf(r.get(0));
    }

    /** ② 同批同料号：某行生产料号空 → 从同 code 首个非空回填；有值行保留。 */
    @Test
    void emptyProductionNo_backfilledFromSibling() {
        handler.handle(List.of(row(CODE,"OP1","1.0","PX-001"), row(CODE,"OP2","2.0",null)), ctx());
        assertEquals("PX-001", prodNoOf(CODE,"OP2"), "同批同料号首个非空生产料号应回填空行");
        assertEquals("PX-001", prodNoOf(CODE,"OP1"), "文件提供的生产料号原样保留");
    }

    /** ④ 整组全空 → material_master(销售料号→生产料号)权威兜底。 */
    @Test
    void allEmpty_backfilledFromMaterialMaster() {
        insertMaster(CODE_MM, "MM-999");
        handler.handle(List.of(row(CODE_MM,"OP1","1.0",null)), ctx());
        assertEquals("MM-999", prodNoOf(CODE_MM,"OP1"), "整组生产料号空 → 从 material_master 兜底");
    }
}
