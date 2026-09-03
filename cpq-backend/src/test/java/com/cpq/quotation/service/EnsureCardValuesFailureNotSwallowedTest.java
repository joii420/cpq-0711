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
 * task-260825 大单量导入建单性能 —— T-3（AC-5 · 守 E-6 · 降级路径未被吞掉）。
 *
 * <p><b>验的是什么</b>：物化真失败时不许被"为了让 AC-2 变绿"而吞掉——失败必须<b>可观测</b>，
 * 不能表现为"悄悄落一个看起来正常的值"。
 *
 * <p><b>为什么不直接测"接口 cardValuesReady:false + warnings 含原文"</b>（AC-5 原文字面意思）：
 * 那是 {@code CreateQuotationMaterializer.materialize()} / {@code V6QuotationCommitService.createQuotation()}
 * 编排层的响应契约，本任务只拿到了 {@code CardSnapshotService} 与 {@code QuotationLineItemMaterializeService}
 * 两层的公开签名（主线给定），编排层方法签名未给出，本任务规则禁止读 {@code cpq-backend/src/main/}
 * 去反查。改为在<b>降级机制真正落地的那一层</b>——{@code CardSnapshotService#ensureCardValues} 的
 * 失败哨兵（{@code __cardValueFailed}，主线在派工单里给出的常量原文，非本测试猜测）——验证：
 * <ol>
 *   <li>失败<b>不是</b>静默丢弃：受影响行的 {@code quote_card_values} 非 NULL 且含哨兵标记
 *       （对标已有 {@code EnsureCardValuesTest#failed_row_writes_sentinel_not_null} 的单行断言，
 *       本测试把它<b>扩展到多行</b>，验证"降级不是只对第一行生效、后面几行被漏报"）</li>
 *   <li>失败不会被误判为"部分成功"再重复无限重算（幂等：二次调用返回 0）</li>
 * </ol>
 *
 * <p><b>已知覆盖缺口</b>（如实登记，见 test-report.md）：AC-5 原文"接口仍返回 cardValuesReady: false
 * 且 warnings 含失败原文"这句里"接口"层面的字段传播，本测试<b>未覆盖</b>——需要
 * {@code CreateQuotationMaterializer}/{@code V6QuotationCommitService.createQuotation} 的编排层
 * 签名（未给出）。建议主线在真机亲验-1/AC-1 时一并核对该字段传播，或后续补给编排层签名。
 */
@QuarkusTest
class EnsureCardValuesFailureNotSwallowedTest {

    private static final String TAG = "T260825T3";
    private static final String SENTINEL = "__cardValueFailed";

    @Inject EntityManager em;
    @Inject CardSnapshotService svc;

    private UUID componentId, templateId, quotationId;
    private final List<UUID> lineIds = new ArrayList<>();

    private static UUID toUUID(Object o) {
        return (o instanceof UUID u) ? u : UUID.fromString(o.toString());
    }

    @AfterEach
    void cleanup() {
        if (quotationId == null && templateId == null && componentId == null) return;
        QuarkusTransaction.requiringNew().run(() -> {
            if (quotationId != null) {
                em.createNativeQuery("DELETE FROM quotation_view_structure WHERE quotation_id = :q")
                        .setParameter("q", quotationId).executeUpdate();
                em.createNativeQuery("DELETE FROM quotation_line_component_data WHERE line_item_id IN " +
                        "(SELECT id FROM quotation_line_item WHERE quotation_id = :q)")
                        .setParameter("q", quotationId).executeUpdate();
                em.createNativeQuery("DELETE FROM quotation_line_item WHERE quotation_id = :q")
                        .setParameter("q", quotationId).executeUpdate();
                em.createNativeQuery("DELETE FROM quotation WHERE id = :q")
                        .setParameter("q", quotationId).executeUpdate();
            }
            if (templateId != null) {
                em.createNativeQuery("DELETE FROM template_component WHERE template_id = :t")
                        .setParameter("t", templateId).executeUpdate();
                em.createNativeQuery("DELETE FROM template WHERE id = :t")
                        .setParameter("t", templateId).executeUpdate();
            }
            if (componentId != null) {
                em.createNativeQuery("DELETE FROM component_sql_view WHERE component_id = :c")
                        .setParameter("c", componentId).executeUpdate();
                em.createNativeQuery("DELETE FROM component WHERE id = :c")
                        .setParameter("c", componentId).executeUpdate();
            }
        });
    }

    /** 4 行报价单，customer_template_id 从建单起就是 NULL —— 对标 EnsureCardValuesTest 已证明的
     *  确定性失败手法（buildCardValues 命中 templateId==null 守卫 → 报价侧全部落哨兵），
     *  差别在于本测试用<b>自建隔离夹具</b>而非共享 rockwell 单，规避并发会话互相影响。 */
    private void buildFixtureWithNullCustomerTemplate() {
        componentId = UUID.randomUUID();
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("INSERT INTO component (id, name, code, fields, formulas, data_driver_path, created_at, updated_at) " +
                    "VALUES (:id, :name, :code, CAST(:fields AS jsonb), CAST('[]' AS jsonb), :ddp, now(), now())")
                    .setParameter("id", componentId)
                    .setParameter("name", TAG + "-驱动组件")
                    .setParameter("code", TAG + "-" + componentId.toString().substring(0, 8))
                    .setParameter("fields", "[{\"name\":\"名称\",\"field_type\":\"INPUT_TEXT\"}]")
                    .setParameter("ddp", "$" + TAG.toLowerCase() + "_view")
                    .executeUpdate();
            em.createNativeQuery("INSERT INTO component_sql_view (id, component_id, sql_view_name, sql_template, declared_columns, created_at, updated_at) " +
                    "VALUES (:id, :cid, :vn, :tpl, '[]', now(), now())")
                    .setParameter("id", UUID.randomUUID())
                    .setParameter("cid", componentId)
                    .setParameter("vn", TAG.toLowerCase() + "_view")
                    .setParameter("tpl", "SELECT '" + TAG + "-P1'::text AS hf_part_no, 'x'::text AS \"名称\" WHERE FALSE")
                    .executeUpdate();
        });

        templateId = UUID.randomUUID();
        String snapshot = "[{\"id\":\"" + UUID.randomUUID() + "\",\"componentId\":\"" + componentId +
                "\",\"componentName\":\"" + TAG + "-驱动组件\",\"componentCode\":\"" + TAG +
                "\",\"componentType\":\"NORMAL\",\"tabName\":\"" + TAG + "页签\",\"sortOrder\":0," +
                "\"fields\":[{\"name\":\"名称\",\"field_type\":\"INPUT_TEXT\"}],\"formulas\":[]," +
                "\"data_driver_path\":\"$" + TAG.toLowerCase() + "_view\"}]";
        QuarkusTransaction.requiringNew().run(() -> {
            // 注:template_id 仍需要一个真实存在的模板行来满足 quotation_line_item.template_id 的 FK 约束，
            // 但 quotation.customer_template_id 才是 buildCardValues 实际读取、决定成败的字段——两者故意分开。
            em.createNativeQuery("INSERT INTO template (id, template_series_id, name, template_kind, status, components_snapshot, created_at, updated_at) " +
                    "VALUES (:id, :tsid, :name, 'QUOTATION', 'PUBLISHED', CAST(:snap AS jsonb), now(), now())")
                    .setParameter("id", templateId)
                    .setParameter("tsid", UUID.randomUUID())
                    .setParameter("name", TAG + "-模板")
                    .setParameter("snap", snapshot)
                    .executeUpdate();
            em.createNativeQuery("INSERT INTO template_component (id, template_id, component_id, tab_name, created_at) " +
                    "VALUES (:id, :tid, :cid, :tab, now())")
                    .setParameter("id", UUID.randomUUID()).setParameter("tid", templateId).setParameter("cid", componentId)
                    .setParameter("tab", TAG + "页签").executeUpdate();
        });

        quotationId = UUID.randomUUID();
        QuarkusTransaction.requiringNew().run(() -> {
            @SuppressWarnings("unchecked")
            List<Object> customers = em.createNativeQuery("SELECT id FROM customer LIMIT 1").getResultList();
            assertFalse(customers.isEmpty(), "DB 无任何 customer,无法建 fixture");
            UUID customerId = toUUID(customers.get(0));
            @SuppressWarnings("unchecked")
            List<Object> users = em.createNativeQuery("SELECT id FROM \"user\" LIMIT 1").getResultList();
            assertFalse(users.isEmpty(), "DB 无任何 user,无法建 fixture");
            UUID salesRepId = toUUID(users.get(0));

            // customer_template_id 故意留 NULL —— 制造确定性失败(EnsureCardValuesTest 已证明的手法)
            em.createNativeQuery("INSERT INTO quotation (id, quotation_number, customer_id, name, sales_rep_id, status, " +
                    "customer_template_id, tax_rate, tax_amount, created_at, updated_at) " +
                    "VALUES (:id, :qn, :cid, :name, :srid, 'DRAFT', NULL, 0, 0, now(), now())")
                    .setParameter("id", quotationId)
                    .setParameter("qn", TAG + "-" + quotationId.toString().substring(0, 8))
                    .setParameter("cid", customerId)
                    .setParameter("name", TAG)
                    .setParameter("srid", salesRepId)
                    .executeUpdate();

            for (int i = 0; i < 4; i++) {
                UUID lid = UUID.randomUUID();
                em.createNativeQuery("INSERT INTO quotation_line_item (id, quotation_id, template_id, " +
                        "product_part_no_snapshot, sort_order, created_at) VALUES (:id, :qid, :tid, :pn, :so, now())")
                        .setParameter("id", lid).setParameter("qid", quotationId).setParameter("tid", templateId)
                        .setParameter("pn", TAG + "-P" + i).setParameter("so", i)
                        .executeUpdate();
                lineIds.add(lid);
            }
        });
    }

    @Test
    @DisplayName("T-3(AC-5): customer_template_id 缺失导致的必然失败 —— 全部受影响行落哨兵,不静默丢弃,不无限重算")
    void deterministicFailure_writesSentinel_forEveryAffectedRow_notSwallowed() {
        buildFixtureWithNullCustomerTemplate();
        assertEquals(4, lineIds.size(), "前置数据非空:应建出 4 行 line item");

        // 1) 第一次 ensureCardValues:应补算(尝试补算)全部 4 行
        int filled = svc.ensureCardValues(quotationId);
        assertEquals(4, filled, "ensureCardValues 应尝试处理全部 4 行缺失(即便结果是失败落哨兵,也计入'已处理')");

        // 2) 断言:全部 4 行 quote_card_values 非 NULL 且含哨兵 —— 降级路径未被吞掉,不是"悄悄不报"
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                "SELECT id, quote_card_values::text FROM quotation_line_item WHERE quotation_id = :q ORDER BY sort_order")
                .setParameter("q", quotationId).getResultList();
        assertEquals(4, rows.size(), "查询应返回全部 4 行(非空验证)");

        int sentinelCount = 0;
        for (Object[] r : rows) {
            UUID lid = (UUID) r[0];
            String qcv = (String) r[1];
            assertNotNull(qcv, "line=" + lid + " quote_card_values 不应为 NULL(应落哨兵,不是静默留空)");
            if (qcv.contains(SENTINEL)) sentinelCount++;
        }
        assertEquals(4, sentinelCount,
                "全部 4 行都应落哨兵(证明失败对该报价单下每一行都可观测,不是只报第一行、" +
                "后面几行被漏报或误判成功)");

        // 3) 幂等:二次调用应为 0(哨兵行不再被 IS NULL 谓词重选,不会无限重算同一个必然失败的行)
        int second = svc.ensureCardValues(quotationId);
        assertEquals(0, second, "第二次 ensureCardValues 应为 0(哨兵行不应被重选,避免对必然失败的" +
                "配置无限重算浪费资源)");

        System.out.printf("[T-3] quotation=%s 4行全部落哨兵=%b 二次调用=%d%n", quotationId, sentinelCount == 4, second);
    }
}
