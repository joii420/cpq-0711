package com.cpq.quotation.service;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * repair-260828 · T-1（AC-4）：{@code assignQuoteCardValues} 对
 * {@code quotation_line_component_data} 的逐行回查不再随行数 N 线性增长（根因 A）。
 *
 * <p><b>前置数据形态是本测试的关键</b>（主线原话，f.1）：夹具必须刻意构造成
 * <b>「行全部无 componentData」</b>——即 {@code quotation_line_component_data} 表里
 * 对这批 {@code quotation_line_item} 一条记录都没有（不是"有记录但字段为空数组"）。
 * 这才是生产单 {@code 10ca17fb}（1845/1845 行无 componentData）的真实触发形态；用
 * 预先塞入空数组占位行的夹具（既有 D-3 测试的手法）会绕开 groupingBy 的空 Map 场景，
 * 测了个寂寞——已实测验证：占位行夹具在改动前后均恒定 exec=1，不构成有效探针。
 *
 * <p><b>判据发现过程（黑盒探测，未读实现代码）</b>：Hibernate {@link Statistics#getQueries()}
 * 对 Panache 动态查询按 <b>HQL 文本</b>（非最终 SQL）分桶。逐行回查的真实签名是
 * {@code FROM `com.cpq.quotation.entity.QuotationLineComponentData` WHERE lineItemId = ?1}
 * （单参数、无 IN），与预载用的整单 IN 查询是<b>两条不同文本</b>——前者按 SQL 文本前缀
 * "select ...quotation_line_component_data" 过滤会完全漏掉（HQL 文本以 "FROM" 开头，
 * 不是 "select"）。black-box 探测确认：改动前该 HQL 文本的执行次数恰好 == N（N=5/20/100
 * 各自实测 5/20/100），改动后（backend 当前 WIP）无论 N=5/20/100 该文本执行次数恒为 0
 * （彻底消失，不只是"降低"）。本测试据此写死判据。
 *
 * <p><b>还原实验</b>：用 {@code git stash} 只暂存
 * {@code CardSnapshotService.java} 这一个文件（不影响本测试文件本身），重跑本测试观察是否变红，
 * 验证后 {@code git stash pop} 复原——过程与结论见 test-report.md，不写在本文件里。
 */
@QuarkusTest
class CardSnapshotComponentDataPreloadNPlusOneTest {

    @Inject EntityManager em;
    @Inject CardSnapshotService cardSnapshotService;

    private final List<UUID> quotationIds = new ArrayList<>();
    private final List<UUID> templateIds = new ArrayList<>();
    private final List<UUID> componentIds = new ArrayList<>();

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
            for (UUID c : componentIds) {
                em.createNativeQuery("DELETE FROM component_sql_view WHERE component_id = :c").setParameter("c", c).executeUpdate();
                em.createNativeQuery("DELETE FROM component WHERE id = :c").setParameter("c", c).executeUpdate();
            }
        });
        quotationIds.clear();
        templateIds.clear();
        componentIds.clear();
    }

    /** PUBLISHED 模板 + template_component_snapshot（对齐 EnsureCardValuesPartialBatchRecoveryTest 已验证手法——
     *  DRAFT 状态会短路 TemplateRenderScope，逐行回查压根不会被触发，测了个寂寞）。
     *  view 返回 0 行（$view WHERE FALSE，对齐既有 D-3 fixture 手法）。
     *  quotation_line_item 全部**不插入** quotation_line_component_data —— 根因 A 的真实触发形态。 */
    private UUID buildQuotationNoComponentData(int n, String tag) {
        UUID componentId = UUID.randomUUID();
        componentIds.add(componentId);
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("INSERT INTO component (id, name, code, fields, formulas, data_driver_path, created_at, updated_at) " +
                    "VALUES (:id, :name, :code, CAST(:fields AS jsonb), CAST('[]' AS jsonb), :ddp, now(), now())")
                    .setParameter("id", componentId).setParameter("name", tag + "-驱动组件")
                    .setParameter("code", tag + "-" + componentId.toString().substring(0, 8))
                    .setParameter("fields", "[{\"name\":\"名称\",\"field_type\":\"INPUT_TEXT\"}]")
                    .setParameter("ddp", "$" + tag.toLowerCase() + "_view").executeUpdate();
            em.createNativeQuery("INSERT INTO component_sql_view (id, component_id, sql_view_name, sql_template, declared_columns, created_at, updated_at) " +
                    "VALUES (:id, :cid, :vn, :tpl, '[]', now(), now())")
                    .setParameter("id", UUID.randomUUID()).setParameter("cid", componentId)
                    .setParameter("vn", tag.toLowerCase() + "_view")
                    .setParameter("tpl", "SELECT '" + tag + "-P1'::text AS hf_part_no, 'x'::text AS \"名称\" WHERE FALSE")
                    .executeUpdate();
        });

        UUID templateId = UUID.randomUUID();
        templateIds.add(templateId);
        String snapshot = "[{\"id\":\"" + UUID.randomUUID() + "\",\"componentId\":\"" + componentId +
                "\",\"componentName\":\"" + tag + "-驱动组件\",\"componentCode\":\"" + tag +
                "\",\"componentType\":\"NORMAL\",\"tabName\":\"" + tag + "页签\",\"sortOrder\":0," +
                "\"fields\":[{\"name\":\"名称\",\"field_type\":\"INPUT_TEXT\"}],\"formulas\":[]," +
                "\"data_driver_path\":\"$" + tag.toLowerCase() + "_view\"}]";
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("INSERT INTO template (id, template_series_id, name, template_kind, status, components_snapshot, created_at, updated_at) " +
                    "VALUES (:id, :tsid, :name, 'QUOTATION', 'PUBLISHED', CAST(:snap AS jsonb), now(), now())")
                    .setParameter("id", templateId).setParameter("tsid", UUID.randomUUID())
                    .setParameter("name", tag + "-模板").setParameter("snap", snapshot).executeUpdate();
            UUID tcId = UUID.randomUUID();
            em.createNativeQuery("INSERT INTO template_component (id, template_id, component_id, sort_order, tab_name, created_at) " +
                    "VALUES (:id, :tid, :cid, 0, :tab, now())")
                    .setParameter("id", tcId).setParameter("tid", templateId).setParameter("cid", componentId)
                    .setParameter("tab", tag + "页签").executeUpdate();
            em.createNativeQuery("INSERT INTO template_component_snapshot (template_id,template_component_id,component_id," +
                    "sort_order,tab_name,component_name,component_code,component_type,fields,formulas," +
                    "element_code_field,element_price_field,element_currency_field) " +
                    "VALUES (:templateId,:templateComponentId,:componentId,0,:tabName," +
                    ":componentName,:componentCode,'NORMAL',CAST(:fields AS jsonb),CAST('[]' AS jsonb),NULL,NULL,NULL)")
                    .setParameter("templateId", templateId).setParameter("templateComponentId", tcId)
                    .setParameter("componentId", componentId).setParameter("tabName", tag + "页签")
                    .setParameter("componentName", tag + "-驱动组件").setParameter("componentCode", tag)
                    .setParameter("fields", "[{\"name\":\"名称\",\"field_type\":\"INPUT_TEXT\"}]")
                    .executeUpdate();
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
            for (int i = 0; i < n; i++) {
                UUID lid = UUID.randomUUID();
                em.createNativeQuery("INSERT INTO quotation_line_item (id, quotation_id, template_id, " +
                        "product_part_no_snapshot, sort_order, created_at) VALUES (:id, :qid, :tid, :pn, :so, now())")
                        .setParameter("id", lid).setParameter("qid", quotationId).setParameter("tid", templateId)
                        .setParameter("pn", tag + "-P" + i).setParameter("so", i).executeUpdate();
                // 刻意不插入 quotation_line_component_data —— 与生产单 10ca17fb 的真实形态一致
            }
        });
        quotationIds.add(quotationId);
        return quotationId;
    }

    private Statistics stats() {
        Statistics st = em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        st.setStatisticsEnabled(true);
        return st;
    }

    /** 逐行回查的 HQL 签名（单参数 lineItemId = ?1，非 IN 列表）执行次数——根因 A 的直接判据。 */
    private long perLineFallbackExecCount(Statistics st) {
        long total = 0;
        for (String q : st.getQueries()) {
            if (q.contains("QuotationLineComponentData") && q.contains("lineItemId = ?1")) {
                long c = st.getQueryStatistics(q).getExecutionCount();
                total += c;
                System.out.printf("  [per-line-fallback] exec=%d hql=%s%n", c, q);
            }
        }
        return total;
    }

    @Test
    @DisplayName("T-1(AC-4): assignQuoteCardValues 逐行回查 quotation_line_component_data 的 HQL 执行次数不随 N 线性增长")
    void perLineFallback_notLinearInN() {
        UUID qid300 = buildQuotationNoComponentData(300, "T260828T1N300");
        Statistics st = stats();
        st.clear();
        int filled300 = cardSnapshotService.ensureCardValues(qid300);
        long fallback300 = perLineFallbackExecCount(st);
        assertEquals(300, filled300, "前置数据非空:N=300 应补算 300 行(不是空跑)");

        UUID qid1845 = buildQuotationNoComponentData(1845, "T260828T1N1845");
        st.clear();
        int filled1845 = cardSnapshotService.ensureCardValues(qid1845);
        long fallback1845 = perLineFallbackExecCount(st);
        assertEquals(1845, filled1845, "前置数据非空:N=1845 应补算 1845 行(不是空跑)");

        System.out.printf("[T-1 AC-4] N=300 fallbackExec=%d | N=1845 fallbackExec=%d | " +
                "判据:后者不应约等于前者*6.15(=1845/300，逐行调用的直接证据)%n", fallback300, fallback1845);

        // 推荐口径(问题说明.md AC-4)：每批 <=2 条。chunk 默认 300 → N=300 是 1 批(阈值2)，
        // N=1845 是 7 批(阈值14)。
        assertTrue(fallback300 <= 2, "N=300 时逐行回查 HQL 执行次数应<=2(每批<=2条的推荐口径),实测=" + fallback300);
        assertTrue(fallback1845 <= 14, "N=1845 时逐行回查 HQL 执行次数应<=14(7批*2),实测=" + fallback1845);
        // 真正的 N+1 判据:线性增长的直接证据是 fallback1845 ≈ fallback300 * (1845/300) ≈ fallback300*6.15。
        // 只要 fallback1845 明显小于该值(这里用 /2 打个宽松折扣)就足以证伪"逐行调用"。
        assertTrue(fallback1845 < fallback300 * 3 + 10,
                "N 从 300 增至 1845(6.15倍),逐行回查执行次数不应等比例增长" +
                "(N+1 的特征是 fallback≈N;实测 fallback300=" + fallback300 + " fallback1845=" + fallback1845 + ")");
    }
}
