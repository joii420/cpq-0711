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
 * repair-260829（卡片值算早了骨架值锁死）· T-03（AC-3 自愈）。
 *
 * <p><b>本文件能证明什么、不能证明什么——如实登记，不要只看绿灯</b>：
 * <ul>
 *   <li>T-05（{@code CardSnapshotEarlySkeletonGuardTest}）已经单测证明：{@code isEarlySkeletonRender}
 *       命中时，B-1 会跳过 {@code assignQuoteCardValues}，让该行 {@code quote_card_values}
 *       <b>保持读入时的原值</b>（对 {@code ensureCardValues(qid, false)} 的正常调用路径而言，
 *       "读入时的原值"就是 {@code IS NULL} 谓词刚选出来的 NULL——即守卫命中后该行仍是 NULL，
 *       不会被写死成骨架）。</li>
 *   <li>本文件补的是另外半句：<b>一行 {@code quote_card_values} 为 NULL、且其 comp_data 已有
 *       真实非空 {@code snapshot_rows} 时，{@code ensureCardValues} 会正确把它算出非空结果</b>——
 *       这正是"IS NULL 判据"本身未被 B-1/B-2 破坏、依然对 NULL 行生效的证据。两条拼起来
 *       （守卫命中→保持NULL 且 NULL+真实数据→正确算出非空）在逻辑上共同保证了 AC-3 要求的
 *       "自愈"：一行不会因为被守卫拦过一次就永远算不出来。</li>
 *   <li><b>本文件不能、也没有尝试端到端真实触发 B-1 守卫本身</b>——判据收窄后（见
 *       {@code CardSnapshotEarlySkeletonGuardTest} 类注释），触发条件依赖 Pass1(build) 与
 *       Pass1.5(comp_data 预载) 两次读取之间恰好跨越一次外部并发提交，单线程测试内无法构造
 *       这种数据分叉，这与 AC-4 需要"并发多发"才能验证是同一个结构性原因（主线在还原实验里
 *       验证并发场景，不在本文件重复造轮子）。</li>
 * </ul>
 *
 * <p><b>造数手法</b>：仿 {@code CardValuesRecomputeStableTest}——直接预插
 * {@code quotation_line_component_data.snapshot_rows} 为真实非空内容（不依赖①步 expand，
 * 该路径在隔离 fixture 下不可靠，见该文件类注释里记录的已知限制），组件对应 SQL 视图同样
 * 返回真实一行（保证"若走完整链路重新展开，结果与预插一致"，避免造出一个渲染管线压根
 * 触碰不到的假数据）。
 */
@QuarkusTest
class CardSnapshotSkeletonSelfHealTest {

    private static final String TAG = "T260829T3";

    @Inject EntityManager em;
    @Inject CardSnapshotService cardSnapshotService;

    private UUID componentId, templateId, quotationId, lineId;

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
                em.createNativeQuery("DELETE FROM template_component_snapshot WHERE template_id = :t")
                        .setParameter("t", templateId).executeUpdate();
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

