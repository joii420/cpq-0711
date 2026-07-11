package com.cpq.basicdata.v6.versioning;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class PricingVersioningWriterTest {

    @Inject VersionedV6Writer writer;
    @Inject EntityManager em;

    /** 描述列(production_no)写入但不参与比对：仅描述列变化 → 不升版、值仍写入。 */
    @Test
    @Transactional
    void descriptorColumn_writtenButNotCompared() {
        String mat = "T0D" + UUID.randomUUID().toString().substring(0, 12);   // material_no VARCHAR(20)
        writeOne(mat, "OP1", "prodA");
        writeOne(mat, "OP1", "prodB");   // 仅描述列 production_no 变
        Number versions = (Number) em.createNativeQuery(
            "SELECT count(DISTINCT calc_version) FROM production_energy WHERE material_no=:m")
            .setParameter("m", mat).getSingleResult();
        assertEquals(1, versions.intValue(), "仅描述列变化不应升版");
        String prod = (String) em.createNativeQuery(
            "SELECT production_no FROM production_energy WHERE material_no=:m AND is_current=TRUE LIMIT 1")
            .setParameter("m", mat).getSingleResult();
        assertEquals("prodB", prod, "描述列新值应写入");
    }

    /** 新表已登记白名单:contentColumns 值变 → 升版(2000→2001)。 */
    @Test
    @Transactional
    void newTableRegistered_valueChange_upversions() {
        String mat = "T0V" + UUID.randomUUID().toString().substring(0, 12);   // material_no VARCHAR(20)
        writeOne(mat, "OP1", "prodA");                // 首版 2000
        writeUnit(mat, "OP1", new java.math.BigDecimal("9.9"), "prodA");  // unit_price 变 → 升版
        Number cnt = (Number) em.createNativeQuery(
            "SELECT count(DISTINCT calc_version) FROM production_energy WHERE material_no=:m")
            .setParameter("m", mat).getSingleResult();
        assertEquals(2, cnt.intValue(), "值变应升版");
        Number cur = (Number) em.createNativeQuery(
            "SELECT count(*) FROM production_energy WHERE material_no=:m AND is_current=TRUE")
            .setParameter("m", mat).getSingleResult();
        assertEquals(1, cur.intValue(), "当前版本行唯一");
    }

    private void writeOne(String mat, String processNo, String productionNo) {
        writeUnit(mat, processNo, new java.math.BigDecimal("1.5"), productionNo);
    }
    private void writeUnit(String mat, String processNo, java.math.BigDecimal price, String productionNo) {
        Map<String,Object> gk = new LinkedHashMap<>();
        gk.put("system_type", "PRICING");
        gk.put("material_no", mat);
        gk.put("price_type", "ENERGY");
        Map<String,Object> row = new LinkedHashMap<>();
        row.put("process_no", processNo);
        row.put("unit_price", price);
        row.put("currency", "CNY");
        row.put("unit", "元");
        row.put("production_no", productionNo);   // 描述列
        LinkedHashMap<Map<String,Object>, List<Map<String,Object>>> groups = new LinkedHashMap<>();
        groups.put(gk, List.of(row));
        writer.writeVersionedGroups("production_energy", "calc_version",
            List.of("process_no","unit_price","currency","unit"), null,
            List.of("production_no"), groups);
    }
}
