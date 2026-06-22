package com.cpq.configure.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Part B 跳过判定纯函数单测：不依赖 DB / Quarkus 容器。 */
class SnapshotLineNeedsExpandTest {

    private final UUID c1 = UUID.randomUUID();
    private final UUID c2 = UUID.randomUUID();

    @Test
    void allCompsHaveSnapshot_skips() {
        // 所有 driver 组件都有非 null snapshot_rows（含合法空数组 "[]"）→ 不需 expand（可跳过）
        boolean needs = ConfigureSnapshotService.lineNeedsExpand(
                List.of(c1, c2),
                Map.of(c1, "[{\"driverRow\":{}}]", c2, "[]"));
        assertFalse(needs, "所有组件已有 snapshot_rows（含空数组）应跳过");
    }

    @Test
    void missingCompSnapshot_needsExpand() {
        // c2 无 snapshot_rows（重建后新行 = null / 缺行）→ 需要 expand
        boolean needs = ConfigureSnapshotService.lineNeedsExpand(
                List.of(c1, c2),
                Map.of(c1, "[{\"driverRow\":{}}]"));
        assertTrue(needs, "缺任一组件 snapshot_rows 应整行重 expand");
    }

    @Test
    void nullSnapshotValue_needsExpand() {
        // 显式 null 值（列存在但为 null）→ 需要 expand
        java.util.HashMap<UUID, String> m = new java.util.HashMap<>();
        m.put(c1, "[]");
        m.put(c2, null);
        boolean needs = ConfigureSnapshotService.lineNeedsExpand(List.of(c1, c2), m);
        assertTrue(needs, "snapshot_rows 为 null 应重 expand");
    }

    @Test
    void noDriverComps_skips() {
        // 无 driver 组件 → 无可展开内容 → 不需 expand
        boolean needs = ConfigureSnapshotService.lineNeedsExpand(List.of(), Map.of());
        assertFalse(needs);
    }
}
