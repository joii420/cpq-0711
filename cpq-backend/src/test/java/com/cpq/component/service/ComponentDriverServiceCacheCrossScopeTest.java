package com.cpq.component.service;

import com.cpq.component.dto.ExpandDriverResponse;
import com.cpq.datasource.sqlview.QuotePendingScope;
import com.cpq.datasource.sqlview.SqlViewRuntimeContext;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * task-0725 T4 · AC-17 门禁 7.6 —— AP-37 两层缓存交叉专项，{@link ComponentDriverService#expandCache}
 * 这一层的<b>活缓存</b>级证明。
 *
 * <p>补 T2 已交付的纯函数级证明（{@code ComponentDriverServiceCacheKeyTest#buildExtraCacheTags_*}
 * / {@code DataLoaderScopedCacheKeyTest}）——那两个测试类只验证"key 字符串构造逻辑本身"（反射调私有
 * 静态方法拼字符串），没有经过真实 Caffeine 缓存的读写路径。本测试改为直接调用公开的
 * {@link ComponentDriverService#expand}，用<b>返回对象的引用同一性</b>判定缓存命中/未命中——
 * 该判据与查询结果是否为空行无关，在 0 行环境下同样可靠："{@code cached != null → return cached}"
 * 只在真正命中<b>同一个 key</b> 时才会返回同一引用；key 不同则必然是一次新查询、一个新对象。
 *
 * <p><b>验收范围限制</b>（需求说明.md §8-7）：本环境样本单 {@code costing_card_template_id} 为
 * NULL，报价侧/核价侧共享同一 lineItem 的集成级样本不存在，无法端到端复现
 * {@code CreateQuotationMaterializer:41/43} "同请求先报价后核价" 场景。本测试改为单测级：直接
 * 操纵 {@link QuotePendingScope} 开/关，对同一 {@code componentId}/{@code customerId}/
 * {@code partNo}/{@code partVersion} 连续调用 {@link ComponentDriverService#expand}，覆盖
 * backtask 7.6 要求的"先报价侧（开域）再核价侧（关域）"与"反向顺序"两种时序。
 */
@QuarkusTest
class ComponentDriverServiceCacheCrossScopeTest {

    private static final String TEST_COMPONENT_CODE = "TEST-AC17-CACHE-CROSS-VIEW";
    private static final String TEST_VIEW_NAME = "test_ac17_cache_cross_view";

    @Inject
    EntityManager em;

    @Inject
    UserTransaction utx;

    @Inject
    ComponentDriverService componentDriverService;

    private UUID componentId;

    @BeforeEach
    void seed() throws Exception {
        utx.begin();
        em.joinTransaction();

        em.createNativeQuery("DELETE FROM component_sql_view WHERE sql_view_name = :n")
                .setParameter("n", TEST_VIEW_NAME).executeUpdate();
        em.createNativeQuery("DELETE FROM component WHERE code = :c")
                .setParameter("c", TEST_COMPONENT_CODE).executeUpdate();

        componentId = UUID.randomUUID();
        em.createNativeQuery(
                "INSERT INTO component (id, name, code, fields, formulas, status, component_type, " +
                "  data_driver_path, excel_columns, column_count, bom_recursive_expand, created_at, updated_at) " +
                "VALUES (:id, :name, :code, '[]'::jsonb, '[]'::jsonb, 'ACTIVE', 'NORMAL', " +
                "  :dp, '[]'::jsonb, 0, false, NOW(), NOW())")
                .setParameter("id", componentId)
                .setParameter("name", "AC17 缓存交叉测试夹具")
                .setParameter("code", TEST_COMPONENT_CODE)
                .setParameter("dp", "$" + TEST_VIEW_NAME)
                .executeUpdate();

        em.createNativeQuery(
                "INSERT INTO component_sql_view (id, component_id, sql_view_name, sql_template, scope, status, created_at, updated_at) " +
                "VALUES (gen_random_uuid(), :cid, :vn, :tpl, 'COMPONENT', 'ACTIVE', NOW(), NOW())")
                .setParameter("cid", componentId)
                .setParameter("vn", TEST_VIEW_NAME)
                .setParameter("tpl",
                        "SELECT up.id, up.code AS _code FROM unit_price up " +
                        "WHERE up.system_type = 'QUOTE' AND up.customer_no = :customerCode")
                .executeUpdate();

        utx.commit();

        // componentId 每次都是新随机 UUID，理论上不会撞到其它测试类的 key；evictAll 是零成本的额外保险，
        // 避免万一同一 JVM fork 内的其它测试残留了同 key（例如极小概率的 UUID 碰撞，或未来有人复用 code）。
        componentDriverService.evictAll();
    }

    @AfterEach
    void cleanup() {
        QuotePendingScope.restore(null);
        SqlViewRuntimeContext.clear();
        // task-0725 技术总监补充：夹具组件/视图原先只在 @BeforeEach 清，导致最后一次跑完会在
        // 共享库 cpq_db 里留下孤儿 component + component_sql_view（不挂任何模板故不影响渲染，
        // 但会污染组件列表、干扰后续人工核对）。改为跑完即清。
        try {
            utx.begin();
            em.joinTransaction();
            em.createNativeQuery("DELETE FROM component_sql_view WHERE sql_view_name = :n")
                .setParameter("n", TEST_VIEW_NAME).executeUpdate();
        em.createNativeQuery("DELETE FROM component WHERE code = :c")
                .setParameter("c", TEST_COMPONENT_CODE).executeUpdate();
            utx.commit();
        } catch (Exception ignore) {
            // 最佳努力清理
        }
    }

    @Test
    void closedThenOpen_thenClosedAgain_noCrossContamination() {
        UUID customerId = null;
        String partNo = null;

        // ① 关闭态（核价侧天然状态）：首次调用，必然缓存 MISS，产出并缓存一个响应对象。
        QuotePendingScope.restore(null);
        ExpandDriverResponse closed1 = componentDriverService.expand(componentId, customerId, partNo, null);
        assertNotNull(closed1);

        // ② 紧接着同参数再调一次，仍是关闭态：30s TTL 内必须命中同一缓存条目（引用相同）——
        //    这一步同时验证本测试"引用同一性=缓存命中"这个判据本身在测试环境里是可靠的（正对照）。
        ExpandDriverResponse closed1b = componentDriverService.expand(componentId, customerId, partNo, null);
        assertSame(closed1, closed1b,
                "同一 scope 状态、同一参数连续两次 expand() 应命中同一缓存条目（本测试判据的正对照；" +
                        "若此断言失败说明 expand() 缓存机制在测试环境本身未生效，下面的跨域断言会失去意义）");

        // ③ 打开报价侧作用域，同参数再调一次：必须是全新对象（缓存 MISS），证明
        //    QuotePendingScope.cacheTag() 已让它落到不同 key，没有复用②的关闭态缓存条目
        //    （即没有从核价侧读到报价侧改写后的缓存值，AP-37 型串号）。
        UUID qid = UUID.randomUUID();
        UUID prev = QuotePendingScope.open(qid, "DRAFT");
        ExpandDriverResponse open1;
        try {
            open1 = componentDriverService.expand(componentId, customerId, partNo, null);
        } finally {
            QuotePendingScope.restore(prev);
        }
        assertNotSame(closed1, open1,
                "scope 打开后同参数 expand() 返回了与关闭态相同的缓存对象引用——" +
                        "说明 QuotePendingScope.cacheTag() 未生效或未进入 expandCache key，AP-37 跨侧串号风险成立");

        // ④ 再次打开作用域（同一 qid），同参数再调一次：应命中③写入的开放态缓存条目。
        UUID prev2 = QuotePendingScope.open(qid, "DRAFT");
        ExpandDriverResponse open1b;
        try {
            open1b = componentDriverService.expand(componentId, customerId, partNo, null);
        } finally {
            QuotePendingScope.restore(prev2);
        }
        assertSame(open1, open1b, "打开态自身的缓存条目应能被自己复用（否则打开态之间也会互相污染/雪崩重算）");

        // ⑤ 关闭作用域，第三次调用：应仍命中①②写入的关闭态缓存条目，证明"打开态的插曲"没有
        //    覆盖/驱逐关闭态原有的缓存条目——双向隔离，不只是"打开态读不到关闭态"单向。
        QuotePendingScope.restore(null);
        ExpandDriverResponse closed2 = componentDriverService.expand(componentId, customerId, partNo, null);
        assertSame(closed1, closed2,
                "打开态的调用不应驱逐/覆盖关闭态原有的缓存条目——这是双向隔离缺失的另一半，" +
                        "只测「开→关不串」不够，必须同时证明「关→开→关」时最初的关闭态条目还在。");
    }

    @Test
    void openThenClosed_thenOpenAgain_reverseOrder_noCrossContamination() {
        // backtask 7.6 明确要求"反向顺序同测"：先报价侧（开）再核价侧（关），验证顺序不影响隔离性。
        UUID customerId = null;
        String partNo = null;

        UUID qid = UUID.randomUUID();
        UUID prev = QuotePendingScope.open(qid, "DRAFT");
        ExpandDriverResponse open1;
        try {
            open1 = componentDriverService.expand(componentId, customerId, partNo, null);
        } finally {
            QuotePendingScope.restore(prev);
        }
        assertNotNull(open1);

        QuotePendingScope.restore(null);
        ExpandDriverResponse closed1 = componentDriverService.expand(componentId, customerId, partNo, null);
        assertNotSame(open1, closed1,
                "先开后关：关闭态（核价侧）不应复用开放态（报价侧）刚写入的缓存条目——" +
                        "这正是 backtask 举的具体风险场景：CreateQuotationMaterializer 在同一请求内先跑报价" +
                        "再跑核价，核价侧若命中报价侧缓存会直接拿到带 pending 数据/__v6_id 的结果。");

        ExpandDriverResponse closed1b = componentDriverService.expand(componentId, customerId, partNo, null);
        assertSame(closed1, closed1b, "关闭态自身缓存条目应能被自己复用");

        UUID prev2 = QuotePendingScope.open(qid, "DRAFT");
        ExpandDriverResponse open1b;
        try {
            open1b = componentDriverService.expand(componentId, customerId, partNo, null);
        } finally {
            QuotePendingScope.restore(prev2);
        }
        assertSame(open1, open1b, "再次打开同一 qid：应命中最初写入的开放态缓存条目，未被中途的关闭态调用驱逐");
    }
}
