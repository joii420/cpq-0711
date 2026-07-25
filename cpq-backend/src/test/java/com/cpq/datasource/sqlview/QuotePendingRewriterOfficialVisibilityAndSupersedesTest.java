package com.cpq.datasource.sqlview;

import com.cpq.component.dto.ExpandDriverResponse;
import com.cpq.component.service.ComponentDriverService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * task-0725 T4 · 7.7（技术总监补充要求）—— 单测级覆盖 TC-ISO-02（官方 current 行在 pending 改写下
 * 仍可见）与 TC-ISO-03（{@code pending_supersedes} 正确遮蔽旧 current 行、不翻倍）。
 *
 * <p><b>为什么不是集成级测试</b>（需求说明.md §16.1 第 1/2 点，技术总监已裁决）：本环境 QUOTE 侧
 * 官方 current 行数为 0（全库只有本单 pending 数据），{@code pending_supersedes} 非空行数也是 0，
 * 两个场景在<b>集成层</b>都没有现成样本；技术总监已明确否决"专门造遮蔽测试数据污染验收样本"这条
 * 路径。本测试改在单元测试<b>自建的隔离夹具行</b>上验证 {@link QuotePendingRewriter} 生成的 SQL
 * 语义：{@code component_sql_view} 指向真实 {@code unit_price} 表，但夹具行的
 * {@code finished_material_no} 用随机字符串（不与任何真实业务料号重合），查询时又按
 * {@code hf_part_no = ANY(:hfPartNos)} 精确收窄到该料号——这些孤立行不会被任何真实报价单/核价单
 * 的查询捞到，测试本身也不依赖库里已有什么数据，纯粹自给自足。
 */
@QuarkusTest
class QuotePendingRewriterOfficialVisibilityAndSupersedesTest {

    private static final String VIEW_COMPONENT_CODE = "TEST-AC17-ISO-VIEW";
    private static final String VIEW_NAME = "test_ac17_iso_view";

    @Inject
    EntityManager em;

    @Inject
    UserTransaction utx;

    @Inject
    ComponentDriverService componentDriverService;

    private UUID componentId;
    private String officialPartNo;
    private String supersedePartNo;
    private UUID officialSupersededRowId;

    @BeforeEach
    void seed() throws Exception {
        utx.begin();
        em.joinTransaction();

        em.createNativeQuery("DELETE FROM component_sql_view WHERE sql_view_name = :n")
                .setParameter("n", VIEW_NAME).executeUpdate();
        em.createNativeQuery("DELETE FROM component WHERE code = :c")
                .setParameter("c", VIEW_COMPONENT_CODE).executeUpdate();

        componentId = UUID.randomUUID();
        em.createNativeQuery(
                "INSERT INTO component (id, name, code, fields, formulas, status, component_type, " +
                "  data_driver_path, excel_columns, column_count, bom_recursive_expand, created_at, updated_at) " +
                "VALUES (:id, :name, :code, '[]'::jsonb, '[]'::jsonb, 'ACTIVE', 'NORMAL', " +
                "  :dp, '[]'::jsonb, 0, false, NOW(), NOW())")
                .setParameter("id", componentId)
                .setParameter("name", "AC17 官方可见性+遮蔽测试夹具")
                .setParameter("code", VIEW_COMPONENT_CODE)
                .setParameter("dp", "$" + VIEW_NAME)
                .executeUpdate();

        em.createNativeQuery(
                "INSERT INTO component_sql_view (id, component_id, sql_view_name, sql_template, scope, status, created_at, updated_at) " +
                "VALUES (gen_random_uuid(), :cid, :vn, :tpl, 'COMPONENT', 'ACTIVE', NOW(), NOW())")
                .setParameter("cid", componentId)
                .setParameter("vn", VIEW_NAME)
                .setParameter("tpl",
                        "SELECT up.id, up.finished_material_no AS hf_part_no, up.code AS _code\n" +
                        "FROM unit_price up\n" +
                        "WHERE up.system_type = 'QUOTE' AND up.is_current = true")
                .executeUpdate();

        // finished_material_no 列上限 varchar(20)，code 列上限 varchar(30)——夹具值必须踩线内。
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        officialPartNo = "TP-A-" + suffix;      // 13 字符
        supersedePartNo = "TP-B-" + suffix;     // 13 字符
        officialSupersededRowId = UUID.randomUUID();

        // TC-ISO-02 夹具：一条纯官方 current 行，与任何 pending 单据都无关。
        em.createNativeQuery(
                "INSERT INTO unit_price (id, system_type, price_type, version_no, code, finished_material_no, is_current) " +
                "VALUES (gen_random_uuid(), 'QUOTE', 'PART', 'V1', :code, :fmn, true)")
                .setParameter("code", "ISOA-OFF-" + suffix)   // 18 字符，< 30
                .setParameter("fmn", officialPartNo)
                .executeUpdate();

        // TC-ISO-03 夹具：一条将被遮蔽的旧官方 current 行（本方法先只插这一条；对应的 pending
        // supersede 行按测试各自需要延后插入，见 insertPendingSupersedingRow）。
        em.createNativeQuery(
                "INSERT INTO unit_price (id, system_type, price_type, version_no, code, finished_material_no, is_current) " +
                "VALUES (:id, 'QUOTE', 'PART', 'V1', :code, :fmn, true)")
                .setParameter("id", officialSupersededRowId)
                .setParameter("code", "ISOB-OFF-" + suffix)   // 18 字符，< 30
                .setParameter("fmn", supersedePartNo)
                .executeUpdate();

        utx.commit();
    }

