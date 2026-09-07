package com.cpq.semanticgraph;

import com.cpq.common.exception.BusinessException;
import com.cpq.component.service.ComponentService;
import com.cpq.semanticgraph.dto.SemanticGraphDTOs.TabViewUpsertRequest;
import com.cpq.semanticgraph.service.SemanticGraphService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.TreeSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * task-260819 · B-59：{@code semantic_tab_view.tab_type} 的<b>写入边界</b>值域闸。
 *
 * <h3>堵的是什么洞</h3>
 * {@link com.cpq.semanticgraph.service.SemanticGraphKeyValueSelfCheck}（B-56）在<b>启动期</b>校验
 * {@code tab_type ⊆ ComponentService.VALID_TAB_TYPES}，越域即 {@code IllegalStateException}——
 * <b>服务起不来</b>。而 {@code POST /api/cpq/config/semantic-graph/tab-views} 此前对 {@code tabType}
 * 零校验：手滑填个显示名「BOM 树」（D-39：那只是前端 label，存储值是「BOM」）当场 200 落库，
 * <b>下一个重启的人</b>才炸，且现场离肇事点极远——与 D-114 同型的<b>延迟引爆</b>。
 *
 * <h3>🚫 为什么不是放宽 B-56</h3>
 * 放宽启动期自检 = 把脏数据放进语义图，让编译期/渲染期去踩；洞没堵，只是挪了个爆点。
 * 正确方向是<b>在写入口当场 400</b>（用户 2026-09-06 裁决 (a)）。
 *
 * <h3>为什么落在 service 层而不是打 HTTP 端点</h3>
 * 洞本身在 service（{@code tv.tabType = req.tabType} 那一行），{@code SemanticGraphResource}
 * 是纯透传（{@code service.createTabView(req, currentOperator())} 一行）。而「400 而不是 500」这半句
 * 由 {@code GlobalExceptionMapper#handleBusinessException} 的 {@code Response.status(e.getCode())}
 * 保证 —— 所以断言 {@code BusinessException.getCode()==400} 与打端点得 400 是等价的，
 * 却省掉一整套登录态夹具。<b>代价</b>：不覆盖 {@code @RoleAllowed} 与 JSON 反序列化，本类不声称覆盖。
 */
@QuarkusTest
class TabViewTabTypeWriteGuardTest {

    /** 典型手滑值：D-39 明示「BOM 树」是显示名，存储值应为「BOM」。 */
    private static final String DISPLAY_NAME_NOT_STORAGE = "BOM 树";
    /** 正向用例的临时 variant_key —— 与唯一键 (tab_type, variant_key, dialect) 的现网取值不冲突。 */
    private static final String TMP_VARIANT = "B59_SELFCHECK_TMP";

    @Inject SemanticGraphService service;
    @Inject DataSource dataSource;

    /**
     * 🚨 <b>兜底自清，别删</b>。反向用例断言的是「一行都不该落库」——它<b>失败</b>时的含义恰恰是
     * 「守卫没生效，脏行已经进了共享开发库」。而 {@code assertThrows} 失败即抛，后面的清理跑不到。
     *
     * <p>实测代价（2026-09-06 本类的证伪实验）：把守卫注释掉重跑，
     * {@code tab_type='BOM 树'} 真的落进了 {@code cpq_db_0724}，
     * <b>下一个重启的人会被 B-56 拦在启动期</b> —— 正是本任务要堵的那颗雷，
     * 被验证它的实验自己种了一颗。{@code application-test.properties} 默认库就是共享开发库
     * （CLAUDE.md 明示），所以<b>证伪实验必须可反复做而不留残留</b>。
     *
     * <p>只按本类的哨兵 {@code variant_key} 删，且逐行按主键 —— 永不触碰现网 45 行种子数据。
     */
    @org.junit.jupiter.api.AfterEach
    void purgeOwnRows() {
        for (UUID id : idsByVariant(TMP_VARIANT)) {
            deleteById(id);
            System.out.println("  [自清] 删除本类残留行 id=" + id + "（按主键，命中面 1 行）");
        }
    }

    // ---------------- 反向：非法值当场 400，且**一行都不许落库** ----------------

    @Test
    void displayNameIsRejectedWith400AndPersistsNothing() {
        long before = countByTabType(DISPLAY_NAME_NOT_STORAGE);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createTabView(reqWithTabType(DISPLAY_NAME_NOT_STORAGE), "b59-test"));

        System.out.println("---- B-59 反向（create，显示名）----");
        System.out.println("  code    = " + ex.getCode());
        System.out.println("  message = " + ex.getMessage());
        System.out.println("  落库行数 before=" + before + " after=" + countByTabType(DISPLAY_NAME_NOT_STORAGE));

        assertEquals(400, ex.getCode(), "非法 tabType 必须是 400（客户端错），不是 500");
        // B-59b：错误信息要点名**收到的值**与**合法值域**，否则用户不知道该改成什么
        assertTrue(ex.getMessage().contains(DISPLAY_NAME_NOT_STORAGE),
                "错误信息必须点名收到的值，实际：" + ex.getMessage());
        for (String legal : ComponentService.VALID_TAB_TYPES) {
            assertTrue(ex.getMessage().contains(legal),
                    "错误信息必须列出合法值域（缺「" + legal + "」），实际：" + ex.getMessage());
        }
        // 🚨 核心断言：被拒 ≠ 没落库。守卫必须在 persist **之前**，否则 B-56 照炸不误。
        assertEquals(before, countByTabType(DISPLAY_NAME_NOT_STORAGE),
                "非法 tabType 被拒后仍有行落库 —— 守卫位置错了（在 persist 之后）");
        assertEquals(0, countByTabType(DISPLAY_NAME_NOT_STORAGE), "库里不该存在显示名行");
    }

    /**
     * 空白串是最阴的一个：它能<b>穿过</b> {@link ComponentService#assertValidTabType}
     * （那边「空 = 未配置，放行」是对的，因为 {@code component.tab_type} 可空），
     * 却<b>落进本表就是坏数据</b> —— {@code tab_type} 是 NOT NULL 的身份列，
     * 而 B-56 的 {@code checkOne} 把 {@code ""} 当越域值照报不误。
     * ⇒ 直接复用组件侧语义 = 洞只堵了一半。
     */
    @Test
    void blankIsRejectedEvenThoughComponentSideWouldAllowIt() {
        // 先钉住前提：组件侧确实放行空白（本断言失败说明 assertValidTabType 语义变了，本类的理由需重写）
        ComponentService.assertValidTabType("");
        ComponentService.assertValidTabType(null);
        System.out.println("---- B-59 前提：ComponentService.assertValidTabType 对 \"\"/null 放行 ✅ ----");

        for (String blank : new String[]{"", "   "}) {
            BusinessException ex = assertThrows(BusinessException.class,
                    () -> service.createTabView(reqWithTabType(blank), "b59-test"),
                    "空白 tabType「" + blank + "」必须被本表的写入闸拒绝");
            System.out.println("  blank=「" + blank + "」 → " + ex.getCode() + " / " + ex.getMessage());
            assertEquals(400, ex.getCode());
        }

        BusinessException nullEx = assertThrows(BusinessException.class,
                () -> service.createTabView(reqWithTabType(null), "b59-test"),
                "null tabType 必须 400，而不是撞 NOT NULL 约束翻成 500");
        System.out.println("  null → " + nullEx.getCode() + " / " + nullEx.getMessage());
        assertEquals(400, nullEx.getCode());

        assertEquals(0, countByTabType(""), "空串行不该落库");
    }

    // ---------------- 反向：update 路径 ----------------

    /**
     * {@code updateTabViewPartial} 此前对 {@code tabType} 是<b>静默忽略</b>——传了不生效也不报错。
     * B-59 改成明确拒绝：越域先报值域错。
     */
    @Test
    void updatePathRejectsIllegalTabType() {
        UUID existing = anyExistingTabViewId();
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.updateTabViewPartial(existing,
                        java.util.Map.of("tabType", DISPLAY_NAME_NOT_STORAGE), "b59-test"));

        System.out.println("---- B-59 反向（update，显示名）----");
        System.out.println("  code    = " + ex.getCode());
        System.out.println("  message = " + ex.getMessage());

        assertEquals(400, ex.getCode());
        assertEquals(0, countByTabType(DISPLAY_NAME_NOT_STORAGE), "update 路径也不许把显示名写进去");
    }

    /** 回传原值（echo-back）是无操作，必须放行 —— 否则会把「只想改 switches」的调用方误伤。 */
    @Test
    void updatePathToleratesEchoBackOfCurrentTabType() {
        UUID id = anyExistingTabViewId();
        String current = tabTypeOf(id);
        int version = service.updateTabViewPartial(id, java.util.Map.of("tabType", current), "b59-test");
        System.out.println("---- B-59 update echo-back「" + current + "」→ 放行，graphVersion=" + version + " ----");
        assertEquals(current, tabTypeOf(id), "echo-back 不该改变 tab_type");
    }

    // ---------------- 正向：合法值照常 200，建完按主键删掉 ----------------

    @Test
    void legalTabTypeStillCreatesAndIsCleanedUp() {
        String legal = "BOM";
        UUID anchor = anyExistingAnchorNodeId();
        long before = countByVariant(TMP_VARIANT);
        assertEquals(0, before, "前置：临时 variant_key 不该已存在（上次残留？）");

        UUID created = null;
        try {
            TabViewUpsertRequest req = reqWithTabType(legal);
            req.anchorNodeId = anchor;
            req.variantKey = TMP_VARIANT;
            req.variantLabel = "B-59 自证临时行";
            req.dialect = "QUOTE";

            int version = service.createTabView(req, "b59-test");
            created = idByVariant(TMP_VARIANT);

            System.out.println("---- B-59 正向 ----");
            System.out.println("  合法值「" + legal + "」建视图成功，graphVersion=" + version);
            System.out.println("  落库 id = " + created + "，行数 = " + countByVariant(TMP_VARIANT));

            assertNotNull(created, "合法值必须真的落库");
            assertEquals(1, countByVariant(TMP_VARIANT));
        } finally {
            if (created != null) {
                // 🚨 自清：走应用自己的删除路径，**按主键**，命中面 1 行。
                service.deleteTabView(created, "b59-test");
            }
        }
        long after = countByVariant(TMP_VARIANT);
        System.out.println("  清理后行数 = " + after);
        assertEquals(0, after, "自证产生的临时行必须清干净（共享开发库）");
    }

    /** 收尾体检：跑完本类后，库里 tab_type 全域仍合法 —— 即 B-56 下次启动不会被本类的残留炸掉。 */
    @Test
    void leavesNoOutOfDomainRowsBehind() {
        List<String> bad = illegalTabTypes();
        System.out.println("---- B-59 收尾：越域取值 = " + bad
                + "（合法值域 " + new TreeSet<>(ComponentService.VALID_TAB_TYPES) + "）----");
        assertTrue(bad.isEmpty(), "共享库里出现越域 tab_type：" + bad + " —— B-56 下次启动会失败");
    }

    // ---------------- 夹具 ----------------

    private TabViewUpsertRequest reqWithTabType(String tabType) {
        TabViewUpsertRequest req = new TabViewUpsertRequest();
        req.tabType = tabType;
        req.anchorNodeId = anyExistingAnchorNodeId();
        req.dialect = "QUOTE";
        req.variantKey = TMP_VARIANT;
        return req;
    }

    private UUID anyExistingAnchorNodeId() {
        return queryUuid("SELECT anchor_node_id FROM semantic_tab_view ORDER BY id LIMIT 1");
    }

    private UUID anyExistingTabViewId() {
        return queryUuid("SELECT id FROM semantic_tab_view ORDER BY id LIMIT 1");
    }

    private String tabTypeOf(UUID id) {
        try (Connection cn = dataSource.getConnection();
             PreparedStatement ps = cn.prepareStatement("SELECT tab_type FROM semantic_tab_view WHERE id = ?")) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private long countByTabType(String tabType) {
        return queryLong("SELECT count(*) FROM semantic_tab_view WHERE tab_type = ?", tabType);
    }

    private long countByVariant(String variantKey) {
        return queryLong("SELECT count(*) FROM semantic_tab_view WHERE variant_key = ?", variantKey);
    }

    private UUID idByVariant(String variantKey) {
        List<UUID> ids = idsByVariant(variantKey);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private List<UUID> idsByVariant(String variantKey) {
        List<UUID> ids = new java.util.ArrayList<>();
        try (Connection cn = dataSource.getConnection();
             PreparedStatement ps = cn.prepareStatement(
                     "SELECT id FROM semantic_tab_view WHERE variant_key = ?")) {
            ps.setString(1, variantKey);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) ids.add((UUID) rs.getObject(1));
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return ids;
    }

    /** 按主键删，命中面恒 1 行；子表随 {@code ON DELETE CASCADE} 走。 */
    private void deleteById(UUID id) {
        try (Connection cn = dataSource.getConnection();
             PreparedStatement ps = cn.prepareStatement("DELETE FROM semantic_tab_view WHERE id = ?")) {
            ps.setObject(1, id);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 与 B-56 同口径：按取值分组，不加 status 过滤（装载器是 listAll()）。 */
    private List<String> illegalTabTypes() {
        List<String> bad = new java.util.ArrayList<>();
        try (Connection cn = dataSource.getConnection();
             PreparedStatement ps = cn.prepareStatement(
                     "SELECT tab_type, count(*) FROM semantic_tab_view GROUP BY 1 ORDER BY 1");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String v = rs.getString(1);
                if (v == null || !ComponentService.VALID_TAB_TYPES.contains(v)) {
                    bad.add((v == null ? "<NULL>" : v) + "×" + rs.getLong(2));
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return bad;
    }

    private UUID queryUuid(String sql) {
        try (Connection cn = dataSource.getConnection();
             PreparedStatement ps = cn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) throw new IllegalStateException("语义图种子未落库，无法取夹具：" + sql);
            return (UUID) rs.getObject(1);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private long queryLong(String sql, String arg) {
        try (Connection cn = dataSource.getConnection();
             PreparedStatement ps = cn.prepareStatement(sql)) {
            ps.setString(1, arg);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
