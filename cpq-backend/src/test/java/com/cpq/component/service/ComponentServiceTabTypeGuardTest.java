package com.cpq.component.service;

import com.cpq.common.exception.BusinessException;
import com.cpq.component.dto.ComponentDTO;
import com.cpq.component.dto.CreateComponentRequest;
import com.cpq.component.entity.Component;
import com.cpq.template.entity.Template;
import com.cpq.template.entity.TemplateComponent;
import com.cpq.template.entity.TemplateComponentSnapshot;
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

    // ── task-0721（2026-07-21 补录，2026-07-23 放宽为"料号列或名称列至少一个"）：
    //    part_no_field / part_name_field ──────────

    @Test
    @TestTransaction
    void restrictedTabType_missingBothIdentifierFields_rejects400() {
        for (String tt : List.of("材质元素", "零件", "外购件", "主件")) {
            CreateComponentRequest req = minimalRequest("测试组件-缺标识列-" + tt);
            req.tabType = tt;
            BusinessException ex = assertThrows(BusinessException.class, () -> svc.create(req),
                    "tabType=" + tt + " 料号列/名称列均缺应 400");
            assertEquals(400, ex.getCode());
            assertTrue(ex.getMessage().contains("料号列或名称列"), ex.getMessage());
        }
    }

    /**
     * 2026-07-23 修订核心用例：只配 {@code partNameField}（不配 partNoField）应能正常保存——
     * 对齐委托方截图场景（「外购件/费用」类页签只有「料件名称」列，没有料号列）。
     */
    @Test
    @TestTransaction
    void restrictedTabType_withOnlyPartNameField_savesSuccessfully() {
        for (String tt : List.of("材质元素", "零件", "外购件", "主件")) {
            CreateComponentRequest req = minimalRequest("测试组件-仅名称列-" + tt);
            req.tabType = tt;
            req.partNameField = "料件名称"; // 不设 partNoField
            ComponentDTO dto = svc.create(req);
            assertEquals(tt, dto.tabType, "tabType=" + tt + " 仅配 partNameField 应能保存成功(不 400)");
            assertNull(dto.partNoField, "partNoField 未配置应保持 null");
            assertEquals("料件名称", dto.partNameField);
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

    // ══════════════════════════════════════════════════════════════════════════
    // repair-0814（2026-08-14）：护栏判定收窄到「未冻结的引用」
    //
    // 背景：上面那条核心护栏用例 componentReferencedByCostingTemplate_cannotBecomeBomTab
    // 构造的 COSTING 模板是 status="DRAFT" —— 恰好是「至今仍该拦」的那一档，因此它在本次
    // 改造后【语义不变、仍然全绿】，一个字符都没改。这也正是本缺陷长期不被发现的原因：
    // 「已冻结 PUBLISHED 引用 → 应放行」这条语义从来没有测试覆盖过。
    //
    // 下面 5 条补齐两个方向。测试目录：dev-docs/task-0806-模板发布全量冻结/
    //   repair-0814-发布冻结后tabType护栏误拦/{问题说明.md, test.md}
    // ══════════════════════════════════════════════════════════════════════════

    /** 建一张 COSTING 模板并把 component 绑上去，返回 (template, tc)。 */
    private Object[] costingTemplateReferencing(UUID componentId, String status, String name) {
        Template tpl = new Template();
        tpl.templateSeriesId = UUID.randomUUID();
        tpl.name = name;
        tpl.version = "v1.0";
        tpl.templateKind = "COSTING";
        tpl.status = status;
        tpl.createdAt = OffsetDateTime.now();
        tpl.updatedAt = OffsetDateTime.now();
        tpl.persist();

        TemplateComponent tc = new TemplateComponent();
        tc.templateId = tpl.id;
        tc.componentId = componentId;
        tc.tabName = "工序";
        tc.sortOrder = 0;
        tc.createdAt = OffsetDateTime.now();
        tc.persist();
        return new Object[]{tpl, tc};
    }

    /** 给某个 tc 落一行冻结快照（内容不重要，本护栏只关心「有没有行」）。 */
    private void freeze(Template tpl, TemplateComponent tc, UUID componentId) {
        TemplateComponentSnapshot s = new TemplateComponentSnapshot();
        s.templateId = tpl.id;
        s.templateComponentId = tc.id;
        s.componentId = componentId;
        s.sortOrder = 0;
        s.tabName = tc.tabName;
        s.persist();
    }

    /**
     * TC-01（AC-1，本次修复的核心阴性用例）：引用来自**已冻结**的 PUBLISHED 核价模板 → 放行。
     *
     * <p>这就是 2026-08-14 用户撞到的场景（COMP-0233 被「核价模板-简易 v1.1(PUBLISHED)」引用）。
     * 冻结后该模板渲染读 template_component_snapshot，改活表组件影响不到它 → 没有理由拦。
     */
    @Test
    @TestTransaction
    void referencedByFrozenPublishedCosting_canBecomeBomTab() {
        ComponentDTO dto = svc.create(minimalRequest("测试组件-已冻结PUBLISHED引用"));
        Object[] pair = costingTemplateReferencing(dto.id, "PUBLISHED", "核价模板-已冻结");
        freeze((Template) pair[0], (TemplateComponent) pair[1], dto.id);

        CreateComponentRequest upd = minimalRequest("测试组件-已冻结PUBLISHED引用");
        upd.tabType = "BOM";
        ComponentDTO updated = svc.update(dto.id, upd);

        assertEquals("BOM", updated.tabType, "引用全部已冻结时应放行");
        assertTrue(updated.bomRecursiveExpand, "放行后仍须自动同步 bomRecursiveExpand=true");
    }

    /** TC-04（AC-2）：ARCHIVED + 有快照，同样是已冻结 → 放行。 */
    @Test
    @TestTransaction
    void referencedByFrozenArchivedCosting_canBecomeBomTab() {
        ComponentDTO dto = svc.create(minimalRequest("测试组件-已冻结ARCHIVED引用"));
        Object[] pair = costingTemplateReferencing(dto.id, "ARCHIVED", "核价模板-已归档");
        freeze((Template) pair[0], (TemplateComponent) pair[1], dto.id);

        CreateComponentRequest upd = minimalRequest("测试组件-已冻结ARCHIVED引用");
        upd.tabType = "BOM";
        assertEquals("BOM", svc.update(dto.id, upd).tabType);
    }

    /**
     * TC-03（AC-3，阳性）：PUBLISHED 但**快照零行** = D17「未冻结」过渡态 → 仍须拦。
     *
     * <p>这一档最容易在实现时漏掉：只按 status 过滤就会错误放行，而这类模板渲染期
     * 并不走冻结快照（PublishedTemplateReader 会识别为未冻结），配置外溢风险真实存在。
     */
    @Test
    @TestTransaction
    void referencedByPublishedButUnfrozenCosting_cannotBecomeBomTab() {
        ComponentDTO dto = svc.create(minimalRequest("测试组件-PUBLISHED未冻结引用"));
        costingTemplateReferencing(dto.id, "PUBLISHED", "核价模板-未冻结");
        // 刻意不落快照

        CreateComponentRequest upd = minimalRequest("测试组件-PUBLISHED未冻结引用");
        upd.tabType = "BOM";
        BusinessException ex = assertThrows(BusinessException.class, () -> svc.update(dto.id, upd));
        assertEquals(400, ex.getCode());

        Component reloaded = Component.findById(dto.id);
        assertNull(reloaded.tabType, "被拦后 tabType 不应被改动");
        assertFalse(reloaded.bomRecursiveExpand, "被拦后 bomRecursiveExpand 不应被改动");
    }

    /**
     * TC-05 + TC-06（AC-2 / AC-6）：混合引用 —— 一张已冻结 PUBLISHED + 一张 DRAFT。
     * 必须拦（因为有未冻结的那张），且文案**只点名未冻结的那张**，不把已冻结的算进去。
     */
    @Test
    @TestTransaction
    void mixedReferences_blocksAndNamesOnlyUnfrozenTemplate() {
        ComponentDTO dto = svc.create(minimalRequest("测试组件-混合引用"));
        Object[] frozenPair = costingTemplateReferencing(dto.id, "PUBLISHED", "核价模板-已冻结的那张");
        freeze((Template) frozenPair[0], (TemplateComponent) frozenPair[1], dto.id);
        costingTemplateReferencing(dto.id, "DRAFT", "核价模板-草稿的那张");

        CreateComponentRequest upd = minimalRequest("测试组件-混合引用");
        upd.tabType = "BOM";
        BusinessException ex = assertThrows(BusinessException.class, () -> svc.update(dto.id, upd));

        assertEquals(400, ex.getCode());
        String msg = ex.getMessage();
        assertTrue(msg.contains("核价模板-草稿的那张"), "文案须点名未冻结的模板: " + msg);
        assertFalse(msg.contains("核价模板-已冻结的那张"), "文案不得点名已冻结的模板: " + msg);
        assertTrue(msg.contains("DRAFT"), "文案须带模板状态: " + msg);
        // AC-6：旧文案（冻结后已成错误陈述）必须消失
        assertFalse(msg.contains("一并改成树渲染"), "旧的错误因果陈述必须删除: " + msg);
        // 多行 → 前端 ComponentManagement#showSaveError 走常驻 notification 而非 3s toast
        assertTrue(msg.contains("\n"), "文案须多行，否则前端只弹 3s toast 看不清: " + msg);
    }
}