    @AfterEach
    void cleanup() throws Exception {
        QuotePendingScope.restore(null);
        SqlViewRuntimeContext.clear();
        // task-0725 技术总监补充：夹具组件/视图原先只在 @BeforeEach 清，导致最后一次跑完会在
        // 共享库 cpq_db 里留下孤儿 component + component_sql_view（不挂任何模板故不影响渲染，
        // 但会污染组件列表、干扰后续人工核对）。改为跑完即清。
        try {
            utx.begin();
            em.joinTransaction();
            em.createNativeQuery("DELETE FROM component_sql_view WHERE sql_view_name = :n")
                .setParameter("n", VIEW_NAME).executeUpdate();
        em.createNativeQuery("DELETE FROM component WHERE code = :c")
                .setParameter("c", VIEW_COMPONENT_CODE).executeUpdate();
            utx.commit();
        } catch (Exception ignore) {
            // 最佳努力清理
        }
        try {
            utx.begin();
            em.joinTransaction();
            em.createNativeQuery("DELETE FROM unit_price WHERE finished_material_no IN (:a, :b)")
                    .setParameter("a", officialPartNo == null ? "" : officialPartNo)
                    .setParameter("b", supersedePartNo == null ? "" : supersedePartNo)
                    .executeUpdate();
            utx.commit();
        } catch (Exception e) {
            // 最佳努力清理；本测试对真实业务表的唯一足迹只有这几行随机料号数据，
            // 即便清理失败也不影响任何真实查询（不会被任何真实 hf_part_no 命中）。
        }
    }

    private void insertPendingSupersedingRow(UUID qid) throws Exception {
        utx.begin();
        em.joinTransaction();
        em.createNativeQuery(
                "INSERT INTO unit_price (id, system_type, price_type, version_no, code, finished_material_no, " +
                "  is_current, pending_quotation_id, pending_supersedes) " +
                "VALUES (gen_random_uuid(), 'QUOTE', 'PART', 'V1', :code, :fmn, false, :qid, ARRAY[:supId]::uuid[])")
                .setParameter("code", "ISOB-PEND-" + qid.toString().replace("-", "").substring(0, 8)) // <30字符
                .setParameter("fmn", supersedePartNo)
                .setParameter("qid", qid)
                .setParameter("supId", officialSupersededRowId)
                .executeUpdate();
        utx.commit();
    }

    // ═══════════════════════ TC-ISO-02：官方 current 行仍可见 ═══════════════════════