    private void buildFixtureWithRealDriverRowAndNullCardValues() {
        componentId = UUID.randomUUID();
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("INSERT INTO component (id, name, code, fields, formulas, data_driver_path, created_at, updated_at) " +
                    "VALUES (:id, :name, :code, CAST(:fields AS jsonb), CAST('[]' AS jsonb), :ddp, now(), now())")
                    .setParameter("id", componentId).setParameter("name", TAG + "-驱动组件")
                    .setParameter("code", TAG + "-" + componentId.toString().substring(0, 8))
                    .setParameter("fields", "[{\"name\":\"名称\",\"field_type\":\"INPUT_TEXT\"}]")
                    .setParameter("ddp", "$" + TAG.toLowerCase() + "_view").executeUpdate();
            em.createNativeQuery("INSERT INTO component_sql_view (id, component_id, sql_view_name, sql_template, declared_columns, created_at, updated_at) " +
                    "VALUES (:id, :cid, :vn, :tpl, '[]', now(), now())")
                    .setParameter("id", UUID.randomUUID()).setParameter("cid", componentId)
                    .setParameter("vn", TAG.toLowerCase() + "_view")
                    .setParameter("tpl", "SELECT '" + TAG + "-P1'::text AS hf_part_no, '自愈真实值'::text AS \"名称\"")
                    .executeUpdate();
        });

        templateId = UUID.randomUUID();
        String snapshot = "[{\"id\":\"" + UUID.randomUUID() + "\",\"componentId\":\"" + componentId +
                "\",\"componentName\":\"" + TAG + "-驱动组件\",\"componentCode\":\"" + TAG +
                "\",\"componentType\":\"NORMAL\",\"tabName\":\"" + TAG + "页签\",\"sortOrder\":0," +
                "\"fields\":[{\"name\":\"名称\",\"field_type\":\"INPUT_TEXT\"}],\"formulas\":[]," +
                "\"data_driver_path\":\"$" + TAG.toLowerCase() + "_view\"}]";
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("INSERT INTO template (id, template_series_id, name, template_kind, status, components_snapshot, created_at, updated_at) " +
                    "VALUES (:id, :tsid, :name, 'QUOTATION', 'PUBLISHED', CAST(:snap AS jsonb), now(), now())")
                    .setParameter("id", templateId).setParameter("tsid", UUID.randomUUID())
                    .setParameter("name", TAG + "-模板").setParameter("snap", snapshot).executeUpdate();
            UUID tcId = UUID.randomUUID();
            em.createNativeQuery("INSERT INTO template_component (id, template_id, component_id, sort_order, tab_name, created_at) " +
                    "VALUES (:id, :tid, :cid, 0, :tab, now())")
                    .setParameter("id", tcId).setParameter("tid", templateId).setParameter("cid", componentId)
                    .setParameter("tab", TAG + "页签").executeUpdate();
            em.createNativeQuery("INSERT INTO template_component_snapshot (template_id,template_component_id,component_id," +
                    "sort_order,tab_name,component_name,component_code,component_type,fields,formulas," +
                    "element_code_field,element_price_field,element_currency_field) " +
                    "VALUES (:templateId,:templateComponentId,:componentId,0,:tabName," +
                    ":componentName,:componentCode,'NORMAL',CAST(:fields AS jsonb),CAST('[]' AS jsonb),NULL,NULL,NULL)")
                    .setParameter("templateId", templateId).setParameter("templateComponentId", tcId)
                    .setParameter("componentId", componentId).setParameter("tabName", TAG + "页签")
                    .setParameter("componentName", TAG + "-驱动组件").setParameter("componentCode", TAG)
                    .setParameter("fields", "[{\"name\":\"名称\",\"field_type\":\"INPUT_TEXT\"}]")
                    .executeUpdate();
        });

        quotationId = UUID.randomUUID();
        lineId = UUID.randomUUID();
        QuarkusTransaction.requiringNew().run(() -> {
            UUID customerId = toUUID(em.createNativeQuery("SELECT id FROM customer LIMIT 1").getResultList().get(0));
            UUID salesRepId = toUUID(em.createNativeQuery("SELECT id FROM \"user\" LIMIT 1").getResultList().get(0));
            em.createNativeQuery("INSERT INTO quotation (id, quotation_number, customer_id, name, sales_rep_id, status, " +
                    "customer_template_id, tax_rate, tax_amount, created_at, updated_at) " +
                    "VALUES (:id, :qn, :cid, :name, :srid, 'DRAFT', :tid, 0, 0, now(), now())")
                    .setParameter("id", quotationId).setParameter("qn", TAG + "-" + quotationId.toString().substring(0, 8))
                    .setParameter("cid", customerId).setParameter("name", TAG).setParameter("srid", salesRepId)
                    .setParameter("tid", templateId).executeUpdate();
            // quote_card_values 不显式赋值 → 落库默认 NULL(未算过)。
            em.createNativeQuery("INSERT INTO quotation_line_item (id, quotation_id, template_id, " +
                    "product_part_no_snapshot, sort_order, created_at) VALUES (:id, :qid, :tid, :pn, 0, now())")
                    .setParameter("id", lineId).setParameter("qid", quotationId).setParameter("tid", templateId)
                    .setParameter("pn", TAG + "-P0").executeUpdate();
            // 预插真实非空 snapshot_rows(仿 CardValuesRecomputeStableTest,绕开 expand 路径的已知限制)。
            em.createNativeQuery("INSERT INTO quotation_line_component_data (id, line_item_id, component_id, tab_name, " +
                    "row_data, snapshot_rows) VALUES (:id, :lid, :cid, :tab, CAST(:rd AS jsonb), CAST(:sr AS jsonb))")
                    .setParameter("id", UUID.randomUUID()).setParameter("lid", lineId).setParameter("cid", componentId)
                    .setParameter("tab", TAG + "页签")
                    .setParameter("rd", "[{\"row_index\":0,\"名称\":\"自愈真实值\"}]")
                    .setParameter("sr", "[{\"driverRow\":{\"hf_part_no\":\"" + TAG + "-P1\",\"名称\":\"自愈真实值\"}," +
                            "\"basicDataValues\":{}}]")
                    .executeUpdate();
        });
    }

    private String readQuoteCardValues() {
        Object v = em.createNativeQuery("SELECT quote_card_values::text FROM quotation_line_item WHERE id = :id")
                .setParameter("id", lineId).getSingleResult();
        return v == null ? null : v.toString();
    }

    private int countNonEmptyBaseRows(String cardValuesJson) throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var root = mapper.readTree(cardValuesJson);
        int total = 0;
        for (var tab : root.path("tabs")) {
            var base = tab.path("baseRows");
            if (base.isArray()) total += base.size();
        }
        return total;
    }

    @Test
    @DisplayName("T-03(AC-3 自愈): quote_card_values=NULL + comp_data 真实非空 → ensureCardValues 正确算出非空结果(非骨架)")
    void nullCardValuesWithRealSourceData_getsComputedNonEmpty() throws Exception {
        buildFixtureWithRealDriverRowAndNullCardValues();

        // 前置数据非空验证:开跑前确实是 NULL(不是"本来就有值,断言在空跑")
        assertNull(readQuoteCardValues(), "开跑前 quote_card_values 应为 NULL(未算过)");

        int filled1 = cardSnapshotService.ensureCardValues(quotationId);
        assertEquals(1, filled1, "应补算 1 行(非空验证,不是空跑)");

        String cv1 = readQuoteCardValues();
        assertNotNull(cv1, "算完后 quote_card_values 不应为 NULL");
        assertFalse(cv1.contains("__cardValueFailed"), "配置合法,不应落失败哨兵,实际=" + cv1);
        int baseRowsCount1 = countNonEmptyBaseRows(cv1);
        assertTrue(baseRowsCount1 > 0, "comp_data 有真实数据时,算出的 baseRows 合计应 > 0(不是骨架),实际=" + cv1);
        assertTrue(cv1.contains("自愈真实值"), "算出结果应包含预插的真实内容,实际=" + cv1);

        // 第二轮:模拟"该行卡片值因任意原因被重置为 NULL(不依赖具体原因——可能是 B-1 守卫保持原值,
        // 也可能是运维清空)"后,底层真实数据未变,ensureCardValues 应能再次正确算出非空结果——
        // 证明"NULL + 真实数据"这条自愈路径是稳定可重复的,不是偶然算对一次。
        QuarkusTransaction.requiringNew().run(() ->
                em.createNativeQuery("UPDATE quotation_line_item SET quote_card_values = NULL WHERE id = :id")
                        .setParameter("id", lineId).executeUpdate());
        em.clear();

        int filled2 = cardSnapshotService.ensureCardValues(quotationId);
        assertEquals(1, filled2, "第二次清空后 ensureCardValues 应仍补算 1 行(IS NULL 判据未被破坏)");
        String cv2 = readQuoteCardValues();
        assertNotNull(cv2, "第二次算完后 quote_card_values 不应为 NULL");
        int baseRowsCount2 = countNonEmptyBaseRows(cv2);
        assertTrue(baseRowsCount2 > 0, "第二次重算 baseRows 合计仍应 > 0,不再被永久锁死为骨架,实际=" + cv2);

        System.out.printf("[T-03] quotation=%s 两轮 baseRows合计=%d/%d(均>0,自愈路径稳定)%n",
                quotationId, baseRowsCount1, baseRowsCount2);
    }
}
