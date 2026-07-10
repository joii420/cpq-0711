package com.cpq.configure.service;

import com.cpq.common.exception.BusinessException;
import com.cpq.configure.dto.ElementDTO;
import com.cpq.configure.dto.ElementUpsertRequest;
import com.cpq.configure.entity.Element;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.ws.rs.NotFoundException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 元素主表管理服务测试（task-0709 · B6）。@TestTransaction 回滚不污染共享 DB。
 */
@QuarkusTest
public class ElementServiceTest {

    @Inject
    ElementService service;

    @Inject
    EntityManager em;

    private ElementUpsertRequest req(String no, String code, String name) {
        ElementUpsertRequest r = new ElementUpsertRequest();
        r.elementNo = no;
        r.elementCode = code;
        r.elementName = name;
        return r;
    }

    /** 造一个被引用：新建材质 + 一条 material_recipe_element 按 element_no 引用。 */
    private void seedReference(String elementNo, String elementCode) {
        UUID recipeId = UUID.randomUUID();
        em.createNativeQuery(
            "INSERT INTO material_recipe (id, code, symbol, recipe_type, status, sort_order, created_at, updated_at) " +
            "VALUES (:id, :c, 'TSTSYM', 'locked', 'ACTIVE', 1, NOW(), NOW())")
            .setParameter("id", recipeId)
            .setParameter("c", "TMAT-" + recipeId.toString().substring(0, 8))
            .executeUpdate();
        em.createNativeQuery(
            "INSERT INTO material_recipe_element " +
            "(id, recipe_id, element_no, element_code, element_name, default_pct, is_locked, sort_order, created_at) " +
            "VALUES (gen_random_uuid(), :r, :no, :code, 'x', 100, true, 1, NOW())")
            .setParameter("r", recipeId).setParameter("no", elementNo).setParameter("code", elementCode)
            .executeUpdate();
    }

    // ── create ──

    @Test
    @TestTransaction
    void create_newElement_referencedCountZero_codeLockedFalse() {
        ElementDTO d = service.create(req("T90101", "TeX", "测元素"));
        assertEquals("T90101", d.elementNo);
        assertEquals("TeX", d.elementCode);
        assertEquals("ACTIVE", d.status);
        assertEquals(0, d.referencedCount);
        assertFalse(d.codeLocked);
    }

    @Test
    @TestTransaction
    void create_duplicateElementNo_409() {
        service.create(req("T90102", "TeY", "测元素Y"));
        BusinessException ex = assertThrows(BusinessException.class,
            () -> service.create(req("T90102", "TeZ", "测元素Z")));
        assertEquals(409, ex.getCode());
    }

    @Test
    @TestTransaction
    void create_duplicateElementCode_409() {
        service.create(req("T90103", "TeDup", "测元素"));
        BusinessException ex = assertThrows(BusinessException.class,
            () -> service.create(req("T90104", "TeDup", "另一个")));
        assertEquals(409, ex.getCode());
    }

    // ── 符号锁 ──

    @Test
    @TestTransaction
    void symbolLock_referencedElement_cannotChangeCode_409() {
        service.create(req("T90201", "TeLock", "锁测"));
        seedReference("T90201", "TeLock");
        BusinessException ex = assertThrows(BusinessException.class,
            () -> service.update("T90201", req("T90201", "TeLockNew", "锁测")));
        assertEquals(409, ex.getCode());
        assertTrue(ex.getMessage().contains("引用"), "409 原因应含'引用'");
        // 符号未变
        Element e = Element.find("elementNo", "T90201").firstResult();
        assertEquals("TeLock", e.elementCode);
    }

    @Test
    @TestTransaction
    void unreferencedElement_canChangeCode() {
        service.create(req("T90202", "TeFree", "自由改"));
        ElementDTO d = service.update("T90202", req("T90202", "TeFreeNew", "自由改"));
        assertEquals("TeFreeNew", d.elementCode, "未引用元素符号可改");
        assertFalse(d.codeLocked);
    }

    @Test
    @TestTransaction
    void unreferencedElement_changeCode_toExistingCode_409() {
        service.create(req("T90203", "TeA", "A"));
        service.create(req("T90204", "TeB", "B"));
        BusinessException ex = assertThrows(BusinessException.class,
            () -> service.update("T90204", req("T90204", "TeA", "B")));
        assertEquals(409, ex.getCode());
    }

    @Test
    @TestTransaction
    void update_nameAndStatus_alwaysEditable_evenWhenReferenced() {
        service.create(req("T90205", "TeNM", "旧名"));
        seedReference("T90205", "TeNM");
        // 被引用也能改中文名 + 停用
        ElementUpsertRequest r = req("T90205", "TeNM", "新名");
        r.status = "INACTIVE";
        ElementDTO d = service.update("T90205", r);
        assertEquals("新名", d.elementName);
        assertEquals("INACTIVE", d.status);
        assertEquals("TeNM", d.elementCode, "符号不变（未尝试改符号）");
    }

    @Test
    @TestTransaction
    void update_elementNo_immutable_ignoresBodyElementNo() {
        service.create(req("T90206", "TeIm", "编号不变"));
        // 请求体里塞不同 elementNo → 被忽略（路径为准）
        service.update("T90206", req("T99999", "TeIm", "改中文"));
        assertNotNull(Element.find("elementNo", "T90206").firstResult(), "原 element_no 仍在");
        assertNull(Element.find("elementNo", "T99999").firstResult(), "请求体 element_no 未生效");
    }

    // ── 停用 ──

    @Test
    @TestTransaction
    void softDelete_setsInactive_idempotent_keepsMaterialRecipeElement() {
        service.create(req("T90301", "TeDel", "停用测"));
        seedReference("T90301", "TeDel");
        long mreBefore = countMre("T90301");

        service.softDelete("T90301");
        assertEquals("INACTIVE", Element.<Element>find("elementNo", "T90301").firstResult().status);
        service.softDelete("T90301");   // 幂等
        assertEquals("INACTIVE", Element.<Element>find("elementNo", "T90301").firstResult().status);

        assertEquals(mreBefore, countMre("T90301"), "停用不动 material_recipe_element（历史材质照常）");
    }

    @Test
    @TestTransaction
    void softDelete_notFound_404() {
        assertThrows(NotFoundException.class, () -> service.softDelete("T_NO_SUCH"));
    }

    // ── list / referencedCount ──

    @Test
    @TestTransaction
    void list_referencedCount_matchesActualRows_andKeyword() {
        service.create(req("T90401", "TeRef", "引用计数"));
        seedReference("T90401", "TeRef");
        seedReference("T90401", "TeRef");   // 2 行引用

        List<ElementDTO> byNo = service.list("T90401");
        ElementDTO d = byNo.stream().filter(x -> "T90401".equals(x.elementNo)).findFirst().orElseThrow();
        assertEquals(2, d.referencedCount, "referencedCount = 实际 material_recipe_element 行数");
        assertTrue(d.codeLocked, "被引用 → codeLocked=true");

        // 关键字命中中文名 / 符号
        assertTrue(service.list("引用计数").stream().anyMatch(x -> "T90401".equals(x.elementNo)), "按中文名命中");
        assertTrue(service.list("TeRef").stream().anyMatch(x -> "T90401".equals(x.elementNo)), "按符号命中");
    }

    private long countMre(String elementNo) {
        Number n = (Number) em.createNativeQuery(
                "SELECT COUNT(*) FROM material_recipe_element WHERE element_no = :no")
            .setParameter("no", elementNo).getSingleResult();
        return n.longValue();
    }
}
