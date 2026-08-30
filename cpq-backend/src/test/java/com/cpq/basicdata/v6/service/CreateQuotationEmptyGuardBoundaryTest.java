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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * repair-260829 · T-05 / T-09 / T-10 / T-11（AC-4 反向 / AC-8 三边界）。
 *
 * <p><b>覆盖什么</b>：B-4/B-5 新加的"① 步空转结果校验"守卫——本文件只验**不该报警的四种情形不误报**：
 * <ul>
 *   <li>T-05：① 步"已成功"的终态（comp_data 非空）——见下方"本文件验的是什么层级"</li>
 *   <li>T-09：明细行 0 条（AC-8-①）</li>
 *   <li>T-10：模板挂 0 个 driver 组件（AC-8-②）——2026-08-29 判据由二元改三元后新增的关键边界，
 *       此边界与 T-04（① 步炸了）终态都是 {@code comp_data==0}，唯一区别是 driver 组件数</li>
 *   <li>T-11：组件视图合法返 0 行，即 {@code WHERE FALSE}（AC-8-③）——comp_data **行存在**，只是内容空</li>
 * </ul>
 *
 * <p><b>本文件验的是什么层级——请勿误读</b>：B-4/B-5 的守卫是**纯后置状态检查**（① 步跑完后查一次
 * {@code count(*)} 比对"明细行>0 且 driver组件>0 且 comp_data==0"三元判据），不关心 comp_data 的内容
 * 是怎么产生的。因此 T-05/T-11 采用**直接预插目标终态**到 {@code quotation_line_component_data}
 * 的手法（与既有测试 {@code EnsureCardValuesPartialBatchRecoveryTest}/
 * {@code CreateQuotationMaterializeWarningsPropagationTest} 同款），只验"守卫看到这个终态时不误报"，
 * **不验①步真实驱动展开本身是否正确**。
 *
 * <p>🚨 **T-05 不是①步端到端验证，绿了不代表①步没问题**——①步的端到端真实性（真实 1845 行单，
 * fire-and-forget，真实驱动展开产出真实数据）由 T-01/T-02/T-03 覆盖，那三条是主线亲验项，不在本文件。
 * 两层各管各的：本文件管"状态之后守卫对不对"，T-01~T-03 管"状态本身是怎么来的"。
 *
 * <p><b>为什么要预插，不能让①步自己产出真实数据——踩坑记录</b>：本文件最初尝试让
 * {@link CreateQuotationMaterializer#materialize} 自己跑①步真实驱动展开产出 comp_data，结果稳定
 * 触发 {@code TemplateNotFrozenException}（"模板尚未冻结：...status=PUBLISHED。该模板的渲染配置
 * 冻结快照为空（过渡期正常状态）"）——只用 {@code template.components_snapshot} 搭的 fixture 模板，
 * 没有经过真正的"冻结"动作，走到 {@code CardSnapshotService.ensureCardValuesDetailed} 内部对
 * {@code PublishedTemplateReader.allTabsOf} 的调用就会失败，被 {@code materialize()} 顶层 catch 兜底
 * 降级为 warning，实际后果是 comp_data 稳定归零（连跑两次、不同随机 UUID，复现一致，排除偶发）。
 * 而既有的两个 precedent 测试**从不依赖①步真实产出**——它们的 fixture 注释写着"quotation_line_
 * component_data 预插空数组快照行——经其验证过对 materialize() 的①②③步均安全"，即直接把终态数据
 * 插进 {@code quotation_line_component_data}，只是没写"为什么"要这样做。这是既有测试基础设施里一个
 * 心照不宣的规避，**不在本次返修范围内，不修**，仅记录以免后来人重踩。
 *
 * <p><b>判据来源</b>：{@code test.md} T-09~T-11 的三元判据（{@code 明细行数>0 且 driver组件数>0 且 comp_data==0}
 * 才报警），{@code commit f907dfa1}（本分支已同步 master，AC-8/B-4/B-5 判据文档为最新版）。
 *
 * <p><b>不覆盖什么</b>：T-04（① 步真的空转、要报警的阳性用例）不在本文件——它需要 B-4 抽出的独立守卫方法
 * （方法签名待后端落地），另开文件补齐，见 test-report.md 的 T-04 记录。
 *
 * <p><b>为什么现在就能写、现在跑也有意义</b>：本文件全部调用既有公开入口
 * {@code CreateQuotationMaterializer#materialize}，不依赖 B-4 抽出的新方法。B-4/B-5 落地前，
 * 守卫逻辑本就不存在，四条用例会"平凡通过"（没有任何告警机制去误报）——这是预期的、无意义的绿；
 * **真正的信号在 B-4/B-5 落地后重跑一次**，届时如果新守卫对这四种合法情形误报，才会由本文件转红。
 * 因此 test-report.md 会记录两轮结果：落地前（基线，预期平凡绿）与落地后（有效绿/红）。
 */
@QuarkusTest
class CreateQuotationEmptyGuardBoundaryTest {

    @Inject EntityManager em;
    @Inject CreateQuotationMaterializer materializer;

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
                em.createNativeQuery("DELETE FROM quotation_view_structure WHERE quotation_id = :q")
                        .setParameter("q", qid).executeUpdate();
                em.createNativeQuery("DELETE FROM quotation_line_component_data WHERE line_item_id IN " +
                        "(SELECT id FROM quotation_line_item WHERE quotation_id = :q)")
                        .setParameter("q", qid).executeUpdate();
                em.createNativeQuery("DELETE FROM quotation_line_item WHERE quotation_id = :q")
                        .setParameter("q", qid).executeUpdate();
                em.createNativeQuery("DELETE FROM quotation WHERE id = :q")
                        .setParameter("q", qid).executeUpdate();
            }
            for (UUID tid : templateIds) {
                em.createNativeQuery("DELETE FROM template_component_snapshot WHERE template_id = :t")
                        .setParameter("t", tid).executeUpdate();
                em.createNativeQuery("DELETE FROM template_component WHERE template_id = :t")
                        .setParameter("t", tid).executeUpdate();
                em.createNativeQuery("DELETE FROM template WHERE id = :t")
                        .setParameter("t", tid).executeUpdate();
            }
            for (UUID cid : componentIds) {
                em.createNativeQuery("DELETE FROM component_sql_view WHERE component_id = :c")
                        .setParameter("c", cid).executeUpdate();
                em.createNativeQuery("DELETE FROM component WHERE id = :c")
                        .setParameter("c", cid).executeUpdate();
            }
        });
    }

    /** 预插模式：NONE=不预插(交给①步/或本就该是0行的场景)；NON_EMPTY=预插非空快照(T-05)；EMPTY_ARRAY=预插空数组(T-11)。 */
    private enum Seed { NONE, NON_EMPTY, EMPTY_ARRAY }

    /** 结果承载：quotationId + 实际建的行数,供断言"现象非空"用。 */
    private record Fixture(UUID quotationId, int lineItemCount, int driverComponentCount, int preSeededCompDataRows) {}

    /**
     * 通用 fixture 构造器。
     *
     * @param tag                 唯一前缀,防止并发/多轮之间撞号
     * @param lineItemCount       建多少行 quotation_line_item(0 = T-09 场景)
     * @param withDriverComponent 是否给模板挂 1 个 driver 组件(false = T-10 场景,模板 0 组件)
     * @param seed                comp_data 预插策略(见 {@link Seed})
     */
    private Fixture buildFixture(String tag, int lineItemCount, boolean withDriverComponent, Seed seed) {
        UUID componentId = null;
        if (withDriverComponent) {
            componentId = UUID.randomUUID();
            final UUID cid = componentId;
            String sql = "SELECT '" + tag + "-P0'::text AS hf_part_no, 'x'::text AS \"名称\" WHERE FALSE";
            QuarkusTransaction.requiringNew().run(() -> {
                em.createNativeQuery("INSERT INTO component (id, name, code, fields, formulas, data_driver_path, created_at, updated_at) " +
                        "VALUES (:id, :name, :code, CAST(:fields AS jsonb), CAST('[]' AS jsonb), :ddp, now(), now())")
                        .setParameter("id", cid)
                        .setParameter("name", tag + "-驱动组件")
                        .setParameter("code", tag + "-" + cid.toString().substring(0, 8))
                        .setParameter("fields", "[{\"name\":\"名称\",\"field_type\":\"INPUT_TEXT\"}]")
                        .setParameter("ddp", "$" + tag.toLowerCase() + "_view")
                        .executeUpdate();
                em.createNativeQuery("INSERT INTO component_sql_view (id, component_id, sql_view_name, sql_template, declared_columns, created_at, updated_at) " +
                        "VALUES (:id, :cid, :vn, :tpl, '[]', now(), now())")
                        .setParameter("id", UUID.randomUUID())
                        .setParameter("cid", cid)
                        .setParameter("vn", tag.toLowerCase() + "_view")
                        .setParameter("tpl", sql)
                        .executeUpdate();
            });
        }
        componentIds.add(componentId); // may be null; cleanup 会跳过对应 no-op

        UUID templateId = UUID.randomUUID();
        final UUID cidFinal = componentId;
        String snapshot = withDriverComponent
                ? "[{\"id\":\"" + UUID.randomUUID() + "\",\"componentId\":\"" + cidFinal +
                  "\",\"componentName\":\"" + tag + "-驱动组件\",\"componentCode\":\"" + tag +
                  "\",\"componentType\":\"NORMAL\",\"tabName\":\"" + tag + "页签\",\"sortOrder\":0," +
                  "\"fields\":[{\"name\":\"名称\",\"field_type\":\"INPUT_TEXT\"}],\"formulas\":[]," +
                  "\"data_driver_path\":\"$" + tag.toLowerCase() + "_view\"}]"
                : "[]";
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("INSERT INTO template (id, template_series_id, name, template_kind, status, components_snapshot, created_at, updated_at) " +
                    "VALUES (:id, :tsid, :name, 'QUOTATION', 'PUBLISHED', CAST(:snap AS jsonb), now(), now())")
                    .setParameter("id", templateId)
                    .setParameter("tsid", UUID.randomUUID())
                    .setParameter("name", tag + "-模板")
                    .setParameter("snap", snapshot)
                    .executeUpdate();
            if (withDriverComponent) {
                UUID templateComponentId = UUID.randomUUID();
                em.createNativeQuery("INSERT INTO template_component (id, template_id, component_id, sort_order, tab_name, created_at) " +
                        "VALUES (:id, :tid, :cid, 0, :tab, now())")
                        .setParameter("id", templateComponentId).setParameter("tid", templateId).setParameter("cid", cidFinal)
                        .setParameter("tab", tag + "页签").executeUpdate();
                em.createNativeQuery("INSERT INTO template_component_snapshot (template_id,template_component_id,component_id," +
                        "sort_order,tab_name,component_name,component_code,component_type,fields,formulas," +
                        "element_code_field,element_price_field,element_currency_field) " +
                        "VALUES (:templateId,:templateComponentId,:componentId,0,:tabName," +
                        ":componentName,:componentCode,'NORMAL',CAST(:fields AS jsonb),CAST('[]' AS jsonb),NULL,NULL,NULL)")
                        .setParameter("templateId", templateId).setParameter("templateComponentId", templateComponentId)
                        .setParameter("componentId", cidFinal).setParameter("tabName", tag + "页签")
                        .setParameter("componentName", tag + "-驱动组件").setParameter("componentCode", tag)
                        .setParameter("fields", "[{\"name\":\"名称\",\"field_type\":\"INPUT_TEXT\"}]")
                        .executeUpdate();
            }
        });
        templateIds.add(templateId);

        UUID quotationId = UUID.randomUUID();
        List<UUID> lineIds = new ArrayList<>();
        QuarkusTransaction.requiringNew().run(() -> {
            @SuppressWarnings("unchecked")
            List<Object> customers = em.createNativeQuery("SELECT id FROM customer LIMIT 1").getResultList();
            assertFalse(customers.isEmpty(), "DB 无任何 customer,无法建 fixture");
            UUID customerId = toUUID(customers.get(0));
            @SuppressWarnings("unchecked")
            List<Object> users = em.createNativeQuery("SELECT id FROM \"user\" LIMIT 1").getResultList();
            assertFalse(users.isEmpty(), "DB 无任何 user,无法建 fixture");
            UUID salesRepId = toUUID(users.get(0));

            em.createNativeQuery("INSERT INTO quotation (id, quotation_number, customer_id, name, sales_rep_id, status, " +
                    "customer_template_id, tax_rate, tax_amount, created_at, updated_at) " +
                    "VALUES (:id, :qn, :cid, :name, :srid, 'DRAFT', :tid, 0, 0, now(), now())")
                    .setParameter("id", quotationId)
                    .setParameter("qn", tag + "-" + quotationId.toString().substring(0, 8))
                    .setParameter("cid", customerId)
                    .setParameter("name", tag)
                    .setParameter("srid", salesRepId)
                    .setParameter("tid", templateId)
                    .executeUpdate();

            for (int i = 0; i < lineItemCount; i++) {
                UUID lid = UUID.randomUUID();
                em.createNativeQuery("INSERT INTO quotation_line_item (id, quotation_id, template_id, " +
                        "product_part_no_snapshot, sort_order, created_at) VALUES (:id, :qid, :tid, :pn, :so, now())")
                        .setParameter("id", lid).setParameter("qid", quotationId).setParameter("tid", templateId)
                        .setParameter("pn", tag + "-P" + i).setParameter("so", i)
                        .executeUpdate();
                lineIds.add(lid);
            }
        });
        quotationIds.add(quotationId);

        int preSeeded = 0;
        if (seed != Seed.NONE && withDriverComponent && !lineIds.isEmpty()) {
            String snapshotRowsJson = seed == Seed.NON_EMPTY
                    ? "[{\"名称\":\"seed-value\"}]"
                    : "[]";
            final UUID cid2 = componentId;
            final String tab = tag + "页签";
            for (UUID lid : lineIds) {
                QuarkusTransaction.requiringNew().run(() -> {
                    em.createNativeQuery("INSERT INTO quotation_line_component_data (id, line_item_id, component_id, tab_name, " +
                            "row_data, snapshot_rows) VALUES (:id, :lid, :cid, :tab, '[]', CAST(:sr AS jsonb))")
                            .setParameter("id", UUID.randomUUID()).setParameter("lid", lid).setParameter("cid", cid2)
                            .setParameter("tab", tab).setParameter("sr", snapshotRowsJson)
                            .executeUpdate();
                });
                preSeeded++;
            }
        }

        int driverCompCount = withDriverComponent ? 1 : 0;
        return new Fixture(quotationId, lineItemCount, driverCompCount, preSeeded);
    }

    private long countLineItems(UUID qid) {
        Number n = (Number) em.createNativeQuery(
                "SELECT count(*) FROM quotation_line_item WHERE quotation_id = :q")
                .setParameter("q", qid).getSingleResult();
        return n.longValue();
    }

    private long countTemplateComponents(UUID templateId) {
        Number n = (Number) em.createNativeQuery(
                "SELECT count(*) FROM template_component WHERE template_id = :t")
                .setParameter("t", templateId).getSingleResult();
        return n.longValue();
    }

    private long countCompData(UUID qid) {
        Number n = (Number) em.createNativeQuery(
                "SELECT count(*) FROM quotation_line_component_data d " +
                "JOIN quotation_line_item li ON li.id = d.line_item_id WHERE li.quotation_id = :q")
                .setParameter("q", qid).getSingleResult();
        return n.longValue();
    }

    private long countCompDataNonEmptySnapshot(UUID qid) {
        Number n = (Number) em.createNativeQuery(
                "SELECT count(*) FROM quotation_line_component_data d " +
                "JOIN quotation_line_item li ON li.id = d.line_item_id " +
                "WHERE li.quotation_id = :q AND jsonb_array_length(COALESCE(d.snapshot_rows,'[]'::jsonb)) > 0")
                .setParameter("q", qid).getSingleResult();
        return n.longValue();
    }

    /** 与 T-2/gap2 系列同款：JUL Handler 挂根 logger 拦截 SEVERE(=JBoss Logging ERROR)记录。 */
    private List<LogRecord> captureSevereDuring(Runnable action) {
        List<LogRecord> captured = new CopyOnWriteArrayList<>();
        Handler handler = new Handler() {
            @Override public void publish(LogRecord record) {
                if (record.getLevel().intValue() >= Level.SEVERE.intValue()) captured.add(record);
            }
            @Override public void flush() {}
            @Override public void close() {}
        };
        Logger root = Logger.getLogger("");
        root.addHandler(handler);
        try {
            action.run();
        } finally {
            root.removeHandler(handler);
        }
        return captured;
    }

    /** 宽松匹配"组件数据 0 行"类文案——后端 B-4 落地前后文案未定,先用关键词兜住。 */
    private static final Pattern EMPTY_WARNING_KEYWORDS = Pattern.compile("组件数据.*0\\s*行|comp[_-]?data.*(0|zero)|driver.*(0|empty|空)");

    private void assertNoFalsePositive(V6QuotationCommitService.CommitResult r, List<LogRecord> severeLogs, UUID qid, String caseTag) {
        System.out.printf("[%s] warnings=%s severeCount=%d%n", caseTag, r.warnings, severeLogs.size());
        for (String w : r.warnings) {
            assertFalse(EMPTY_WARNING_KEYWORDS.matcher(w).find(),
                    "[" + caseTag + "] 本场景是合法边界,不应触发'组件数据0行'类告警,实际 warnings=" + r.warnings);
        }
        for (LogRecord rec : severeLogs) {
            String msg = String.valueOf(rec.getMessage());
            assertFalse(msg.contains(qid.toString()) && EMPTY_WARNING_KEYWORDS.matcher(msg).find(),
                    "[" + caseTag + "] 本场景不应有 ERROR 级'组件数据0行'类日志,实际=" + msg);
        }
    }

    @Test
    @DisplayName("T-05(AC-4反向,守卫状态验证非①步端到端): comp_data非空终态不误报")
    void t05_normalSuccess_noFalsePositive() {
        Fixture f = buildFixture("T05GUARD", 3, true, Seed.NON_EMPTY);
        assertEquals(3, f.lineItemCount(), "前置数据非空:应建 3 行 line item");
        assertEquals(1, f.driverComponentCount(), "前置数据非空:模板应挂 1 个 driver 组件");
        assertEquals(3, f.preSeededCompDataRows(), "前置数据非空:应预插 3 行 comp_data(非空快照)");
        // 现象非空:预插后立刻查一次,确认真的插进去了(不是"预插动作跑了但没生效")
        assertEquals(3, countCompData(f.quotationId()), "预插后 DB 层确认: comp_data 应为 3 行");
        assertEquals(3, countCompDataNonEmptySnapshot(f.quotationId()), "预插后 DB 层确认: 3 行都应非空快照");

        V6QuotationCommitService.CommitResult r =
                new V6QuotationCommitService.CommitResult(f.quotationId(), UUID.randomUUID(), f.lineItemCount());
        List<LogRecord> severe = captureSevereDuring(() -> materializer.materialize(r));

        long compData = countCompData(f.quotationId());
        long nonEmpty = countCompDataNonEmptySnapshot(f.quotationId());
        System.out.printf("[T05] compData=%d nonEmpty=%d%n", compData, nonEmpty);
        assertEquals(3, compData, "①步跑完后 comp_data 终态应仍为 3 行(非空,守卫应视为成功)");
        assertEquals(3, nonEmpty, "①步跑完后 3 行的 snapshot_rows 都应仍非空");

        assertNoFalsePositive(r, severe, f.quotationId(), "T05");
    }

    @Test
    @DisplayName("T-09(AC-8-①): 明细行0条不误报")
    void t09_zeroLineItems_noFalsePositive() {
        Fixture f = buildFixture("T09GUARD", 0, true, Seed.NONE);
        assertEquals(0, countLineItems(f.quotationId()), "前置条件确认:明细行确为 0 条");

        V6QuotationCommitService.CommitResult r =
                new V6QuotationCommitService.CommitResult(f.quotationId(), UUID.randomUUID(), 0);
        List<LogRecord> severe = captureSevereDuring(() -> materializer.materialize(r));

        assertEquals(0, countCompData(f.quotationId()), "0 行明细,comp_data 理应为 0(合法态)");
        assertNoFalsePositive(r, severe, f.quotationId(), "T09");
    }

    @Test
    @DisplayName("T-10(AC-8-②): 模板挂0个driver组件不误报——判据三元化新增的关键边界")
    void t10_zeroDriverComponents_noFalsePositive() {
        Fixture f = buildFixture("T10GUARD", 3, false, Seed.NONE);
        assertEquals(3, f.lineItemCount(), "前置数据非空:应建 3 行 line item");
        assertEquals(0, f.driverComponentCount(), "前置条件确认:模板应挂 0 个 driver 组件");
        UUID resolvedTemplateId = toUUID(
                em.createNativeQuery("SELECT customer_template_id FROM quotation WHERE id = :q")
                        .setParameter("q", f.quotationId()).getSingleResult());
        assertEquals(0, countTemplateComponents(resolvedTemplateId),
                "DB 层再次确认: template_component 表里该模板确实 0 行");

        V6QuotationCommitService.CommitResult r =
                new V6QuotationCommitService.CommitResult(f.quotationId(), UUID.randomUUID(), f.lineItemCount());
        List<LogRecord> severe = captureSevereDuring(() -> materializer.materialize(r));

        assertEquals(0, countCompData(f.quotationId()),
                "明细行>0 但模板 0 driver 组件,comp_data 理应为 0(0 组件 × N 行 = 0,合法态,不是缺陷)");
        assertNoFalsePositive(r, severe, f.quotationId(), "T10");
    }

    @Test
    @DisplayName("T-11(AC-8-③): 组件视图合法返0行(WHERE FALSE)——comp_data行存在且snapshot_rows为空,不误报")
    void t11_legitimateEmptyView_rowExistsNoFalsePositive() {
        Fixture f = buildFixture("T11GUARD", 3, true, Seed.EMPTY_ARRAY);
        assertEquals(3, f.lineItemCount(), "前置数据非空:应建 3 行 line item");
        assertEquals(1, f.driverComponentCount(), "前置数据非空:模板应挂 1 个 driver 组件");
        assertEquals(3, f.preSeededCompDataRows(), "前置数据非空:应预插 3 行 comp_data(空快照)");
        // 现象非空(关键区分点提前到预插后就确认): 行存在,不是没有记录
        assertEquals(3, countCompData(f.quotationId()), "预插后 DB 层确认: comp_data **行数**应=3(行存在)");
        assertEquals(0, countCompDataNonEmptySnapshot(f.quotationId()), "预插后 DB 层确认: 3 行的 snapshot_rows 都应为空数组");

        V6QuotationCommitService.CommitResult r =
                new V6QuotationCommitService.CommitResult(f.quotationId(), UUID.randomUUID(), f.lineItemCount());
        List<LogRecord> severe = captureSevereDuring(() -> materializer.materialize(r));

        long compData = countCompData(f.quotationId());
        long nonEmpty = countCompDataNonEmptySnapshot(f.quotationId());
        System.out.printf("[T11] compData=%d nonEmpty=%d%n", compData, nonEmpty);
        assertEquals(3, compData,
                "①步跑完后关键区分点仍应成立: comp_data **行数**=3(行存在),不是 0——WHERE FALSE 是'视图合法返回" +
                "0条结果行',不是'没有comp_data记录'。若这里变成 0,说明①步把预插行删掉了,fixture 不再代表 T-11 要的状态");
        assertEquals(0, nonEmpty, "①步跑完后 3 行的 snapshot_rows 仍应为空数组(视图确实返回0行内容)");

        assertNoFalsePositive(r, severe, f.quotationId(), "T11");
    }
}
