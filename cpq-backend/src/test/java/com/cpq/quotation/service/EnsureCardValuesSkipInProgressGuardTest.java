package com.cpq.quotation.service;

import com.cpq.basicdata.v6.service.MaterializeRegistry;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * repair-260829（卡片值算早了骨架值锁死）· T-17（B-1b 自锁防护 + 绕过口子不被悄悄扩大）。
 *
 * <p><b>覆盖什么</b>：B-1b 加了 {@code ensureCardValuesDetailed(qid, force, skipInProgressGuard)}
 * 3 参重载——{@code CreateQuotationMaterializer.materialize} 自身③步调用时传 {@code true}，
 * 绕过"物化进行中"守卫（否则会被自己刚 {@code begin()} 的标志拦死，物化永远算不出东西）。
 * 本文件验两件事：① 行为——{@code skipInProgressGuard=true} 时即便 registry 标记进行中也能
 * 正常算出结果，2 参重载（守卫生效）此时仍会被拦住；② 反射——全工程只应有 1 处调用点传
 * {@code true}，防止后人随手加一个 {@code true} 把守卫绕过去而不自知（同类"反射式断言防悄悄
 * 扩大绕过口子"的先例见 {@code PricingSheetRegistry} 相关测试）。
 */
@QuarkusTest
class EnsureCardValuesSkipInProgressGuardTest {

    private static final String TAG = "T260829T17";

    @Inject EntityManager em;
    @Inject CardSnapshotService cardSnapshotService;
    @Inject MaterializeRegistry registry;

    private UUID componentId, templateId, quotationId, lineId;

    private static UUID toUUID(Object o) {
        return (o instanceof UUID u) ? u : UUID.fromString(o.toString());
    }

