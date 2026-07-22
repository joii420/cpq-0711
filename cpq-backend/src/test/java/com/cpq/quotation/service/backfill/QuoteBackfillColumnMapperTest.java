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
}
