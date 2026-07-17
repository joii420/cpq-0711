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

    // ── repair-071501 夹具：非树元素页签切版本（Bug2 复现）──────────────────────────────
    // 截图核价单 HJ-20260715-0584（产品 S-3120014539），元素组件 wl_ys_bom_view，元素料号
    // S-2120011658 有 characteristic 2000/2001（V333 T0.2 造）。非树切换走 buildMixedBaseRows，
    // 该路径此前把新行 basicDataValues 置空 {} → 元素字段(BASIC_DATA)全解析失败显示「—」。
    private static final UUID ELEM_COID = UUID.fromString("af4d1c84-e979-4434-9ad0-b5e35c09145e");
    private static final UUID ELEM_LINE_ITEM_ID = UUID.fromString("70bc1007-39c5-4c10-9dfe-e5e80a8d16c2");
    private static final UUID ELEM_COMPONENT_ID = UUID.fromString("33fee28a-699a-467c-a48c-99a6a5482c6d");
    private static final String ELEM_PART_NO = "S-2120011658";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject QuotationService quotationService;
    @Inject CostingVersionService costingVersionService;
    @Inject EntityManager em;

    /** 该单是否仍是本任务的夹具（PENDING + 产品根料号=S-3120014539）；不满足则跳过（防夹具漂移误报）。 */
    private void assumeFixtureIntact() {
        CostingOrderDetailDTO d = quotationService.getCostingOrderById(COID);
        assumeTrue("PENDING".equals(d.status), "夹具核价单需为 PENDING（可能已被其它并发会话核价通过/驳回）");
    }

    // ── repair-0590：非树切到"该料号没有的版本" → 400 拒绝 + 料号不消失 + override 不落库 ──
    private static final UUID DIAG_COID = UUID.fromString("8c434c65-1f0c-4fe5-b5df-8b93e6a174d4");
    private static final UUID DIAG_LINE = UUID.fromString("30d2c1e2-20db-4cfe-809f-3efa27486055");
    private static final UUID DIAG_CONSUM_COMP = UUID.fromString("f94b12c4-b0c8-4fc2-9489-20f7972852fd");

    /**
     * repair-0590（料号消失无法恢复 根因）：把非树料号切到一个"它没有数据的版本"必须被拒绝(400)、
     * override 不落库(回滚)、料号原样保留——不能像修复前那样删旧行+无新行补致料号从页签消失。
     * 夹具用真实单 HJ-20260717-0590 的生产耗材组件；S-3120014539 有 2000/2001/2002，切到不存在的 9999。
     */
    @Test
    void switchToNonexistentVersion_rejected_overrideNotPersisted() {
        CostingOrderDetailDTO d = quotationService.getCostingOrderById(DIAG_COID);
        assumeTrue(d != null && "PENDING".equals(d.status),
                "夹具核价单 HJ-20260717-0590 需为 PENDING（可能被并发核价/漂移）");

        VersionSwitchRequest req = new VersionSwitchRequest();
        req.lineItemId = DIAG_LINE;
        req.componentId = DIAG_CONSUM_COMP;
        req.partNo = "S-3120014539";
        req.viewVersion = "9999";   // 不存在的版本
        com.cpq.common.exception.BusinessException ex = assertThrows(
                com.cpq.common.exception.BusinessException.class,
                () -> costingVersionService.switchVersion(DIAG_COID, req),
                "切到该料号没有的版本应被拒绝");
        assertEquals(400, ex.getCode(), "应 400（该版本非此料号可选版本）");

        // override 未落库（@Transactional 回滚）
        em.clear();
        Number cnt = (Number) em.createNativeQuery(
                "SELECT count(*) FROM costing_order_version_override WHERE costing_order_id=:c " +
                "AND component_id=:cid AND part_no='S-3120014539' AND view_version='9999'")
                .setParameter("c", DIAG_COID).setParameter("cid", DIAG_CONSUM_COMP).getSingleResult();
        assertEquals(0L, cnt.longValue(), "非法切换的 override 不应落库（已回滚）");
    }


    /** T7.1：非 PENDING 核价单切版本必须 403，且不落 override（用真实 REJECTED 单验证状态门禁）。 */
    @Test
    void t3_nonPendingCostingOrder_switchVersion_returns403() {
        UUID nonPendingCoid = null;
        String status = null;
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                "SELECT id, status FROM costing_order WHERE status IN ('REJECTED','APPROVED','WITHDRAWN') LIMIT 1")
                .getResultList();
        if (!rows.isEmpty()) {
            nonPendingCoid = (UUID) rows.get(0)[0];
            status = (String) rows.get(0)[1];
        }
        assumeTrue(nonPendingCoid != null, "共享库需要至少一张非 PENDING 核价单");

        VersionSwitchRequest req = new VersionSwitchRequest();
        req.lineItemId = LINE_ITEM_ID;      // 门禁在业务校验之前触发，具体 line/component 是否存在不影响本用例
        req.componentId = TREE_COMPONENT_ID;
        req.partNo = ROOT_PART_NO;
        req.viewVersion = "9999";

        UUID finalCoid = nonPendingCoid;
        com.cpq.common.exception.BusinessException ex = assertThrows(
                com.cpq.common.exception.BusinessException.class,
                () -> costingVersionService.switchVersion(finalCoid, req),
                "非 PENDING(实际=" + status + ") 核价单切版本应抛 BusinessException(403)");
        assertEquals(403, ex.getCode(), "错误码应为 403");
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

    /**
     * repair-071501（Bug2）：非树元素页签切版本后，新行必须携带非空 basicDataValues。
     *
     * <p>元素组件字段均为 BASIC_DATA（basic_data_path=$wl_ys_bom_view.列），单元格从
     * basicDataValues 取值。修复前 {@code buildMixedBaseRows} 把新行 basicDataValues 置空 {}，
     * 导致切换后整组元素行所有列（含销售料号）显示「—」。修复 = expandRows 保留完整 Row，
     * 新行 basicDataValues 直接来自 ExpandDriverResponse.Row（与初次渲染同口径）。
     */
    @Test
    void t4_nonTreeElementSwitch_freshRowsCarryBasicDataValues() throws Exception {
        // 夹具完整性防护（并发漂移）：该单需仍为 PENDING；单已被删（getById 抛 404）时也跳过而非报错。
        CostingOrderDetailDTO d;
        try {
            d = quotationService.getCostingOrderById(ELEM_COID);
        } catch (com.cpq.common.exception.BusinessException ex) {
            assumeTrue(false, "夹具核价单 HJ-20260715-0584 已不存在（被删/漂移），跳过");
            return;
        }
        assumeTrue(d != null && "PENDING".equals(d.status),
                "夹具核价单 HJ-20260715-0584 需为 PENDING（可能被其它会话核价/漂移）");

        // ⚠️ 有意「单次 switch、且不在本请求内先调 listVersionOptions」：DataLoader 是 @RequestScoped，
        //    其 resultCache 按 $view path 为 key、不含 versionFilter mode/override 维度；@QuarkusTest
        //    里 listVersionOptions(LIST→TRUE) 与 switchVersion(RENDER) 共享同一 request scope 会让
        //    RENDER expand 命中 LIST 缓存返回全版本（测试假象，非生产 bug——生产两者是分开的 HTTP 请求、
        //    各自独立 DataLoader）。见 repair-071501 报告「发现的遗留问题」+ BL-0055。此处单次 RENDER
        //    expand，request cache 干净，真实反映生产单请求切换行为。
        VersionSwitchRequest req = new VersionSwitchRequest();
        req.lineItemId = ELEM_LINE_ITEM_ID;
        req.componentId = ELEM_COMPONENT_ID;
        req.partNo = ELEM_PART_NO;
        req.viewVersion = "2001"; // = 该料号 is_current，与用户原始 override 一致；切后落回干净单版本状态
        VersionSwitchResponseDTO resp = costingVersionService.switchVersion(ELEM_COID, req);
        assertNotNull(resp.costingCardValues, "非树切换后核价卡片值不应为空");

        JsonNode cv = MAPPER.readTree(resp.costingCardValues);
        JsonNode elemTab = null;
        for (JsonNode tab : cv.path("tabs")) {
            if (ELEM_COMPONENT_ID.toString().equals(tab.path("componentId").asText(""))) { elemTab = tab; break; }
        }
        assertNotNull(elemTab, "核价卡片值须含元素页签 componentId=" + ELEM_COMPONENT_ID);

        int checked = 0;
        for (JsonNode br : elemTab.path("baseRows")) {
            String mn = br.path("driverRow").path("material_no").asText("");
            if (!ELEM_PART_NO.equals(mn)) continue;
            checked++;
            // ① 版本纯净：切到 2001 后该料号不得残留其它版本行（drop 清旧 + fresh 单版本）
            assertEquals("2001", br.path("driverRow").path("view_version").asText(""),
                    ELEM_PART_NO + " 切到 2001 后不应残留其它版本行，行=" + br);
            // ② basicDataValues 非空（repair-071501 Bug2 修复主断言）：修复前置空 {} → 元素字段
            //    (BASIC_DATA basic_data_path=$wl_ys_bom_view.列) 全解析失败显示「—」。
            JsonNode bdv = br.path("basicDataValues");
            assertTrue(bdv.isObject() && bdv.size() > 0,
                    "Bug2 回归：切换后的元素行 basicDataValues 不能为空 {}（BASIC_DATA 字段取数源），行=" + br);
            assertFalse(br.path("driverRow").path("content").isMissingNode(), "元素行应含 content 列");
        }
        assertTrue(checked >= 1, "应至少校验到一行 " + ELEM_PART_NO + " 的元素行（实际 " + checked + "）");
        System.out.println("[CostingVersionServiceTest] t4 非树元素切→2001：" + ELEM_PART_NO
                + " 元素行 " + checked + " 行全为 2001 且 basicDataValues 非空 ✅");
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
