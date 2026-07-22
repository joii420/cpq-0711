package com.cpq.component.service;

import com.cpq.common.exception.BusinessException;
import com.cpq.component.dto.ComponentDTO;
import com.cpq.component.dto.CreateComponentRequest;
import com.cpq.component.entity.Component;
import com.cpq.template.entity.Template;
import com.cpq.template.entity.TemplateComponent;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * task-0721 B4 — 页签类型属性值域校验 + COSTING 模板反向护栏（2026-07-21 业务方裁决）单测。
 *
 * <p>核心风险：{@code bomRecursiveExpand} 是组件级全局开关，同一组件被多模板共用时一开全生效。
 * 现网 3 个开启该开关的组件（COMP-0021__imp1__imp1 / COMP-0039 / COMP-0042）共 34 处模板引用，
 * 全部在 COSTING 模板——若允许把已被 COSTING 引用的组件设为 {@code tabType=BOM}，会把这些核价
 * 模板一并改成树渲染，直接违反 AC-10 核价零回归门禁。
 */
@QuarkusTest
class ComponentServiceTabTypeGuardTest {

    @Inject
    ComponentService svc;

    private CreateComponentRequest minimalRequest(String name) {
        CreateComponentRequest req = new CreateComponentRequest();
        req.name = name;
        req.fields = List.of();
        req.formulas = List.of();
        return req;
    }

    @Test
    @TestTransaction
    void tabTypeInvalidValue_rejects400() {
        CreateComponentRequest req = minimalRequest("测试组件-非法tabType");
        req.tabType = "不存在的类型";
        BusinessException ex = assertThrows(BusinessException.class, () -> svc.create(req));
        assertEquals(400, ex.getCode());
    }

    @Test
    @TestTransaction
    void tabTypeBom_allFiveValuesStoreAndReadBack() {
        for (String tt : List.of("BOM", "材质元素", "零件", "外购件", "主件")) {
            CreateComponentRequest req = minimalRequest("测试组件-" + tt);
            req.tabType = tt;
            if (!"BOM".equals(tt)) req.partNoField = "料号"; // 非树页签必须配 partNoField
            ComponentDTO dto = svc.create(req);
            assertEquals(tt, dto.tabType, "tabType=" + tt + " 应能存能读");
        }
    }

    // ── task-0721（2026-07-21 补录）：part_no_field / part_name_field ──────────

    @Test
    @TestTransaction
    void restrictedTabType_missingPartNoField_rejects400() {
        for (String tt : List.of("材质元素", "零件", "外购件", "主件")) {
            CreateComponentRequest req = minimalRequest("测试组件-缺料号列-" + tt);
            req.tabType = tt;
            BusinessException ex = assertThrows(BusinessException.class, () -> svc.create(req),
                    "tabType=" + tt + " 缺 partNoField 应 400");
            assertEquals(400, ex.getCode());
            assertTrue(ex.getMessage().contains("partNoField"), ex.getMessage());
        }
    }

    @Test
    @TestTransaction
    void bomTabType_doesNotRequirePartNoField() {
        CreateComponentRequest req = minimalRequest("测试组件-BOM无需料号列");
        req.tabType = "BOM";
        ComponentDTO dto = svc.create(req); // 不传 partNoField，不应 400
        assertEquals("BOM", dto.tabType);
        assertNull(dto.partNoField);
    }

    @Test
    @TestTransaction
    void restrictedTabType_withPartNoField_savesAndReadsBack() {
        CreateComponentRequest req = minimalRequest("测试组件-带料号列");
        req.tabType = "材质元素";
        req.partNoField = "料号";
        req.partNameField = "料号名称";
        ComponentDTO dto = svc.create(req);
        assertEquals("料号", dto.partNoField);
        assertEquals("料号名称", dto.partNameField);
    }

    @Test
    @TestTransaction
    void updateOnlyPartNoField_stillValidatedAgainstExistingTabType() {
        // 先建一条 tabType=材质元素 + partNoField=料号 的合法组件
        CreateComponentRequest req = minimalRequest("测试组件-仅改料号列");
        req.tabType = "材质元素";
        req.partNoField = "料号";
        ComponentDTO dto = svc.create(req);

        // 之后的更新只想把 partNoField 清空(空串)、不碰 tabType → 仍应按"材质元素需要 partNoField"校验拦截
        CreateComponentRequest upd = minimalRequest("测试组件-仅改料号列");
        upd.partNoField = ""; // 显式清空
        BusinessException ex = assertThrows(BusinessException.class, () -> svc.update(dto.id, upd));
        assertEquals(400, ex.getCode());
    }

