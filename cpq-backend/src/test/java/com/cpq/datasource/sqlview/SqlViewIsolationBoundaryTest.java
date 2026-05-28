package com.cpq.datasource.sqlview;

import com.cpq.datasource.sqlview.SqlViewRuntimeContext.OwnerType;
import com.cpq.datasource.sqlview.SqlViewRuntimeContext.Snapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 单元测试：SqlViewRuntimeContext 约束 + 隔离边界逻辑（Phase 2 重写）。
 *
 * <p>V249 变更：
 * <ul>
 *   <li>OwnerType.COSTING_TEMPLATE → OwnerType.TEMPLATE</li>
 *   <li>Snapshot 去除独立的 costingTemplateId 字段，统一复用 templateId 字段</li>
 *   <li>TEMPLATE ownerType 时：componentId 非空 → 抛 IllegalArgumentException（互斥）</li>
 *   <li>删除原 4 个 COMPONENT/COSTING_TEMPLATE 互斥约束场景（字段设计变了）</li>
 *   <li>新增：TEMPLATE owner + $$ → 抛 BusinessException（通过 BnfPathLinter 间接测）</li>
 * </ul>
 *
 * <p>覆盖场景：
 * <ol>
 *   <li>COMPONENT + componentId=null → IllegalArgumentException</li>
 *   <li>TEMPLATE + templateId=null → IllegalArgumentException</li>
 *   <li>TEMPLATE + componentId 非 null → IllegalArgumentException（互斥）</li>
 *   <li>COMPONENT 合法构造 → 字段赋值正确</li>
 *   <li>TEMPLATE 合法构造 → 字段赋值正确</li>
 *   <li>EMPTY Snapshot → ownerType=null</li>
 *   <li>set / setCostingTemplate(legacy) / setTemplate(new) → ownerType 路由</li>
 *   <li>setNested / setNestedTemplate / restore 保存恢复</li>
 *   <li>isQuotationFrozen() 状态机</li>
 * </ol>
 */
class SqlViewIsolationBoundaryTest {

    @AfterEach
    void cleanup() {
        SqlViewRuntimeContext.clear();
    }

    // ─────────────────── 边界场景 1：COMPONENT + componentId=null ────────────