    @Test
    void tcIso02_officialCurrentRow_visibleBothScopeClosedAndOpen() {
        // scope 关闭（核价侧天然状态）：不改写，走原始 SQL，官方行按用户原写的 is_current=true 直接可见。
        QuotePendingScope.restore(null);
        ExpandDriverResponse closedResp = componentDriverService.expand(componentId, null, officialPartNo, null);
        assertEquals(1, closedResp.rowCount, "scope 关闭态下官方 current 行应可见（未改写，原始过滤）");

        // scope 打开：走改写后的 SQL，官方行经
        // "is_current AND pending_quotation_id IS NULL AND NOT EXISTS(...)" 分支仍应可见——
        // 这正是 TC-ISO-02 要证明的不变式：pending 改写只是"多加一条可见性"，不是"替换可见性"。
        UUID qid = UUID.randomUUID();
        UUID prev = QuotePendingScope.open(qid, "DRAFT");
        ExpandDriverResponse openResp;
        try {
            openResp = componentDriverService.expand(componentId, null, officialPartNo, null);
        } finally {
            QuotePendingScope.restore(prev);
        }
        assertEquals(1, openResp.rowCount,
                "TC-ISO-02：scope 打开（pending 改写生效）后，与本单无关的官方 current 行必须仍然可见");
    }

    // ═══════════════════ TC-ISO-03：pending_supersedes 遮蔽、不翻倍 ═══════════════════

    @Test
    void tcIso03_pendingSupersedes_hidesOldOfficialRow_noDoubling() throws Exception {
        UUID qid = UUID.randomUUID();
        insertPendingSupersedingRow(qid);

        // scope 关闭：原始 SQL 只按 is_current=true 过滤，pending 行（is_current=false）天然不可见，
        // 只有旧官方行可见——验证夹具本身在"未改写"状态下的基线正确（1 行）。
        QuotePendingScope.restore(null);
        ExpandDriverResponse closedResp = componentDriverService.expand(componentId, null, supersedePartNo, null);
        assertEquals(1, closedResp.rowCount, "改写关闭态基线：应只看到旧官方行（pending 行 is_current=false 天然不可见）");

        // scope 打开（本单 qid）：pending 行可见（走 pending_quotation_id=:pq 分支），
        // 旧官方行应被其 pending_supersedes 遮蔽——合计仍是 1 行，不翻倍（TC-ISO-03 核心断言）。
        UUID prevA = QuotePendingScope.open(qid, "DRAFT");
        ExpandDriverResponse openRespOwnQid;
        try {
            openRespOwnQid = componentDriverService.expand(componentId, null, supersedePartNo, null);
        } finally {
            QuotePendingScope.restore(prevA);
        }
        assertEquals(1, openRespOwnQid.rowCount,
                "TC-ISO-03：本单 scope 打开后应恰好 1 行（新 pending 行遮蔽旧官方行），不翻倍");
        String returnedCode = (String) openRespOwnQid.rows.get(0).driverRow.get("_code");
        assertTrue(returnedCode != null && returnedCode.startsWith("ISOB-PEND-"),
                "TC-ISO-03：可见的那一行必须是新 pending 行（旧官方行已被遮蔽），实际 code=" + returnedCode);

        // 反向对照：换一个与本次 supersede 无关的另一张报价单 qid 打开 scope，遮蔽只应对
        // "发起 supersede 的那张单"生效（:pq 绑定值不同）——旧官方行对它仍可见，证明遮蔽是
        // 按单隔离的，不会误伤其它报价单的视角（同时验证不相关 pending 行也不会跨单泄漏可见）。
        UUID unrelatedQid = UUID.randomUUID();
        UUID prevB = QuotePendingScope.open(unrelatedQid, "DRAFT");
        ExpandDriverResponse openRespOtherQid;
        try {
            openRespOtherQid = componentDriverService.expand(componentId, null, supersedePartNo, null);
        } finally {
            QuotePendingScope.restore(prevB);
        }
        assertEquals(1, openRespOtherQid.rowCount,
                "反向对照：不相关报价单打开 scope 时，遮蔽不应生效，应仍看到旧官方行（1 行，不是 0 或 2）");
        String otherCode = (String) openRespOtherQid.rows.get(0).driverRow.get("_code");
        assertTrue(otherCode != null && otherCode.startsWith("ISOB-OFF-"),
                "反向对照：不相关报价单看到的应是旧官方行，实际 code=" + otherCode);
    }
}
