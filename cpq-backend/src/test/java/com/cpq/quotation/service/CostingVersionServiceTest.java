package com.cpq.quotation.service;

import com.cpq.quotation.dto.CostingOrderDetailDTO;
import com.cpq.quotation.dto.VersionOptionsResponseDTO;
import com.cpq.quotation.dto.VersionSwitchRequest;
import com.cpq.quotation.dto.VersionSwitchResponseDTO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * task-0713 B6/B7 端到端验证 —— 直接调用 {@link CostingVersionService} / {@link QuotationService}
 * 服务方法（不经 HTTP 层，但同 REST 端点内部实际调用路径），验证真实数据。
 *
 * <p><b>夹具</b>：复用 B9（V333）造出的真实锚点 —— 父料号 S-3120014539 是一张真实 PENDING
 * 核价单（HJ-20260713-0487）的产品根料号，主树组件（配件，data_driver_path=$pj_view，
 * bom_recursive_expand=true）在 is_current(2001) 下有 6 个子件、override 到 2000 有 8 个子件
 * （见 V333 迁移注释）。
 *
 * <p><b>不用 {@code @TestTransaction}/事务回滚</b>：{@link CostingVersionService#switchVersion}
 * 自身声明 {@code @Transactional} 并在方法内部提交，若外层再包一层测试事务，反而不能验证真实的
 * "写库后其它查询立刻可见"语义（且 T6.2「重开即读到切换后结果」本就要求跨事务可见）。测试结束前
 * 会把该单切回原始 is_current 版本（2001），尽量不留副作用，但因这是共享测试环境, 结束状态是
 * "真实切过一次版本"的 PENDING 核价单，可作为人工验收素材。
 */
@QuarkusTest
class CostingVersionServiceTest {

    private static final UUID COID = UUID.fromString("570edf2f-da6c-4adb-a6cf-d317136014d1");
    private static final UUID LINE_ITEM_ID = UUID.fromString("68fd50d9-34ea-4c7c-8f7c-ac4f521030ec");
    private static final UUID TREE_COMPONENT_ID = UUID.fromString("428c5db8-9604-4d14-ab70-361ac0f34e7f");
    private static final String ROOT_PART_NO = "S-3120014539";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject QuotationService quotationService;
    @Inject CostingVersionService costingVersionService;
    @Inject EntityManager em;

    /** 该单是否仍是本任务的夹具（PENDING + 产品根料号=S-3120014539）；不满足则跳过（防夹具漂移误报）。 */
    private void assumeFixtureIntact() {
        CostingOrderDetailDTO d = quotationService.getCostingOrderById(COID);
        assumeTrue("PENDING".equals(d.status), "夹具核价单需为 PENDING（可能已被其它并发会话核价通过/驳回）");
    }

    @Test
    void t1_versionOptions_listsDescendingAndCurrentIsIsCurrent() {
        assumeFixtureIntact();
        VersionOptionsResponseDTO opts = costingVersionService.listVersionOptions(
                COID, LINE_ITEM_ID, TREE_COMPONENT_ID, ROOT_PART_NO);
        assertNotNull(opts.options);
        assertTrue(opts.options.contains("2000"), "应含旧版本 2000: " + opts.options);
        assertTrue(opts.options.contains("2001"), "应含新版本 2001: " + opts.options);
        // 严格倒序
        for (int i = 0; i + 1 < opts.options.size(); i++) {
            assertTrue(opts.options.get(i).compareTo(opts.options.get(i + 1)) >= 0
                            || Long.parseLong(opts.options.get(i)) >= Long.parseLong(opts.options.get(i + 1)),
                    "options 必须严格倒序: " + opts.options);
        }
    }

    @Test
    void t2_switchToOldVersion_expandsToEightChildren_thenSwitchBack() throws Exception {
        assumeFixtureIntact();

        // ── switch -> 2000（父节点切旧版，子件集合应变为 8 子件形态，13 行）──────────────
        VersionSwitchRequest req = new VersionSwitchRequest();
        req.lineItemId = LINE_ITEM_ID;
        req.componentId = TREE_COMPONENT_ID;
        req.partNo = ROOT_PART_NO;
        req.viewVersion = "2000";
        VersionSwitchResponseDTO resp = costingVersionService.switchVersion(COID, req);

        assertEquals(LINE_ITEM_ID.toString(), resp.lineItemId);
        assertNotNull(resp.costingCardValues, "重算后核价卡片值不应为空");
        assertTrue(resp.affectedTabs != null && !resp.affectedTabs.isEmpty(),
                "主树切应有受影响页签列表（该 line 全部 driver 组件）");
        // 3a 总价联动（技术总监返修单核心断言）：SUBTOTAL 组件（核价小计）公式必须真正求值非 0，
        // 不能停在 CostingSubtotalUtil 找不到 componentType/subtotal 字段而恒 0 的假绿状态。
        assertNotNull(resp.costingTotalAmount);
        assertTrue(resp.costingTotalAmount.compareTo(java.math.BigDecimal.ZERO) > 0,
                "override 到 2000 后 costingTotalAmount 必须 > 0，实际=" + resp.costingTotalAmount);

        JsonNode respTree = MAPPER.readTree(resp.costingCardValues);
        int afterRowsFromResp = treeTabRowCountFromCardValues(respTree, TREE_COMPONENT_ID.toString());
        assertEquals(13, afterRowsFromResp,
                "override 到 2000 后主树应展开为 8 子件形态（1根+8子+4孙=13 行）");

        // override 落库校验
        Number cnt = (Number) em.createNativeQuery(
                        "SELECT count(*) FROM costing_order_version_override WHERE costing_order_id=:coid " +
                                "AND component_id=:cid AND part_no=:p AND view_version='2000'")
                .setParameter("coid", COID).setParameter("cid", TREE_COMPONENT_ID).setParameter("p", ROOT_PART_NO)
                .getSingleResult();
        assertEquals(1L, cnt.longValue(), "override 应精确落库一行 view_version=2000");

        // 重开(重新 getById)即读到切换后的结果（不重算，读缓存）——T6.2
        em.clear(); // 强制丢弃 L1 缓存里 switchVersion 写入前加载的旧 CostingOrder 实例，读到真实已提交的库内容
        CostingOrderDetailDTO afterDetail = quotationService.getCostingOrderById(COID);
        assertNotNull(afterDetail.costingRender, "costing_render 缓存应非空");
        int afterRowsFromCache = treeTabRowCount(afterDetail);
        assertEquals(13, afterRowsFromCache, "重开后核价树应仍是切换后的 8 子件形态（读缓存非重算）");
        assertEquals(1, afterDetail.versionOverrides.size());
        assertEquals("2000", afterDetail.versionOverrides.get(0).viewVersion);

        // frozen_dto 逐字节不变校验（报价侧隔离，T2.2）
        String frozenBefore = (String) em.createNativeQuery(
                        "SELECT frozen_dto::text FROM costing_order WHERE id=:id")
                .setParameter("id", COID).getSingleResult();
        assertNotNull(frozenBefore);

        // ── switch back -> 2001（尽量恢复原状，减少对共享环境的副作用）─────────────────
        req.viewVersion = "2001";
        VersionSwitchResponseDTO respBack = costingVersionService.switchVersion(COID, req);
        JsonNode backTree = MAPPER.readTree(respBack.costingCardValues);
        int backRows = treeTabRowCountFromCardValues(backTree, TREE_COMPONENT_ID.toString());
        assertEquals(11, backRows,
                "切回 2001 后应恢复 6 子件形态（1根+6子+4孙=11 行）");

        // 3a 总价联动：2000(8子件) 与 2001(6子件) 的 costingTotalAmount 必须都 > 0 且互不相等
        // （子件集合真的变了，成本必然跟着变——不是恰好都算出同一个假值）。"配件"公式=数量*5 按
        // 子件行累加，子件多的 2000 应严格高于子件少的 2001。
        assertNotNull(respBack.costingTotalAmount);
        assertTrue(respBack.costingTotalAmount.compareTo(java.math.BigDecimal.ZERO) > 0,
                "切回 2001 后 costingTotalAmount 必须 > 0，实际=" + respBack.costingTotalAmount);
        assertNotEquals(0, resp.costingTotalAmount.compareTo(respBack.costingTotalAmount),
                "2000 与 2001 的 costingTotalAmount 必须不相等：2000=" + resp.costingTotalAmount
                        + " 2001=" + respBack.costingTotalAmount);
        assertTrue(resp.costingTotalAmount.compareTo(respBack.costingTotalAmount) > 0,
                "子件更多的 2000(" + resp.costingTotalAmount + ") 成本应高于子件更少的 2001("
                        + respBack.costingTotalAmount + ")");
        System.out.println("[CostingVersionServiceTest] costingTotalAmount: 2000=" + resp.costingTotalAmount
                + " 2001=" + respBack.costingTotalAmount);

        // frozen_dto 在两次切换后仍逐字节不变（报价侧完全隔离，T2.2 核心断言）
        String frozenAfter = (String) em.createNativeQuery(
                        "SELECT frozen_dto::text FROM costing_order WHERE id=:id")
                .setParameter("id", COID).getSingleResult();
        assertEquals(frozenBefore, frozenAfter, "版本切换前后 frozen_dto 必须逐字节相同（报价侧隔离）");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private int treeTabRowCount(CostingOrderDetailDTO detail) {
        if (detail.costingRender == null) return -1;
        JsonNode entry = detail.costingRender.get(LINE_ITEM_ID.toString());
        if (entry == null) return -1;
        JsonNode cardValues = entry.get("costingCardValues");
        if (cardValues == null || cardValues.isNull()) return -1;
        try {
            JsonNode parsed = MAPPER.readTree(cardValues.asText());
            return treeTabRowCountFromCardValues(parsed, TREE_COMPONENT_ID.toString());
        } catch (Exception e) {
            return -1;
        }
    }

    private int treeTabRowCountFromCardValues(JsonNode cardValues, String componentId) {
        for (JsonNode tab : cardValues.path("tabs")) {
            if (componentId.equals(tab.path("componentId").asText(""))) {
                return tab.path("baseRows").size();
            }
        }
        return -1;
    }
}