    @Test
    void scenario1_component_owner_with_null_componentId_throws() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                new Snapshot(OwnerType.COMPONENT, null /* componentId=null */, null, null, null)
        );
        assertTrue(ex.getMessage().contains("componentId 不能为 null"),
                "错误信息应包含 componentId 不能为 null，实际：" + ex.getMessage());
    }

    // ─────────────────── 边界场景 2：TEMPLATE + templateId=null ─────────────

    @Test
    void scenario2_template_owner_with_null_templateId_throws() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                new Snapshot(OwnerType.TEMPLATE, null, null /* templateId=null */, null, null)
        );
        assertTrue(ex.getMessage().contains("templateId 不能为 null"),
                "错误信息应包含 templateId 不能为 null，实际：" + ex.getMessage());
    }

    // ─────────────────── 边界场景 3：TEMPLATE + componentId 非 null → 互斥 ────

    @Test
    void scenario3_template_owner_with_nonNull_componentId_throws() {
        UUID componentId = UUID.randomUUID();
        UUID templateId = UUID.randomUUID();
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                new Snapshot(OwnerType.TEMPLATE, componentId, templateId, null, null)
        );
        assertTrue(ex.getMessage().contains("互斥约束") || ex.getMessage().contains("componentId 必须为 null"),
                "错误信息应包含互斥约束相关描述，实际：" + ex.getMessage());
    }

    // ─────────────────── 正常路径：构造合规 Snapshot ─────────────────────────

    @Test
    void valid_component_snapshot_succeeds() {
        UUID componentId = UUID.randomUUID();
        UUID templateId = UUID.randomUUID();
        UUID quotationId = UUID.randomUUID();
        Snapshot s = new Snapshot(OwnerType.COMPONENT, componentId, templateId, quotationId, "DRAFT");
        assertEquals(OwnerType.COMPONENT, s.ownerType);
        assertEquals(componentId, s.componentId);
        assertEquals(templateId, s.templateId);
        assertEquals(quotationId, s.quotationId);
        assertEquals("DRAFT", s.quotationStatus);
    }

    @Test
    void valid_template_snapshot_succeeds() {
        UUID templateId = UUID.randomUUID();
        UUID quotationId = UUID.randomUUID();
        // TEMPLATE owner: componentId=null（互斥约束），templateId 非空
        Snapshot s = new Snapshot(OwnerType.TEMPLATE, null, templateId, quotationId, "PUBLISHED");
        assertEquals(OwnerType.TEMPLATE, s.ownerType);
        assertNull(s.componentId);
        assertEquals(templateId, s.templateId);
        assertEquals(quotationId, s.quotationId);
    }

    // ─────────────────── EMPTY Snapshot ──────────────────────────────────────

    @Test
    void empty_snapshot_has_null_ownerType() {
        assertNull(Snapshot.EMPTY.ownerType);
        assertNull(Snapshot.EMPTY.componentId);
        assertNull(Snapshot.EMPTY.templateId);
    }

    // ─────────────────── ThreadLocal API — 向后兼容入口（COMPONENT）───────────

    @Test
    void set_component_context_via_legacy_api() {
        UUID componentId = UUID.randomUUID();
        SqlViewRuntimeContext.set(componentId, null, null, "DRAFT");
        Snapshot s = SqlViewRuntimeContext.get();
        assertEquals(OwnerType.COMPONENT, s.ownerType);
        assertEquals(componentId, s.componentId);
        assertNull(s.templateId);
    }

    // ─────────────────── ThreadLocal API — 新签名（TEMPLATE）────────────────

    @Test
    void setTemplate_context_via_new_api() {
        UUID tId = UUID.randomUUID();
        SqlViewRuntimeContext.setTemplate(tId, null, "PUBLISHED");
        Snapshot s = SqlViewRuntimeContext.get();
        assertEquals(OwnerType.TEMPLATE, s.ownerType);
        assertEquals(tId, s.templateId);
        assertNull(s.componentId);
    }

    @Test
    void setNestedTemplate_and_restore() {
        UUID cid = UUID.randomUUID();
        UUID tId = UUID.randomUUID();
        // 先设组件上下文
        SqlViewRuntimeContext.set(cid, null, null, null);
        // 嵌套设模板上下文
        Snapshot prev = SqlViewRuntimeContext.setNestedTemplate(tId, null, "DRAFT");
        assertEquals(OwnerType.TEMPLATE, SqlViewRuntimeContext.get().ownerType);
        assertEquals(tId, SqlViewRuntimeContext.get().templateId);
        // 恢复后回到组件上下文
        SqlViewRuntimeContext.restore(prev);
        assertEquals(OwnerType.COMPONENT, SqlViewRuntimeContext.get().ownerType);
        assertEquals(cid, SqlViewRuntimeContext.get().componentId);
    }

    // ─────────────────── 向后兼容别名（setNestedCostingTemplate）────────────

    @SuppressWarnings("deprecation")
    @Test
    void setNestedCostingTemplate_deprecated_alias_works_as_template() {
        UUID tId = UUID.randomUUID();
        Snapshot prev = SqlViewRuntimeContext.setNestedCostingTemplate(tId, null, "DRAFT");
        Snapshot s = SqlViewRuntimeContext.get();
        // 向后兼容别名：调 setNestedCostingTemplate 应等同于 setNestedTemplate
        assertEquals(OwnerType.TEMPLATE, s.ownerType,
                "向后兼容别名 setNestedCostingTemplate 应路由到 TEMPLATE ownerType");
        assertEquals(tId, s.templateId);
        SqlViewRuntimeContext.restore(prev);
    }

    // ─────────────────── setNested + restore ─────────────────────────────────

    @Test
    void setNested_and_restore_component_context() {
        UUID cid1 = UUID.randomUUID();
        UUID cid2 = UUID.randomUUID();
        SqlViewRuntimeContext.set(cid1, null, null, null);
        Snapshot prev = SqlViewRuntimeContext.setNested(cid2, null, null, null);
        assertEquals(cid2, SqlViewRuntimeContext.get().componentId);
        SqlViewRuntimeContext.restore(prev);
        assertEquals(cid1, SqlViewRuntimeContext.get().componentId);
    }

    @Test
    void restore_null_clears_context() {
        UUID cid = UUID.randomUUID();
        SqlViewRuntimeContext.set(cid, null, null, null);
        SqlViewRuntimeContext.restore(null);
        assertNull(SqlViewRuntimeContext.get().ownerType);
        assertNull(SqlViewRuntimeContext.get().componentId);
    }

    // ─────────────────── isQuotationFrozen() 状态机 ──────────────────────────

    @Test
    void isQuotationFrozen_returns_true_for_approved() {
        UUID tId = UUID.randomUUID();
        Snapshot s = new Snapshot(OwnerType.TEMPLATE, null, tId, UUID.randomUUID(), "APPROVED");
        assertTrue(s.isQuotationFrozen());
    }

    @Test
    void isQuotationFrozen_returns_false_for_draft() {
        UUID cid = UUID.randomUUID();
        Snapshot s = new Snapshot(OwnerType.COMPONENT, cid, null, null, "DRAFT");
        assertFalse(s.isQuotationFrozen());
    }

    @Test
    void isQuotationFrozen_returns_false_for_null_status() {
        assertFalse(Snapshot.EMPTY.isQuotationFrozen());
    }
}
