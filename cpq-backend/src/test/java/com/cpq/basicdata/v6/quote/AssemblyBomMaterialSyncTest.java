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
 * update-0723 B3 重构：原「组成件BOM」sheet 已删除，本类原有的双 List 调用 + "组装工序"名称回填
 * 工序（{@code processMasterRepo.findFirstByProcessName}）机制均已随之下线（B4 改为「自制加工费」
 * 投入料号→工序编号 map 反填，见 {@code MaterialBomMergeHandlerTest#b4_operationNo_backfilledFromSharedCache}）。
 *
 * <p>本类仅保留仍然有效的语义：已存在 material_master 行的 material_type 不被覆盖
 * （preserve=true，决策 #6，B6 后依然成立——只是新值域变成 零件/外购件）。
 */
@QuarkusTest
class AssemblyBomMaterialSyncTest {

    @Inject MaterialBomMergeHandler handler;
    @Inject EntityManager em;

    static final String CUST = "TSTASM0723";
    static final String MAT  = "TESTASM0723";

    @Transactional
    void cleanup() {
        em.createNativeQuery("DELETE FROM material_bom_item WHERE material_no=:m").setParameter("m", MAT).executeUpdate();
        em.createNativeQuery("DELETE FROM material_bom WHERE material_no=:m").setParameter("m", MAT).executeUpdate();
        em.createNativeQuery("DELETE FROM material_customer_map WHERE material_no IN ('ASM0723-EXIST')").executeUpdate();
        em.createNativeQuery("DELETE FROM material_master WHERE material_no IN ('ASM0723-EXIST')").executeUpdate();
    }
    @BeforeEach void before() { cleanup(); }
    @AfterEach  void after()  { cleanup(); }

    private ImportContext ctx() {
        ImportContext c = new ImportContext();
        c.customerNo = CUST; c.systemType = "QUOTE"; c.importedBy = null; return c;
    }

    private SheetRow bomRow(int seq, String comp, String name) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("销售料号", MAT); m.put("项次", String.valueOf(seq));
        if (comp != null) m.put("投入料号", comp);
        if (name != null) m.put("投入料号名称", name);
        m.put("产出料号类型", "2.非银点类"); m.put("材料毛重", "1.0"); m.put("重量单位", "KG");
        return new SheetRow(seq, m);
    }

    private String typeOf(String no) {
        var r = em.createNativeQuery("SELECT material_type FROM material_master WHERE material_no=:n")
            .setParameter("n", no).getResultList();
        return r.isEmpty() ? null : String.valueOf(r.get(0));
    }

    @Transactional
    void em_upsertMaster(String no, String name, String type) {
        em.createNativeQuery("INSERT INTO material_master (id, material_no, material_name, material_type, created_at, updated_at) " +
            "VALUES (gen_random_uuid(), :no, :nm, :tp, NOW(), NOW()) ON CONFLICT (material_no) DO UPDATE SET material_type=:tp")
            .setParameter("no", no).setParameter("nm", name).setParameter("tp", type).executeUpdate();
    }

    @Transactional
    @Test
    void existingComponent_keepsOriginalType() {
        em_upsertMaster("ASM0723-EXIST", "ASM0723-E1", "自定义历史类型");
        handler.merge(List.of(bomRow(1, "ASM0723-EXIST", "ASM0723-E1")), ctx());
        assertEquals("自定义历史类型", typeOf("ASM0723-EXIST"), "已存在保留原 type，不被新导入覆盖（preserve=true）");
    }
}
