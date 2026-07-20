package com.cpq.component.service;

import com.cpq.component.dto.ComponentDirectoryDTO;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 集成测试：组件目录树「显示停用」过滤（{@link ComponentDirectoryService#listTree(String, boolean)}）。
 *
 * <p>默认 {@code includeDisabled=false} 时后端在查询层就不返回 DISABLED 组件；
 * {@code includeDisabled=true} 时一并返回。
 */
@QuarkusTest
class ComponentDirectoryServiceTest {

    @Inject
    ComponentDirectoryService directoryService;

    @Inject
    EntityManager em;

    @Inject
    UserTransaction utx;

    private UUID dirId;
    private String activeCode;
    private String disabledCode;

    @BeforeEach
    void setup() throws Exception {
        dirId = UUID.randomUUID();
        String suffix = dirId.toString().substring(0, 8);
        activeCode = "COMP-DIRTEST-ACTIVE-" + suffix;
        disabledCode = "COMP-DIRTEST-DISABLED-" + suffix;
        utx.begin();
        em.joinTransaction();
        em.createNativeQuery(
                "INSERT INTO component_directory(id, name, sort_order, created_at) " +
                "VALUES (:id, 'DirServiceTestDir', 0, NOW())")
                .setParameter("id", dirId)
                .executeUpdate();
        insertComponent(activeCode, "启用组件", "ACTIVE");
        insertComponent(disabledCode, "停用组件", "DISABLED");
        utx.commit();
    }

    @AfterEach
    void cleanup() throws Exception {
        utx.begin();
        em.joinTransaction();
        em.createNativeQuery("DELETE FROM component WHERE directory_id = :dir")
                .setParameter("dir", dirId).executeUpdate();
        em.createNativeQuery("DELETE FROM component_directory WHERE id = :id")
                .setParameter("id", dirId).executeUpdate();
        utx.commit();
    }

    private void insertComponent(String code, String name, String status) {
        em.createNativeQuery(
                "INSERT INTO component(id, directory_id, name, code, column_count, fields, formulas, excel_columns, component_type, status, created_at, updated_at) " +
                "VALUES (:id, :dir, :name, :code, 0, '[]', '[]', '[]', 'NORMAL', :status, NOW(), NOW())")
                .setParameter("id", UUID.randomUUID())
                .setParameter("dir", dirId)
                .setParameter("name", name)
                .setParameter("code", code)
                .setParameter("status", status)
                .executeUpdate();
    }

    private List<String> codesInTestDir(boolean includeDisabled) {
        List<ComponentDirectoryDTO> tree = directoryService.listTree(null, includeDisabled);
        ComponentDirectoryDTO dir = findDir(tree, dirId);
        assertNotNull(dir, "测试目录应在树中");
        return dir.components.stream().map(c -> c.code).toList();
    }

    private ComponentDirectoryDTO findDir(List<ComponentDirectoryDTO> nodes, UUID id) {
        for (ComponentDirectoryDTO n : nodes) {
            if (id.equals(n.id)) return n;
            ComponentDirectoryDTO found = findDir(n.children, id);
            if (found != null) return found;
        }
        return null;
    }

    @Test
    @DisplayName("includeDisabled=false 默认不返回 DISABLED 组件")
    void defaultExcludesDisabled() {
        List<String> codes = codesInTestDir(false);
        assertTrue(codes.contains(activeCode), "应含启用组件");
        assertFalse(codes.contains(disabledCode), "默认不应含停用组件，实际: " + codes);
    }

    @Test
    @DisplayName("includeDisabled=true 返回 DISABLED 组件")
    void includeDisabledReturnsAll() {
        List<String> codes = codesInTestDir(true);
        assertTrue(codes.contains(activeCode), "应含启用组件");
        assertTrue(codes.contains(disabledCode), "勾选后应含停用组件，实际: " + codes);
    }
}
