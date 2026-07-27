package com.cpq.quotation.service.backfill;

import com.cpq.component.entity.ComponentSqlView;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * task-0721 报价数据版本升级 · B5 —— {@link QuoteBackfillColumnMapper} 真实模板自测。
 *
 * <p>复用 {@code SqlViewExecutorPendingHookTest} 已验证过的真实组件"组合工艺"（主位 capacity 白名单表，
 * {@code zh_view}），验证：改写后能解析出 {@code colToBase}（至少含若干映射到 capacity 物理列的输出别名），
 * 且 {@code primaryTable=capacity}、{@code backfillable=true}。
 */
@QuarkusTest
class QuoteBackfillColumnMapperTest {

    @Inject DataSource dataSource;

    private static final UUID COMPONENT_ID = UUID.fromString("4d8874c8-5022-4ba0-ba08-17009f46ecae");

    @Test
    void resolvesCapacityColumnsForZhView() throws Exception {
        ComponentSqlView view = ComponentSqlView.find(
            "componentId = ?1 and sqlViewName = ?2", COMPONENT_ID, "zh_view").firstResult();
        assertNotNull(view, "需要真实组件'组合工艺'的 zh_view 存在（与 SqlViewExecutorPendingHookTest 共用夹具）");

        try (Connection conn = dataSource.getConnection()) {
            QuoteBackfillColumnMapper.Resolved resolved =
                QuoteBackfillColumnMapper.resolve(view.sqlTemplate, conn);
            assertTrue(resolved.backfillable, "capacity 主位视图应判定可回填");
            assertEquals("capacity", resolved.primaryTable);
            assertFalse(resolved.colToBase.isEmpty(), "应至少解析出一个可回写列映射");
            boolean anyCapacity = resolved.colToBase.values().stream()
                .anyMatch(ref -> "capacity".equals(ref.table()));
            assertTrue(anyCapacity, "至少一个输出列应映射回 capacity 表物理列");
        }
    }

    @Test
    void secondCallHitsCache() throws Exception {
        ComponentSqlView view = ComponentSqlView.find(
            "componentId = ?1 and sqlViewName = ?2", COMPONENT_ID, "zh_view").firstResult();
        assertNotNull(view);
        try (Connection conn = dataSource.getConnection()) {
            QuoteBackfillColumnMapper.Resolved r1 = QuoteBackfillColumnMapper.resolve(view.sqlTemplate, conn);
            QuoteBackfillColumnMapper.Resolved r2 = QuoteBackfillColumnMapper.resolve(view.sqlTemplate, conn);
            assertSame(r1, r2, "同一 sqlTemplate 二次解析应命中进程级缓存（同一对象引用）");
        }
    }

    @Test
    void blankTemplateIsNotBackfillable() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            QuoteBackfillColumnMapper.Resolved resolved = QuoteBackfillColumnMapper.resolve("", conn);
            assertFalse(resolved.backfillable);
            assertTrue(resolved.colToBase.isEmpty());
        }
    }

    /**
     * repair-0727 B1/B2（修 D3 同族问题）：真实 {@code cp_view}（"产品"页签）模板——顶层
     * {@code UNION ALL}，边行 {@code material_bom_item} + 根行 {@code material_master}（与需求说明
     * §1.3 D3 的 {@code $bom_view} 同一形状），应可回填。
     *
     * <p>B1 之前该模板 {@code anchorInjected=false}（顶层 set-op 整体安全降级，实测复现于
     * {@code QuoteViewValidationService} 启动校验："component=产品 view=cp_view"），B2 的列映射也
     * 就永远拿不到 {@code primaryTable}——这正是 D3 那一族缺陷的根因（树/平铺页签渲染出来的行没有
     * {@code __v6_id}，在回填眼里等于不存在）。本用例验证 B1 逐分支注入 + B2 单分支探测组合后，对
     * 真实生产模板端到端可回填（用 {@code cp_view} 而非 {@code bom_view}——本环境测试库 {@code cpq_db}
     * 与开发库 {@code cpq_db_0724} 是两个物理数据库，seed 数据不完全一致，{@code cp_view} 是
     * {@code cpq_db} 里确实存在的同构真实模板）。
     */
    @Test
    void resolvesCpViewSetOpBranchColumns() throws Exception {
        // "cp_view" 在共享 DB 里有多个不同客户模板的同名副本（不同 sql_template，长度从 308~972 不等），
        // 不能像 zh_view 一样直接按名字 firstResult()（顺序不确定，可能选到非 set-op 的简化副本）——
        // 显式取最长(最完整)的那份真实 UNION ALL 模板，与本类其它用例锁 COMPONENT_ID 常量同一防坑思路。
        ComponentSqlView view = ComponentSqlView.<ComponentSqlView>find(
                "sqlViewName = ?1 and status = 'ACTIVE'", "cp_view")
            .list().stream()
            .max(java.util.Comparator.comparingInt(v -> v.sqlTemplate.length()))
            .orElse(null);
        assertNotNull(view, "共享 DB 应存在至少一个真实 cp_view 组件模板（产品页签）");

        try (Connection conn = dataSource.getConnection()) {
            QuoteBackfillColumnMapper.Resolved resolved = QuoteBackfillColumnMapper.resolve(view.sqlTemplate, conn);
            assertTrue(resolved.backfillable, "cp_view(顶层 UNION ALL) 应判定可回填（repair-0727 B1 修复前恒为 false）");
            assertEquals("material_bom_item", resolved.primaryTable, "主位应是边行分支的 material_bom_item");
            assertFalse(resolved.colToBase.isEmpty());
            boolean anyBomItem = resolved.colToBase.values().stream()
                .anyMatch(ref -> "material_bom_item".equals(ref.table()));
            assertTrue(anyBomItem, "至少一个输出列应映射回 material_bom_item 表物理列");
            var partNoRef = resolved.colToBase.get("_销售料号");
            assertNotNull(partNoRef, "_销售料号 应映射回边行分支的物理列");
            assertEquals("material_bom_item", partNoRef.table());
            assertEquals("component_no", partNoRef.column());
        }
    }
}