    @Test
    @TestTransaction
    void createWithTabTypeBom_autoSyncsBomRecursiveExpandTrue() {
        CreateComponentRequest req = minimalRequest("测试组件-BOM自动同步");
        req.tabType = "BOM";
        ComponentDTO dto = svc.create(req);
        assertTrue(dto.bomRecursiveExpand, "tabType=BOM 应自动同步 bomRecursiveExpand=true");
    }

    @Test
    @TestTransaction
    void updateTabTypeAwayFromBom_autoSyncsBomRecursiveExpandFalse() {
        CreateComponentRequest req = minimalRequest("测试组件-BOM转零件");
        req.tabType = "BOM";
        ComponentDTO created = svc.create(req);
        assertTrue(created.bomRecursiveExpand);

        CreateComponentRequest upd = minimalRequest("测试组件-BOM转零件");
        upd.tabType = "零件";
        upd.partNoField = "料号"; // 零件类型需要 partNoField
        ComponentDTO updated = svc.update(created.id, upd);
        assertFalse(updated.bomRecursiveExpand, "tabType 改为非 BOM 应自动同步 bomRecursiveExpand=false");
    }

    /**
     * 核心护栏用例：组件已被 COSTING 模板引用 → 禁止改成 tabType=BOM，返回 400。
     */
    @Test
    @TestTransaction
    void componentReferencedByCostingTemplate_cannotBecomeBomTab() {
        // ① 建一个未设 tabType 的普通组件
        CreateComponentRequest req = minimalRequest("测试组件-核价引用护栏");
        ComponentDTO dto = svc.create(req);
        Component component = Component.findById(dto.id);
        assertNotNull(component);

        // ② 建一个 COSTING 模板 + template_component 关联本组件
        Template costingTpl = new Template();
        costingTpl.templateSeriesId = UUID.randomUUID();
        costingTpl.name = "核价模板-护栏测试";
        costingTpl.templateKind = "COSTING";
        costingTpl.status = "DRAFT";
        costingTpl.createdAt = OffsetDateTime.now();
        costingTpl.updatedAt = OffsetDateTime.now();
        costingTpl.persist();

        TemplateComponent tc = new TemplateComponent();
        tc.templateId = costingTpl.id;
        tc.componentId = component.id;
        tc.tabName = "工序";
        tc.createdAt = OffsetDateTime.now();
        tc.persist();

        // ③ 试图把该组件设为 tabType=BOM → 必须 400，且组件本身不受影响(仍非 BOM)
        CreateComponentRequest upd = minimalRequest("测试组件-核价引用护栏");
        upd.tabType = "BOM";
        BusinessException ex = assertThrows(BusinessException.class, () -> svc.update(dto.id, upd));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("核价"), "错误文案应说明核价引用原因: " + ex.getMessage());

        Component reloaded = Component.findById(dto.id);
        assertNull(reloaded.tabType, "护栏拦截后组件 tabType 不应被改动");
        assertFalse(reloaded.bomRecursiveExpand, "护栏拦截后 bomRecursiveExpand 不应被改动");
    }

    /** 对照组：未被任何 COSTING 模板引用的组件，正常放行设为 tabType=BOM。 */
    @Test
    @TestTransaction
    void componentNotReferencedByCosting_canBecomeBomTab() {
        CreateComponentRequest req = minimalRequest("测试组件-无核价引用");
        ComponentDTO dto = svc.create(req);

        CreateComponentRequest upd = minimalRequest("测试组件-无核价引用");
        upd.tabType = "BOM";
        ComponentDTO updated = svc.update(dto.id, upd);
        assertEquals("BOM", updated.tabType);
        assertTrue(updated.bomRecursiveExpand);
    }

    /** 对照组：组件被 QUOTATION（非 COSTING）模板引用，不受护栏影响。 */
    @Test
    @TestTransaction
    void componentReferencedByQuotationTemplateOnly_canBecomeBomTab() {
        CreateComponentRequest req = minimalRequest("测试组件-仅报价模板引用");
        ComponentDTO dto = svc.create(req);

        Template quoteTpl = new Template();
        quoteTpl.templateSeriesId = UUID.randomUUID();
        quoteTpl.name = "报价模板-护栏对照";
        quoteTpl.templateKind = "QUOTATION";
        quoteTpl.status = "DRAFT";
        quoteTpl.createdAt = OffsetDateTime.now();
        quoteTpl.updatedAt = OffsetDateTime.now();
        quoteTpl.persist();

        TemplateComponent tc = new TemplateComponent();
        tc.templateId = quoteTpl.id;
        tc.componentId = dto.id;
        tc.tabName = "BOM";
        tc.createdAt = OffsetDateTime.now();
        tc.persist();

        CreateComponentRequest upd = minimalRequest("测试组件-仅报价模板引用");
        upd.tabType = "BOM";
        ComponentDTO updated = svc.update(dto.id, upd);
        assertEquals("BOM", updated.tabType);
    }
}
