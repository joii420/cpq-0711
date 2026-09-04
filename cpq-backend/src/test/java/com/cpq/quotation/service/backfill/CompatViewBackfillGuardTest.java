package com.cpq.quotation.service.backfill;

import com.cpq.datasource.sqlview.QuotePendingRewriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * task-260903 · B-3 守卫测试（主线裁决「方案乙」实施要求②）。
 *
 * <p><b>守的是什么</b>：读路径走兼容视图、写路径归一化回物理表。这条分层一旦断，
 * 故障是<b>完全静默</b>的 —— {@code QuoteBackfillCollector:172} 的
 * {@code QuoteTableAxis.of(primaryTable) == null → continue} 是刻意的安全降级，
 * 未登记的表名只会让该页签「不回填」，不抛异常、不打日志、启动期硬校验也判「不适用」。
 *
 * <p>所以后人往 {@link QuotePendingRewriter#WHITELIST_TABLES} 里加了新的兼容视图、
 * 却忘了同步加 {@link QuotePendingRewriter#COMPAT_VIEW_TO_TABLE} 映射时，
 * <b>只有这个测试会红</b>。🚫 不要因为「它只是断言几个字符串」就删掉或 @Disabled 它。
 *
 * <p>纯静态断言，不连库、不起 Quarkus。
 */
@DisplayName("task-260903 B-3 · 兼容视图 → 物理表 归一化守卫")
class CompatViewBackfillGuardTest {

    private static final String COMPAT_PREFIX = "v_compat_";

    @Test
    @DisplayName("白名单里每个兼容视图都有物理表映射，且映射目标在 QuoteTableAxis 里登记过")
    void everyCompatViewMapsToARegisteredPhysicalTable() {
        List<String> compatViews = new ArrayList<>();
        for (String t : QuotePendingRewriter.WHITELIST_TABLES) {
            if (t.startsWith(COMPAT_PREFIX)) compatViews.add(t);
        }
        // 防「白名单里一个兼容视图都没有」时本测试空转成假绿
        assertFalse(compatViews.isEmpty(),
            "WHITELIST_TABLES 里一个 " + COMPAT_PREFIX + "* 都没有 —— "
          + "要么 V411 的表名替换被回退了，要么白名单被改坏了，两种都要人来看");

        for (String view : compatViews) {
            String physical = QuotePendingRewriter.physicalTable(view);
            assertNotEquals_(view, physical,
                "兼容视图 " + view + " 没有登记进 COMPAT_VIEW_TO_TABLE —— "
              + "回填会把它当物理表去 UPDATE，而 UNION 视图不可更新");
            assertNotNull(QuoteTableAxis.of(physical),
                "兼容视图 " + view + " 映射到的 " + physical + " 没在 QuoteTableAxis 登记 —— "
              + "QuoteBackfillCollector:172 会静默 continue，该页签从此不回填");
        }
    }

    @Test
    @DisplayName("映射表的每个 value 都是 QuoteTableAxis 认识的物理表，key 都在白名单里")
    void mappingTableIsSelfConsistent() {
        for (Map.Entry<String, String> e : QuotePendingRewriter.COMPAT_VIEW_TO_TABLE.entrySet()) {
            assertTrue(e.getKey().startsWith(COMPAT_PREFIX),
                "COMPAT_VIEW_TO_TABLE 的 key 应是兼容视图名: " + e.getKey());
            assertTrue(QuotePendingRewriter.WHITELIST_TABLES.contains(e.getKey()),
                e.getKey() + " 有映射却不在白名单里 —— rewriter 根本不会命中它，映射是死代码");
            assertTrue(QuotePendingRewriter.WHITELIST_TABLES.contains(e.getValue()),
                "映射目标 " + e.getValue() + " 必须仍在白名单里（存量未改造的模板还在直接引用物理表）");
            assertNotNull(QuoteTableAxis.of(e.getValue()),
                "映射目标 " + e.getValue() + " 未在 QuoteTableAxis 登记");
        }
    }

    @Test
    @DisplayName("非兼容视图名原样返回，null 安全")
    void physicalTableIsIdentityForEverythingElse() {
        assertEquals("material_bom_item", QuotePendingRewriter.physicalTable("material_bom_item"));
        assertEquals("unit_price", QuotePendingRewriter.physicalTable("unit_price"));
        assertEquals("material_customer_map", QuotePendingRewriter.physicalTable("material_customer_map"));
        assertNull(QuotePendingRewriter.physicalTable(null));
    }

    @Test
    @DisplayName("v_compat_material_master 刻意不在白名单 —— 它没有 is_current 列，替换子查询会 SQL 报错")
    void materialMasterCompatViewStaysOutOfWhitelist() {
        assertFalse(QuotePendingRewriter.WHITELIST_TABLES.contains("v_compat_material_master"),
            "material_master 历来不在白名单（无 is_current 列，buildReplacementSubquery 会拼出非法 SQL），"
          + "其兼容视图同样不能进 —— 进了就是把渲染主链路的 128 段视图一起拖挂");
        assertFalse(QuotePendingRewriter.WHITELIST_TABLES.contains("material_master"),
            "基线保护：material_master 本来就不在白名单，本次改动不许把它带进来");
    }

    private static void assertNotEquals_(String unexpected, String actual, String msg) {
        assertFalse(unexpected.equals(actual), msg);
    }
}
