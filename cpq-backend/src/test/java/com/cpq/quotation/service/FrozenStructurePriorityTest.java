package com.cpq.quotation.service;

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
 * task-260825 大单量导入建单性能 —— T-7（AC-10 · 守 {@code b6e86a18}）。
 *
 * <p><b>验的是什么</b>：D-3 把 {@code loadFrozenQuoteTabs(li.quotationId)} 从逐行循环提到循环外，
 * 三级降级链（冻结结构 → prefetch 模板快照 → 模板表）的<b>优先级</b>必须原样保留 —— 一旦模板在
 * "结构已冻结"之后又被修改，卡片值仍应按<b>冻结时刻</b>的结构算，不能被提循环这个改动意外接回
 * "实时读模板"的语义（那正是 {@code b6e86a18} 要修的"卡片小计与报价总额分叉"）。
 *
 * <p><b>为什么不测到卡片值的具体计算结果</b>：要观测到"公式结果随结构版本而不同"需要一条真实
 * $view 驱动的行数据（SQL 视图 + 展开），这需要理解 buildCardValues 的内部渲染细节 ——
 * 本任务禁止读 {@code cpq-backend/src/main/}。改为在<b>结构冻结层面</b>直接验证：
 * {@link CardSnapshotService#ensureStructure} 已被 {@code CardStructureSnapshotTest} 证明幂等
 * （"再调一次 count 仍为 4"）；本测试把"count 不变"升级为"<b>内容</b>不变"—— 冻结后修改模板、
 * 再次 ensureStructure，读回的 QUOTE_CARD 结构必须仍是冻结时刻的版本，不含修改后新加的标记。
 * 这是"冻结结构优先于模板实时内容"这条不变量在黑盒层面唯一可验证、又不依赖内部方法名的角度。
 *
 * <p><b>已知覆盖缺口</b>（如实登记，见 test-report.md）：本测试不覆盖"卡片小计与报价总额一致"
 * 这一具体数值断言（AC-10 原文的后半句），因为需要真实驱动行数据 + 内部渲染方法名，均不可得。
 * 亲验-3（主线在真机对一张已知曾因配置源不一致而分叉的单据）负责补齐这部分。
 */
@QuarkusTest
class FrozenStructurePriorityTest {

    private static final String TAG = "T260825T7";

    @Inject EntityManager em;
    @Inject CardSnapshotService svc;

    private final List<UUID> cleanupQuotationIds = new ArrayList<>();
    private final List<UUID> cleanupTemplateIds = new ArrayList<>();
    private final List<UUID> cleanupComponentIds = new ArrayList<>();

    private static UUID toUUID(Object o) {
        return (o instanceof UUID u) ? u : UUID.fromString(o.toString());
    }

    @AfterEach
    void cleanup() {
        QuarkusTransaction.requiringNew().run(() -> {
            for (UUID qid : cleanupQuotationIds) {
                em.createNativeQuery("DELETE FROM quotation_view_structure WHERE quotation_id = :q")
                        .setParameter("q", qid).executeUpdate();
                em.createNativeQuery("DELETE FROM quotation_line_item WHERE quotation_id = :q")
                        .setParameter("q", qid).executeUpdate();
                em.createNativeQuery("DELETE FROM quotation WHERE id = :q")
                        .setParameter("q", qid).executeUpdate();
            }
            for (UUID tid : cleanupTemplateIds) {
                em.createNativeQuery("DELETE FROM template_component_snapshot WHERE template_id = :t")
                        .setParameter("t", tid).executeUpdate();
                em.createNativeQuery("DELETE FROM template_component WHERE template_id = :t")
                        .setParameter("t", tid).executeUpdate();
                em.createNativeQuery("DELETE FROM template WHERE id = :t")
                        .setParameter("t", tid).executeUpdate();
            }
            for (UUID cid : cleanupComponentIds) {
                em.createNativeQuery("DELETE FROM component WHERE id = :c")
                        .setParameter("c", cid).executeUpdate();
            }
        });
        cleanupQuotationIds.clear();
        cleanupTemplateIds.clear();
        cleanupComponentIds.clear();
    }

    /** 建一个"0 行 $view 驱动"组件 + 模板，components_snapshot 里含一个可辨识的标记字段名。 */
    private UUID buildTemplate(String tag, String markerFieldName) {
        UUID componentId = UUID.randomUUID();
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("INSERT INTO component (id, name, code, fields, formulas, data_driver_path, created_at, updated_at) " +
                    "VALUES (:id, :name, :code, CAST(:fields AS jsonb), CAST('[]' AS jsonb), :ddp, now(), now())")
                    .setParameter("id", componentId)
                    .setParameter("name", tag + "-驱动组件")
                    .setParameter("code", tag + "-" + componentId.toString().substring(0, 8))
                    .setParameter("fields", "[{\"name\":\"" + markerFieldName + "\",\"field_type\":\"INPUT_TEXT\"}]")
                    .setParameter("ddp", "$" + tag.toLowerCase() + "_view")
                    .executeUpdate();

            em.createNativeQuery("INSERT INTO component_sql_view (id, component_id, sql_view_name, sql_template, declared_columns, created_at, updated_at) " +
                    "VALUES (:id, :cid, :vn, :tpl, '[]', now(), now())")
                    .setParameter("id", UUID.randomUUID())
                    .setParameter("cid", componentId)
                    .setParameter("vn", tag.toLowerCase() + "_view")
                    .setParameter("tpl", "SELECT '" + tag + "-P1'::text AS hf_part_no, 'x'::text AS \"" + markerFieldName + "\" WHERE FALSE")
                    .executeUpdate();
        });
        cleanupComponentIds.add(componentId);
        return componentId;
    }

    private UUID buildTemplateRow(UUID componentId, String tag, String markerFieldName) {
        UUID templateId = UUID.randomUUID();
        String snapshot = "[{\"id\":\"" + UUID.randomUUID() + "\",\"componentId\":\"" + componentId +
                "\",\"componentName\":\"" + tag + "-驱动组件\",\"componentCode\":\"" + tag +
                "\",\"componentType\":\"NORMAL\",\"tabName\":\"" + tag + "页签\",\"sortOrder\":0," +
                "\"fields\":[{\"name\":\"" + markerFieldName + "\",\"field_type\":\"INPUT_TEXT\"}],\"formulas\":[]," +
                "\"data_driver_path\":\"$" + tag.toLowerCase() + "_view\"}]";
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("INSERT INTO template (id, template_series_id, name, template_kind, status, components_snapshot, created_at, updated_at) " +
                    "VALUES (:id, :tsid, :name, 'QUOTATION', 'PUBLISHED', CAST(:snap AS jsonb), now(), now())")
                    .setParameter("id", templateId)
                    .setParameter("tsid", UUID.randomUUID())
                    .setParameter("name", tag + "-模板")
                    .setParameter("snap", snapshot)
                    .executeUpdate();
            UUID templateComponentId = UUID.randomUUID();
            em.createNativeQuery("INSERT INTO template_component (id, template_id, component_id, sort_order, tab_name, created_at) " +
                    "VALUES (:id, :tid, :cid, 0, :tab, now())")
                    .setParameter("id", templateComponentId).setParameter("tid", templateId).setParameter("cid", componentId)
                    .setParameter("tab", tag + "页签").executeUpdate();
            // buildCardStructure 的"模板快照损坏"守卫要求 template_component_snapshot 行数与
            // components_snapshot jsonb 数组长度恒相等(均由同一次 publish() 派生) —— 对标
            // MaterialVersionUpgradePrecisionParityTest#insertFrozenTemplateTab 的字段清单。
            em.createNativeQuery("INSERT INTO template_component_snapshot (template_id,template_component_id,component_id," +
                    "sort_order,tab_name,component_name,component_code,component_type,fields,formulas," +
                    "element_code_field,element_price_field,element_currency_field) " +
                    "VALUES (:templateId,:templateComponentId,:componentId,0,:tabName," +
                    ":componentName,:componentCode,'NORMAL',CAST(:fields AS jsonb),CAST('[]' AS jsonb),NULL,NULL,NULL)")
                    .setParameter("templateId", templateId).setParameter("templateComponentId", templateComponentId)
                    .setParameter("componentId", componentId).setParameter("tabName", tag + "页签")
                    .setParameter("componentName", tag + "-驱动组件").setParameter("componentCode", tag)
                    .setParameter("fields", "[{\"name\":\"" + markerFieldName + "\",\"field_type\":\"INPUT_TEXT\"}]")
                    .executeUpdate();
        });
        cleanupTemplateIds.add(templateId);
        return templateId;
    }

    private UUID buildQuotation(UUID templateId, String tag) {
        UUID quotationId = UUID.randomUUID();
        QuarkusTransaction.requiringNew().run(() -> {
            @SuppressWarnings("unchecked")
            List<Object> customers = em.createNativeQuery("SELECT id FROM customer LIMIT 1").getResultList();
            assertFalse(customers.isEmpty(), "DB 无任何 customer,无法建 fixture");
            UUID customerId = toUUID(customers.get(0));
            @SuppressWarnings("unchecked")
            List<Object> users = em.createNativeQuery("SELECT id FROM \"user\" LIMIT 1").getResultList();
            assertFalse(users.isEmpty(), "DB 无任何 user,无法建 fixture");
            UUID salesRepId = toUUID(users.get(0));

            // customer_template_id 与 costing_card_template_id 复用同一模板行,规避"必须两套模板"的额外 fixture 成本 ——
            // ensureStructure 是否要求两者不同不影响本测试要验的"冻结优先于模板实时内容"这条不变量。
            em.createNativeQuery("INSERT INTO quotation (id, quotation_number, customer_id, name, sales_rep_id, status, " +
                    "customer_template_id, costing_card_template_id, tax_rate, tax_amount, created_at, updated_at) " +
                    "VALUES (:id, :qn, :cid, :name, :srid, 'DRAFT', :tid, :tid, 0, 0, now(), now())")
                    .setParameter("id", quotationId)
                    .setParameter("qn", tag + "-" + quotationId.toString().substring(0, 8))
                    .setParameter("cid", customerId)
                    .setParameter("name", tag)
                    .setParameter("srid", salesRepId)
                    .setParameter("tid", templateId)
                    .executeUpdate();
        });
        cleanupQuotationIds.add(quotationId);
        return quotationId;
    }

    private String readQuoteCardStructure(UUID quotationId) {
        Object row = em.createNativeQuery(
                "SELECT structure::text FROM quotation_view_structure WHERE quotation_id = :q AND view_kind = 'QUOTE_CARD'")
                .setParameter("q", quotationId).getSingleResult();
        assertNotNull(row, "QUOTE_CARD 结构应存在(非空验证)");
        return row.toString();
    }

    @Test
    @DisplayName("T-7(AC-10): 结构一旦冻结,后续模板修改不得回灌——重调 ensureStructure 内容保持冻结时刻版本")
    void ensureStructure_contentFrozen_notResyncedFromLiveTemplateAfterMutation() {
        UUID componentId = buildTemplate(TAG, "版本A标记字段");
        UUID templateId = buildTemplateRow(componentId, TAG, "版本A标记字段");
        UUID quotationId = buildQuotation(templateId, TAG + "-Q1");

        // 1) 首次冻结:结构应含"版本A标记字段"
        svc.ensureStructure(quotationId);
        String frozenBefore = readQuoteCardStructure(quotationId);
        assertTrue(frozenBefore.contains("版本A标记字段"),
                "首次冻结应捕获当时模板内容(版本A标记字段),实际=" + frozenBefore);
        assertFalse(frozenBefore.contains("版本B标记字段"),
                "首次冻结时模板还没有版本B标记字段,不应出现");

        // 2) 冻结之后修改模板 components_snapshot 为版本B(模拟组件保存后模板被刷新)
        String snapshotB = "[{\"id\":\"" + UUID.randomUUID() + "\",\"componentId\":\"" + componentId +
                "\",\"componentName\":\"" + TAG + "-驱动组件\",\"componentCode\":\"" + TAG +
                "\",\"componentType\":\"NORMAL\",\"tabName\":\"" + TAG + "页签\",\"sortOrder\":0," +
                "\"fields\":[{\"name\":\"版本B标记字段\",\"field_type\":\"INPUT_TEXT\"}],\"formulas\":[]," +
                "\"data_driver_path\":\"$" + TAG.toLowerCase() + "_view\"}]";
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("UPDATE template SET components_snapshot = CAST(:snap AS jsonb), updated_at = now() WHERE id = :id")
                    .setParameter("snap", snapshotB)
                    .setParameter("id", templateId)
                    .executeUpdate();
        });
        // 独立验证:模板确实已经变成版本B(证明"修改"这一步本身生效,不是空跑)
        Object liveSnapshot = em.createNativeQuery("SELECT components_snapshot::text FROM template WHERE id = :id")
                .setParameter("id", templateId).getSingleResult();
        assertTrue(liveSnapshot.toString().contains("版本B标记字段"),
                "模板应已改为版本B(非空验证,证明改动步骤本身生效)");
        assertFalse(liveSnapshot.toString().contains("版本A标记字段"),
                "模板改为版本B后不应再含版本A标记字段");

        // 3) 重调 ensureStructure —— 核心断言:冻结结构必须仍是版本A,不得被模板的版本B回灌
        svc.ensureStructure(quotationId);
        String frozenAfter = readQuoteCardStructure(quotationId);
        assertTrue(frozenAfter.contains("版本A标记字段"),
                "冻结结构必须仍是版本A(冻结优先于模板实时内容),实际=" + frozenAfter);
        assertFalse(frozenAfter.contains("版本B标记字段"),
                "冻结结构不应含模板事后新增的版本B标记字段——否则说明 ensureStructure 被模板变更" +
                "回灌重建,冻结优先级被破坏(即 b6e86a18 要防的\"同卡双值\"根因重新出现),实际=" + frozenAfter);
        assertEquals(frozenBefore, frozenAfter,
                "冻结结构在模板修改前后应逐字节相同(除非本身就该更新——但 ensureStructure 对已存在" +
                "的结构是幂等跳过,不重建,见 CardStructureSnapshotTest T1)");

        // 4) 结构条数仍为 4(复核既有 CardStructureSnapshotTest 已证明的幂等性未被破坏)
        Number cnt = (Number) em.createNativeQuery(
                "SELECT count(*) FROM quotation_view_structure WHERE quotation_id = :q")
                .setParameter("q", quotationId).getSingleResult();
        assertEquals(4, cnt.intValue(), "重调 ensureStructure 后结构条数应仍为 4(幂等,不重复插入)");
    }
}
