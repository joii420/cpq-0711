package com.cpq.basicdata.v6.service;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * repair-260829（卡片值算早了骨架值锁死）· T-00（B-4 交付物冒烟）。
 *
 * <p><b>覆盖什么</b>：{@link MaterializeRegistry} 是本次返修新增的内存态 in-progress 标志 bean，
 * 供 {@link CreateQuotationMaterializer#materialize} 在方法开头 {@code begin}、finally 里
 * {@code end}，并被 {@code CardSnapshotService#ensureCardValuesDetailed}（B-1b）读取用于第二层
 * 守卫。本文件只验 registry 自身的行为契约（begin/end/isInProgress）以及"异常终止时 finally
 * 是否真的执行"这两件事，不涉及 B-1/B-1b 的判据逻辑本身（那部分见
 * {@code CardSnapshotEarlySkeletonGuardTest} / {@code CardSnapshotMaterializeInProgressGuardTest}）。
 *
 * <p><b>T-00-b 的异常构造手法</b>：沿用 {@code CreateQuotationEmptyGuardBoundaryTest} 已踩出的坑——
 * PUBLISHED 模板若 {@code components_snapshot} JSON 数组长度与 {@code template_component_snapshot}
 * 实际行数不一致（未走真正"冻结"动作），{@code PublishedTemplateReader.verifyConsistentWithJsonb}
 * 会抛异常，并被 {@code CreateQuotationMaterializer.materialize} 内部
 * {@code checkMaterializeOutcome}/顶层 {@code catch (Exception e)} 吞掉、降级为日志/warning，
 * 不上抛给调用方——这正好用来验证"内部异常终止时 {@code finally} 里的 {@code registry.end(qid)}
 * 是否仍然执行"，不需要 mock 任何东西。
 *
 * <p><b>实测记录（如实覆盖写注释时的最初预期）</b>：本文件 fixture（jsonb 数组长度=1、
 * {@code template_component_snapshot} 表行数=0）实测触发的是
 * {@code BusinessException(500, "模板快照损坏：...")}（D19"长度不一致"分支，经
 * {@code CreateQuotationMaterializer.checkMaterializeOutcome} 调 {@code loadDriverComponents}
 * 触发），<b>不是</b>最初设计意图里的
 * {@link com.cpq.template.exception.TemplateNotFrozenException}（D17"两侧都为0"分支）——
 * 两者都是"内部异常终止"，对本条断言（finally 是否清干净标志）而言等价，跑过一遍确认
 * {@code Tests run: 3, Failures: 0, Errors: 0} 后如实改注释，不按最初假设不写。
 */
@QuarkusTest
class MaterializeRegistryTest {

    @Inject MaterializeRegistry registry;
    @Inject EntityManager em;
    @Inject CreateQuotationMaterializer materializer;

    private final List<UUID> quotationIds = new ArrayList<>();
    private final List<UUID> templateIds = new ArrayList<>();

    private static UUID toUUID(Object o) {
        return (o instanceof UUID u) ? u : UUID.fromString(o.toString());
    }

    @AfterEach
    void cleanup() {
        QuarkusTransaction.requiringNew().run(() -> {
            for (UUID qid : quotationIds) {
                em.createNativeQuery("DELETE FROM quotation_view_structure WHERE quotation_id = :q").setParameter("q", qid).executeUpdate();
                em.createNativeQuery("DELETE FROM quotation_line_component_data WHERE line_item_id IN (SELECT id FROM quotation_line_item WHERE quotation_id = :q)").setParameter("q", qid).executeUpdate();
                em.createNativeQuery("DELETE FROM quotation_line_item WHERE quotation_id = :q").setParameter("q", qid).executeUpdate();
                em.createNativeQuery("DELETE FROM quotation WHERE id = :q").setParameter("q", qid).executeUpdate();
            }
            for (UUID t : templateIds) {
                em.createNativeQuery("DELETE FROM template_component_snapshot WHERE template_id = :t").setParameter("t", t).executeUpdate();
                em.createNativeQuery("DELETE FROM template_component WHERE template_id = :t").setParameter("t", t).executeUpdate();
                em.createNativeQuery("DELETE FROM template WHERE id = :t").setParameter("t", t).executeUpdate();
            }
        });
        quotationIds.clear();
        templateIds.clear();
        // 保险丝：万一某次断言前抛异常导致标志未被自然清理，防止污染下一个测试方法。
        registry.end(PROBE_QID);
    }

    private static final UUID PROBE_QID = UUID.randomUUID();

    @Test
    @DisplayName("T-00-a: begin/end/isInProgress 基本契约 + null 安全")
    void beginEndIsInProgress_basicContract() {
        UUID qid = UUID.randomUUID();
        assertFalse(registry.isInProgress(qid), "从未 begin 过的 qid 应为 false");

        registry.begin(qid);
        assertTrue(registry.isInProgress(qid), "begin 后应为 true");

        registry.end(qid);
        assertFalse(registry.isInProgress(qid), "end 后应为 false");

        // null 安全：begin/end/isInProgress 均不应抛异常
        assertDoesNotThrow(() -> registry.begin(null));
        assertDoesNotThrow(() -> registry.end(null));
        assertFalse(registry.isInProgress(null), "isInProgress(null) 应为 false,不抛异常");
    }

    @Test
    @DisplayName("T-00-a2: 同一 qid 可重复 begin(幂等占位)+多次 end 安全")
    void repeatedBeginEnd_idempotentAndSafe() {
        UUID qid = UUID.randomUUID();
        registry.begin(qid);
        registry.begin(qid); // 重复 begin 不应抛异常、不应需要两次 end 才能清零
        assertTrue(registry.isInProgress(qid));
        registry.end(qid);
        assertFalse(registry.isInProgress(qid), "一次 end 应足以清除标志(Set 语义,非计数器)");
        assertDoesNotThrow(() -> registry.end(qid), "对已清除的 qid 重复 end 不应抛异常");
    }

    /** 建一个"PUBLISHED 但未真正冻结"的模板 + 1 行 quotation,用于制造 materialize() 内部异常。 */
    private UUID buildUnfrozenTemplateFixture(String tag) {
        UUID templateId = UUID.randomUUID();
        templateIds.add(templateId);
        UUID fakeComponentId = UUID.randomUUID();
        // components_snapshot 数组长度=1,但故意不插入对应的 template_component_snapshot 行,
        // 制造"数组长度(1) != 冻结快照行数(0)"的不一致,触发 BusinessException(500,模板快照损坏,D19分支)——实测命中,见类注释。
        String snapshot = "[{\"id\":\"" + UUID.randomUUID() + "\",\"componentId\":\"" + fakeComponentId +
                "\",\"componentName\":\"" + tag + "-组件\",\"componentCode\":\"" + tag +
                "\",\"componentType\":\"NORMAL\",\"tabName\":\"" + tag + "页签\",\"sortOrder\":0," +
                "\"fields\":[],\"formulas\":[]}]";
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("INSERT INTO template (id, template_series_id, name, template_kind, status, components_snapshot, created_at, updated_at) " +
                    "VALUES (:id, :tsid, :name, 'QUOTATION', 'PUBLISHED', CAST(:snap AS jsonb), now(), now())")
                    .setParameter("id", templateId).setParameter("tsid", UUID.randomUUID())
                    .setParameter("name", tag + "-未冻结模板").setParameter("snap", snapshot).executeUpdate();
            // 刻意不插入 template_component / template_component_snapshot 行。
        });

        UUID quotationId = UUID.randomUUID();
        QuarkusTransaction.requiringNew().run(() -> {
            UUID customerId = toUUID(em.createNativeQuery("SELECT id FROM customer LIMIT 1").getResultList().get(0));
            UUID salesRepId = toUUID(em.createNativeQuery("SELECT id FROM \"user\" LIMIT 1").getResultList().get(0));
            em.createNativeQuery("INSERT INTO quotation (id, quotation_number, customer_id, name, sales_rep_id, status, " +
                    "customer_template_id, tax_rate, tax_amount, created_at, updated_at) " +
                    "VALUES (:id, :qn, :cid, :name, :srid, 'DRAFT', :tid, 0, 0, now(), now())")
                    .setParameter("id", quotationId).setParameter("qn", tag + "-" + quotationId.toString().substring(0, 8))
                    .setParameter("cid", customerId).setParameter("name", tag).setParameter("srid", salesRepId)
                    .setParameter("tid", templateId).executeUpdate();
            UUID lid = UUID.randomUUID();
            em.createNativeQuery("INSERT INTO quotation_line_item (id, quotation_id, template_id, " +
                    "product_part_no_snapshot, sort_order, created_at) VALUES (:id, :qid, :tid, :pn, 0, now())")
                    .setParameter("id", lid).setParameter("qid", quotationId).setParameter("tid", templateId)
                    .setParameter("pn", tag + "-P0").executeUpdate();
        });
        quotationIds.add(quotationId);
        return quotationId;
    }

    @Test
    @DisplayName("T-00-b(B-4 finally 保证): materialize() 内部因未冻结模板异常终止,registry 标志仍被清干净")
    void materialize_internalException_stillClearsInProgressFlag() {
        UUID qid = buildUnfrozenTemplateFixture("T00MRB");

        // 前置断言:开跑前标志确实是干净的(不是"从来没脏过"这种平凡通过)
        assertFalse(registry.isInProgress(qid), "开跑前不应处于 in-progress 状态");

        V6QuotationCommitService.CommitResult r =
                new V6QuotationCommitService.CommitResult(qid, UUID.randomUUID(), 1);

        // materialize() 内部吞异常,不应该把异常抛给调用方(实测命中 BusinessException(500,模板快照损坏),见类注释)。
        assertDoesNotThrow(() -> materializer.materialize(r),
                "materialize() 顶层应吞掉内部异常,不应向调用方抛出");

        // 核心断言(B-4 的 finally 契约):无论内部是否异常终止,标志都必须被清干净,不能悬挂 true。
        assertFalse(registry.isInProgress(qid),
                "materialize() 内部异常终止后,registry.isInProgress(qid) 必须为 false(finally 未泄漏)");
    }
}