    @AfterEach
    void cleanup() {
        if (quotationId != null) registry.end(quotationId); // 保险丝
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

    private void buildHealthyFixture() {
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
                    .setParameter("tpl", "SELECT '" + TAG + "-P1'::text AS hf_part_no, '绕过守卫'::text AS \"名称\"")
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
            em.createNativeQuery("INSERT INTO quotation_line_item (id, quotation_id, template_id, " +
                    "product_part_no_snapshot, sort_order, created_at) VALUES (:id, :qid, :tid, :pn, 0, now())")
                    .setParameter("id", lineId).setParameter("qid", quotationId).setParameter("tid", templateId)
                    .setParameter("pn", TAG + "-P0").executeUpdate();
            em.createNativeQuery("INSERT INTO quotation_line_component_data (id, line_item_id, component_id, tab_name, " +
                    "row_data, snapshot_rows) VALUES (:id, :lid, :cid, :tab, CAST(:rd AS jsonb), CAST(:sr AS jsonb))")
                    .setParameter("id", UUID.randomUUID()).setParameter("lid", lineId).setParameter("cid", componentId)
                    .setParameter("tab", TAG + "页签")
                    .setParameter("rd", "[{\"row_index\":0,\"名称\":\"绕过守卫\"}]")
                    .setParameter("sr", "[{\"driverRow\":{\"hf_part_no\":\"" + TAG + "-P1\",\"名称\":\"绕过守卫\"}," +
                            "\"basicDataValues\":{}}]")
                    .executeUpdate();
        });
    }

    private String readQuoteCardValues() {
        Object v = em.createNativeQuery("SELECT quote_card_values::text FROM quotation_line_item WHERE id = :id")
                .setParameter("id", lineId).getSingleResult();
        return v == null ? null : v.toString();
    }

    @Test
    @DisplayName("T-17-a: registry进行中时,skipInProgressGuard=true能正常算出;2参重载(守卫生效)仍被拦")
    void skipGuardTrue_bypassesInProgressLock_whilePlainOverloadStaysBlocked() {
        buildHealthyFixture();
        registry.begin(quotationId);
        assertTrue(registry.isInProgress(quotationId), "前置条件确认: registry 应标记为进行中");

        // 2参重载(守卫生效): 应被拦住,返回 WARMING_IN_PROGRESS,不落库。
        CardSnapshotService.EnsureResult guarded = cardSnapshotService.ensureCardValuesDetailed(quotationId, false);
        assertEquals(CardSnapshotService.WARMING_IN_PROGRESS, guarded.computed,
                "2参重载(守卫生效)在registry进行中时应返回WARMING_IN_PROGRESS");
        assertNull(readQuoteCardValues(), "2参重载被拦住,不应落库");

        // 3参重载 skipInProgressGuard=true(物化自身③步的调用方式): 应正常算出,不被同一标志拦死。
        CardSnapshotService.EnsureResult bypassed =
                cardSnapshotService.ensureCardValuesDetailed(quotationId, false, true);
        assertEquals(1, bypassed.computed,
                "skipInProgressGuard=true 时应正常补算1行(不是空跑),不应被自己刚begin的标志拦死");
        String cv = readQuoteCardValues();
        assertNotNull(cv, "skipInProgressGuard=true 应正常落库");
        assertTrue(cv.contains("绕过守卫"), "应包含真实预插内容,实际=" + cv);

        System.out.printf("[T-17-a] guarded.computed=%d(WARMING) bypassed.computed=%d(正常) quote_card_values=%s%n",
                guarded.computed, bypassed.computed, cv);
    }

    // ── 反射式断言:全工程只应有1处调用点给 skipInProgressGuard 传 true ──

    /** 与 QuotePendingScopeOpenWhitelistTest#srcMainJavaRoot 同款:向上查找 cpq-backend/src/main/java。 */
    private static Path srcMainJavaRoot() {
        Path dir = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 8 && dir != null; i++) {
            Path candidate = dir.resolve("cpq-backend/src/main/java");
            if (Files.isDirectory(candidate)) return candidate;
            if ("cpq-backend".equals(String.valueOf(dir.getFileName()))) {
                candidate = dir.resolve("src/main/java");
                if (Files.isDirectory(candidate)) return candidate;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException(
                "无法从当前工作目录定位 cpq-backend/src/main/java：cwd=" + Paths.get("").toAbsolutePath());
    }

    /**
     * 精确匹配"3参调用且末参数字面量为 true"——即
     * {@code ensureCardValuesDetailed(quotationId, forceRecomputeAll, true)} 这种形态。
     *
     * <p>🚨 首跑实测踩过的坑：最初的正则只要求"以 ,true) 收尾"，未限定参数个数，
     * 结果把 {@code ensureCardValuesDetailed(id, true)}（2参重载，{@code true} 是
     * {@code forceRecomputeAll}，与本条要防的 {@code skipInProgressGuard} 完全是两回事）
     * 也算命中，误报 {@code CostingFreezeService}/{@code QuotationService} 两处 2 参调用为
     * "绕过守卫"——两者语义不同，必须靠"是否有 3 个逗号分隔的参数"精确区分，不能只看结尾字面量。
     * 本正则要求恰好 2 个逗号（3 段参数），且各段本身不含逗号/括号（本工程内该方法所有真实
     * 调用点的实参都是简单标识符/字面量，未出现嵌套调用，此假设经实测验证成立）。
     */
    private static final Pattern THREE_ARG_TRUE_CALL = Pattern.compile(
            "ensureCardValuesDetailed\\s*\\(\\s*[^,()]+\\s*,\\s*[^,()]+\\s*,\\s*true\\s*\\)");

    @Test
    @DisplayName("T-17-b(反射防护): 全工程只应有1处调用点给 ensureCardValuesDetailed 的 skipInProgressGuard 传 true")
    void onlyOneCallSitePassesSkipGuardTrue() throws IOException {
        Path root = srcMainJavaRoot();
        List<String> hits = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(p -> p.toString().endsWith(".java")).forEach(p -> {
                try {
                    String content = Files.readString(p);
                    Matcher m = THREE_ARG_TRUE_CALL.matcher(content);
                    while (m.find()) {
                        String call = m.group().replaceAll("\\s+", " ");
                        // 排除方法定义本身(参数声明形如 "boolean skipInProgressGuard)" 不含字面量 true,
                        // 正则已要求以 ",true)" 收尾,方法签名声明不会匹配,这里仅兜底防误伤重载定义处的调用转发。
                        hits.add(p + " :: " + call);
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }

        System.out.printf("[T-17-b] 全工程 ensureCardValuesDetailed(...,true) 调用点:%n%s%n",
                String.join("\n", hits));
        assertEquals(1, hits.size(),
                "全工程应恰好1处调用点给 skipInProgressGuard 传 true(仅 CreateQuotationMaterializer 物化③步自身)," +
                "实际命中=" + hits.size() + "：" + hits);
        assertTrue(hits.get(0).contains("CreateQuotationMaterializer.java"),
                "唯一的传true调用点应在 CreateQuotationMaterializer.java,实际=" + hits.get(0));
    }
}
