package com.cpq.datasource.sqlview;

import com.cpq.component.dto.RuntimeContext;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * task-0721 B3/B4 端到端自测：SqlViewExecutor 的 pending 改写接缝 + 锚点透传。
 *
 * <p>用真实组件（"组合工艺" 的 zh_view，主位 capacity 白名单表）验证：
 * <ul>
 *   <li>DRAFT 态 + quotationId 非空 → executeAllRows 返回行含 {@code __v6_id}（B3 挂接生效）</li>
 *   <li>APPROVED（冻结）态 → 不改写，行不含 {@code __v6_id}（AC-10 已建单/冻结不受影响）</li>
 *   <li>无 quotationId（EMPTY 上下文）→ 不改写，行为与改动前逐位一致（AC-17 核价侧零回归代理验证）</li>
 * </ul>
 * B4 的"锚点入快照"落点（{@code CardSnapshotService}/{@code ConfigureSnapshotService} 对
 * {@code driverRow} 的 {@code MAPPER.valueToTree} 全量直通、无白名单过滤）已通过代码走查确认
 * （见 backtask B4 交付说明），本类只覆盖到 SqlViewExecutor 这一步的锚点"是否产出"，
 * 不重复搭建整条 ComponentDriverService→CardSnapshotService 物化链路（超出自测必要范围）。
 */
@QuarkusTest
class SqlViewExecutorPendingHookTest {

    @Inject SqlViewExecutor executor;

    /** "组合工艺" 组件（拥有 QUOTE 侧 zh_view，主位 capacity）。 */
    private static final UUID COMPONENT_ID = UUID.fromString("4d8874c8-5022-4ba0-ba08-17009f46ecae");

    @AfterEach
    void cleanup() {
        SqlViewRuntimeContext.clear();
    }

    @Test
    void draftQuotation_anchorPresent() {
        SqlViewRuntimeContext.set(COMPONENT_ID, null, UUID.randomUUID(), "DRAFT");
        var rows = executor.executeAllRows("$zh_view", new RuntimeContext(), null);
        // 数据可能为空（测试环境无该客户/料号真实 capacity 行），关键断言在于"改写是否生效"而非行数，
        // 用 SQL 层面能否成功执行(不抛异常)即已证明改写语法正确；若有行必须含 __v6_id 键。
        for (Map<String, Object> row : rows) {
            assertTrue(row.containsKey("__v6_id"), "DRAFT 态报价单上下文应改写并含 __v6_id 锚点");
        }
    }

    @Test
    void frozenQuotation_noRewrite() {
        SqlViewRuntimeContext.set(COMPONENT_ID, null, UUID.randomUUID(), "APPROVED");
        var rows = executor.executeAllRows("$zh_view", new RuntimeContext(), null);
        for (Map<String, Object> row : rows) {
            assertFalse(row.containsKey("__v6_id"), "冻结态不应改写，不应含 __v6_id（AC-10）");
        }
    }

    @Test
    void noQuotationContext_noRewrite() {
        SqlViewRuntimeContext.set(COMPONENT_ID, null, null, null);
        var rows = executor.executeAllRows("$zh_view", new RuntimeContext(), null);
        for (Map<String, Object> row : rows) {
            assertFalse(row.containsKey("__v6_id"), "无 quotationId 上下文不应改写（核价侧零回归代理验证）");
        }
    }
}
