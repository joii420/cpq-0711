package com.cpq.component.service;

import com.cpq.component.dto.CreateComponentSqlViewRequest;
import com.cpq.component.entity.Component;
import com.cpq.component.entity.ComponentSqlView;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
public class DriverViewDesignationTest {

    @Inject
    ComponentService componentService;

    @Inject
    ComponentSqlViewService sqlViewService;

    private CreateComponentSqlViewRequest viewReq(String name) {
        CreateComponentSqlViewRequest r = new CreateComponentSqlViewRequest();
        r.sqlViewName = name;
        r.sqlTemplate = "SELECT 1 AS x";
        r.scope = "COMPONENT";
        return r;
    }

    /** 建一个 NORMAL 组件 + 一张 ACTIVE 视图，返回 component。 */
    private Component newComponentWithView(String viewName) {
        Component c = new Component();
        c.code = "TEST-DRV-" + UUID.randomUUID().toString().substring(0, 8);
        c.name = "driver-test";
        c.componentType = "NORMAL";
        c.fields = "[]";
        c.formulas = "[]";
        c.persist();

        ComponentSqlView v = new ComponentSqlView();
        v.componentId = c.id;
        v.sqlViewName = viewName;
        v.sqlTemplate = "SELECT 1 AS x";
        v.declaredColumns = "[]";
        v.scope = "COMPONENT";
        v.status = "ACTIVE";
        v.persist();
        return c;
    }

    @Test
    @TestTransaction
    void setDriverView_setsDollarPrefixedPath() {
        Component c = newComponentWithView("cz_view");
        componentService.setDriverView(c.id, "cz_view");
        Component reloaded = Component.findById(c.id);
        assertEquals("$cz_view", reloaded.dataDriverPath);
    }

    @Test
    @TestTransaction
    void setDriverView_nullClearsDriver() {
        Component c = newComponentWithView("cz_view");
        componentService.setDriverView(c.id, "cz_view");
        componentService.setDriverView(c.id, null);
        Component reloaded = Component.findById(c.id);
        assertTrue(reloaded.dataDriverPath == null || reloaded.dataDriverPath.isBlank());
    }

    @Test
    @TestTransaction
    void setDriverView_unknownViewRejected() {
        Component c = newComponentWithView("cz_view");
        assertThrows(RuntimeException.class,
                () -> componentService.setDriverView(c.id, "no_such_view"));
    }

    @Test
    @TestTransaction
    void setDriverView_switchOverwritesPrevious() {
        Component c = newComponentWithView("view_a");
        ComponentSqlView vb = new ComponentSqlView();
        vb.componentId = c.id;
        vb.sqlViewName = "view_b";
        vb.sqlTemplate = "SELECT 1 AS x";
        vb.declaredColumns = "[]";
        vb.scope = "COMPONENT";
        vb.status = "ACTIVE";
        vb.persist();

        componentService.setDriverView(c.id, "view_a");
        componentService.setDriverView(c.id, "view_b");
        Component reloaded = Component.findById(c.id);
        assertEquals("$view_b", reloaded.dataDriverPath);
    }

    @Test
    @TestTransaction
    void createFirstView_becomesDriverWhenNoneSet() {
        Component c = new Component();
        c.code = "TEST-DRV-" + UUID.randomUUID().toString().substring(0, 8);
        c.name = "driver-test"; c.componentType = "NORMAL";
        c.fields = "[]"; c.formulas = "[]"; c.persist();

        sqlViewService.create(c.id, viewReq("first_view"), null);
        assertEquals("$first_view", ((Component) Component.findById(c.id)).dataDriverPath);
    }

    @Test
    @TestTransaction
    void createSecondView_doesNotStealDriver() {
        Component c = new Component();
        c.code = "TEST-DRV-" + UUID.randomUUID().toString().substring(0, 8);
        c.name = "driver-test"; c.componentType = "NORMAL";
        c.fields = "[]"; c.formulas = "[]"; c.persist();

        sqlViewService.create(c.id, viewReq("first_view"), null);
        sqlViewService.create(c.id, viewReq("second_view"), null);
        assertEquals("$first_view", ((Component) Component.findById(c.id)).dataDriverPath);
    }

    @Test
    @TestTransaction
    void deleteDriverView_clearsDriver() {
        Component c = new Component();
        c.code = "TEST-DRV-" + UUID.randomUUID().toString().substring(0, 8);
        c.name = "driver-test"; c.componentType = "NORMAL";
        c.fields = "[]"; c.formulas = "[]"; c.persist();

        var dto = sqlViewService.create(c.id, viewReq("first_view"), null);
        assertEquals("$first_view", ((Component) Component.findById(c.id)).dataDriverPath);
        sqlViewService.delete(c.id, dto.id);
        String p = ((Component) Component.findById(c.id)).dataDriverPath;
        assertTrue(p == null || p.isBlank());
    }

    @Test
    @TestTransaction
    void deleteNonDriverView_doesNotClearDriver() {
        Component c = new Component();
        c.code = "TEST-DRV-" + UUID.randomUUID().toString().substring(0, 8);
        c.name = "driver-test"; c.componentType = "NORMAL";
        c.fields = "[]"; c.formulas = "[]"; c.persist();

        sqlViewService.create(c.id, viewReq("first_view"), null);   // becomes driver
        var second = sqlViewService.create(c.id, viewReq("second_view"), null); // not driver
        sqlViewService.delete(c.id, second.id);
        assertEquals("$first_view", ((Component) Component.findById(c.id)).dataDriverPath);
    }
}
