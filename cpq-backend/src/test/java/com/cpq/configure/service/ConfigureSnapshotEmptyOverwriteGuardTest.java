package com.cpq.configure.service;

import com.cpq.component.entity.Component;
import com.cpq.component.entity.ComponentSqlView;
import com.cpq.quotation.service.CardSnapshotService;
import com.cpq.template.entity.Template;
import com.cpq.template.entity.TemplateComponent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * repair-0729 Task3 · T7（可选项，coordinator 返修消息第 3 条）——
 * 复制报价单后，某 driver 组件 {@code snapshot_rows} 为 NULL 时的"整行 all-or-nothing 重 expand"
 * 是否会误伤同行其它已继承的正常组件；验证 {@code ConfigureSnapshotService.snapshotLines}（Task2 §2.2）
 * 的空覆盖护栏是否真的兜住了。
 *
 * <p><b>触发机制</b>：{@link ConfigureSnapshotService#lineNeedsExpand} 是整行判定——只要该行任一
 * driver 组件的 {@code snapshot_rows} 缺失/为 NULL，整行都会重新 expand（见同包既有单测
 * {@code SnapshotLineNeedsExpandTest}，纯函数级已覆盖该判定逻辑本身）。这意味着：即便 copy() 已经
 * 把某组件（compGood）的 {@code snapshot_rows} 正确整份继承下来，只要同一行另一个组件（compNull）
 * 的 {@code snapshot_rows} 是 NULL（例如源单该组件历史上从未成功展开过），下一次 saveDraft 增量物化
 * （{@code snapshotQuotation(quotationId, skipRowsWithSnapshot=true)}）就会把 compGood 也一并重新
 * expand —— 若新单在 pending 可见域下该组件的 $view 查询结果恰好是 0 行（本 bug 根因 A 的同款场景：
 * 新单 id 下查不到源单的私有 pending 数据），compGood 继承下来的正确值就会被空结果悄悄抹掉，除非
 * Task2 §2.2 的护栏拦下来。
 *
 * <p><b>本用例只测 ConfigureSnapshotService 侧的护栏</b>（区别于 {@code RefreshQuoteCardValuesEmptyOverwriteGuardTest}
 * 测的是 CardSnapshotService 侧），直接构造"compNull 快照为 NULL + compGood 快照非空但其 $view 本次
 * 查询返回 0 行"的行，调用真实的 {@code snapshotQuotation(id, true)}（与 saveDraft 增量热路径同一入口），
 * 断言 compGood 的值原样保留、compNull 被正常 expand（不再是 NULL）、计数器 +1。
 */
@QuarkusTest
@DisplayName("ConfigureSnapshotEmptyOverwriteGuardTest — T7: 同行一组件 snapshot_rows=NULL 触发整行重 expand 时，护栏保住其它组件已继承的非空值")
class ConfigureSnapshotEmptyOverwriteGuardTest {

    private static final ObjectMapper M = new ObjectMapper();
    private static final String TAG = "T0729T7";

    @Inject EntityManager em;
    @Inject ConfigureSnapshotService configureSnapshotService;

    private UUID compNullId, compGoodId, nullViewId, goodViewId, templateId, tcNullId, tcGoodId,
            quotationId, lineItemId;

    @AfterEach
    void cleanup() {
        QuarkusTransaction.requiringNew().run(() -> {
            if (lineItemId != null) {
                em.createNativeQuery("DELETE FROM quotation_line_component_data WHERE line_item_id = :id")
                        .setParameter("id", lineItemId).executeUpdate();
                em.createNativeQuery("DELETE FROM quotation_line_item WHERE id = :id")
                        .setParameter("id", lineItemId).executeUpdate();
            }
            if (quotationId != null) {
                em.createNativeQuery("DELETE FROM quotation WHERE id = :id")
                        .setParameter("id", quotationId).executeUpdate();
            }
            if (tcNullId != null) em.createNativeQuery("DELETE FROM template_component WHERE id = :id").setParameter("id", tcNullId).executeUpdate();
            if (tcGoodId != null) em.createNativeQuery("DELETE FROM template_component WHERE id = :id").setParameter("id", tcGoodId).executeUpdate();
            if (templateId != null) em.createNativeQuery("DELETE FROM template WHERE id = :id").setParameter("id", templateId).executeUpdate();
            if (nullViewId != null) em.createNativeQuery("DELETE FROM component_sql_view WHERE id = :id").setParameter("id", nullViewId).executeUpdate();
            if (goodViewId != null) em.createNativeQuery("DELETE FROM component_sql_view WHERE id = :id").setParameter("id", goodViewId).executeUpdate();
            if (compNullId != null) em.createNativeQuery("DELETE FROM component WHERE id = :id").setParameter("id", compNullId).executeUpdate();
            if (compGoodId != null) em.createNativeQuery("DELETE FROM component WHERE id = :id").setParameter("id", compGoodId).executeUpdate();
            em.createNativeQuery("DELETE FROM component WHERE code LIKE :p").setParameter("p", TAG + "%").executeUpdate();
            em.createNativeQuery("DELETE FROM template WHERE name LIKE :p").setParameter("p", TAG + "%").executeUpdate();
        });
    }

    private static UUID toUUID(Object o) {
        if (o instanceof UUID u) return u;
        return UUID.fromString(o.toString());
    }

    /**
     * 建 fixture：2 个 flat 驱动组件（同一模板同一行）——
     * compNull：snapshot_rows=NULL（触发整行 all-or-nothing），$view 本次也返回 0 行（无所谓，反正本来就 NULL）；
     * compGood：snapshot_rows=已继承的非空 marker，$view 本次返回 0 行（模拟 copy 后新单 pending 域查不到 → 根因 A 同款）。
     */
    private void buildFixture() {
        QuarkusTransaction.requiringNew().run(() -> {
            Component compNull = new Component();
            compNull.name = TAG + "-空快照组件";
            compNull.code = TAG + "-NULL-" + UUID.randomUUID().toString().substring(0, 8);
            compNull.fields = "[{\"name\":\"名称\",\"field_type\":\"INPUT_TEXT\"}]";
            compNull.formulas = "[]";
            compNull.dataDriverPath = "$" + TAG.toLowerCase() + "_null_view";
            compNull.persist();
            compNullId = compNull.id;

            ComponentSqlView nullView = new ComponentSqlView();
            nullView.componentId = compNull.id;
            nullView.sqlViewName = compNull.dataDriverPath.substring(1);
            nullView.sqlTemplate = "SELECT '" + TAG + "-P1'::text AS hf_part_no, 'x'::text AS \"名称\" WHERE FALSE";
            nullView.declaredColumns = "[]";
            nullView.persist();
            nullViewId = nullView.id;

            Component compGood = new Component();
            compGood.name = TAG + "-已继承组件";
            compGood.code = TAG + "-GOOD-" + UUID.randomUUID().toString().substring(0, 8);
            compGood.fields = "[{\"name\":\"名称\",\"field_type\":\"INPUT_TEXT\"}]";
            compGood.formulas = "[]";
            compGood.dataDriverPath = "$" + TAG.toLowerCase() + "_good_view";
            compGood.persist();
            compGoodId = compGood.id;

            ComponentSqlView goodView = new ComponentSqlView();
            goodView.componentId = compGood.id;
            goodView.sqlViewName = compGood.dataDriverPath.substring(1);
            // 本次重查同样返回 0 行——模拟 copy 后新单在其 pending 可见域下查不到源单私有 pending 数据
            // （本 bug 根因 A 的同款场景），但 compGood 已经从源单继承了非空 snapshot_rows。
            goodView.sqlTemplate = "SELECT '" + TAG + "-P1'::text AS hf_part_no, 'x'::text AS \"名称\" WHERE FALSE";
            goodView.declaredColumns = "[]";
            goodView.persist();
            goodViewId = goodView.id;

            Template tpl = new Template();
            tpl.templateSeriesId = UUID.randomUUID();
            tpl.name = TAG + "-模板";
            tpl.templateKind = "QUOTATION";
            tpl.status = "DRAFT";
            tpl.createdAt = OffsetDateTime.now();
            tpl.updatedAt = OffsetDateTime.now();
            tpl.persist();
            templateId = tpl.id;

            TemplateComponent tcNull = new TemplateComponent();
            tcNull.templateId = tpl.id;
            tcNull.componentId = compNull.id;
            tcNull.tabName = "空快照页签";
            tcNull.createdAt = OffsetDateTime.now();
            tcNull.persist();
            tcNullId = tcNull.id;

            TemplateComponent tcGood = new TemplateComponent();
            tcGood.templateId = tpl.id;
            tcGood.componentId = compGood.id;
            tcGood.tabName = "已继承页签";
            tcGood.createdAt = OffsetDateTime.now();
            tcGood.persist();
            tcGoodId = tcGood.id;

            try {
                com.fasterxml.jackson.databind.node.ArrayNode snapshot = M.createArrayNode();
                com.fasterxml.jackson.databind.node.ObjectNode nullEntry = snapshot.addObject();
                nullEntry.put("id", tcNull.id.toString());
                nullEntry.put("componentId", compNull.id.toString());
                nullEntry.put("componentName", compNull.name);
                nullEntry.put("componentCode", compNull.code);
                nullEntry.put("componentType", "NORMAL");
                nullEntry.put("tabName", "空快照页签");
                nullEntry.put("sortOrder", 0);
                nullEntry.set("fields", M.readTree(compNull.fields));
                nullEntry.set("formulas", M.readTree(compNull.formulas));
                nullEntry.put("data_driver_path", compNull.dataDriverPath);

                com.fasterxml.jackson.databind.node.ObjectNode goodEntry = snapshot.addObject();
                goodEntry.put("id", tcGood.id.toString());
                goodEntry.put("componentId", compGood.id.toString());
                goodEntry.put("componentName", compGood.name);
                goodEntry.put("componentCode", compGood.code);
                goodEntry.put("componentType", "NORMAL");
                goodEntry.put("tabName", "已继承页签");
                goodEntry.put("sortOrder", 1);
                goodEntry.set("fields", M.readTree(compGood.fields));
                goodEntry.set("formulas", M.readTree(compGood.formulas));
                goodEntry.put("data_driver_path", compGood.dataDriverPath);

                tpl.componentsSnapshot = M.writeValueAsString(snapshot);
                tpl.persist();
            } catch (Exception e) {
                throw new RuntimeException("构造 template.components_snapshot 失败", e);
            }

            @SuppressWarnings("unchecked")
            List<Object> customers = em.createNativeQuery("SELECT id FROM customer LIMIT 1").getResultList();
            assertFalse(customers.isEmpty(), "DB 无任何 customer,无法建报价单 fixture");
            UUID customerId = toUUID(customers.get(0));
            @SuppressWarnings("unchecked")
            List<Object> users = em.createNativeQuery("SELECT id FROM \"user\" LIMIT 1").getResultList();
            assertFalse(users.isEmpty(), "DB 无任何 user,无法建报价单 fixture");
            UUID salesRepId = toUUID(users.get(0));

            quotationId = UUID.randomUUID();
            em.createNativeQuery(
                    "INSERT INTO quotation (id, quotation_number, customer_id, name, sales_rep_id, status, " +
                    " customer_template_id, tax_rate, tax_amount, created_at, updated_at) " +
                    "VALUES (:id, :qn, :cid, :name, :srid, 'DRAFT', :tid, 0, 0, now(), now())")
                    .setParameter("id", quotationId)
                    .setParameter("qn", TAG + "-" + quotationId.toString().substring(0, 8))
                    .setParameter("cid", customerId)
                    .setParameter("name", TAG + "-测试报价单(模拟 copy 后的新单)")
                    .setParameter("srid", salesRepId)
                    .setParameter("tid", templateId)
                    .executeUpdate();

            lineItemId = UUID.randomUUID();
            em.createNativeQuery(
                    "INSERT INTO quotation_line_item (id, quotation_id, template_id, product_part_no_snapshot, " +
                    " sort_order, created_at, card_snapshot_at) VALUES (:id, :qid, :tid, :pn, 0, now(), now())")
                    .setParameter("id", lineItemId)
                    .setParameter("qid", quotationId)
                    .setParameter("tid", templateId)
                    .setParameter("pn", TAG + "-P1")
                    .executeUpdate();

            // compNull：snapshot_rows = NULL（模拟"源单该组件历史上从未成功展开过"，继承后仍是 NULL）
            em.createNativeQuery(
                    "INSERT INTO quotation_line_component_data (id, line_item_id, component_id, tab_name, " +
                    " row_data, snapshot_rows) VALUES (:id, :lid, :cid, :tab, '[]', NULL)")
                    .setParameter("id", UUID.randomUUID())
                    .setParameter("lid", lineItemId)
                    .setParameter("cid", compNullId)
                    .setParameter("tab", "空快照页签")
                    .executeUpdate();

            // compGood：snapshot_rows = 已继承的非空 marker（模拟 copy() 值快照整份继承下来的正确值）
            em.createNativeQuery(
                    "INSERT INTO quotation_line_component_data (id, line_item_id, component_id, tab_name, " +
                    " row_data, snapshot_rows) VALUES (:id, :lid, :cid, :tab, '[]', CAST(:rows AS jsonb))")
                    .setParameter("id", UUID.randomUUID())
                    .setParameter("lid", lineItemId)
                    .setParameter("cid", compGoodId)
                    .setParameter("tab", "已继承页签")
                    .setParameter("rows", "[{\"driverRow\":{\"名称\":\"OLD_GOOD_VALUE\"},\"basicDataValues\":{}}]")
                    .executeUpdate();
        });
    }

    private String readSnapshotRows(UUID componentId) {
        return QuarkusTransaction.requiringNew().call(() -> {
            @SuppressWarnings("unchecked")
            List<Object> r = em.createNativeQuery(
                    "SELECT snapshot_rows::text FROM quotation_line_component_data " +
                    "WHERE line_item_id = :lid AND component_id = :cid")
                    .setParameter("lid", lineItemId).setParameter("cid", componentId)
                    .getResultList();
            return r.isEmpty() ? null : (String) r.get(0);
        });
    }

    @Test
    @DisplayName("T7: compNull.snapshot_rows=NULL 触发整行重 expand → compGood(已继承非空值,本次$view查0行) 被护栏保住,不被抹空")
    void snapshotQuotation_incremental_guardsInheritedValueWhenSiblingNullTriggersWholeLineExpand() {
        buildFixture();

        long blockedBefore = CardSnapshotService.EMPTY_OVERWRITE_BLOCKED_COUNT.get();

        // 与 saveDraft 增量热路径同一入口：skipRowsWithSnapshot=true。
        // compNull.snapshot_rows=NULL → lineNeedsExpand 判定整行需要重 expand（SnapshotLineNeedsExpandTest
        // 已单测覆盖该纯函数本身，这里验证的是它触发后，写入路径的空覆盖护栏是否真的生效）。
        configureSnapshotService.snapshotQuotation(quotationId, true);

        long blockedAfter = CardSnapshotService.EMPTY_OVERWRITE_BLOCKED_COUNT.get();
        assertTrue(blockedAfter > blockedBefore,
                "compGood 本次重查 0 行 + 旧值非空 → 护栏应至少拦截一次(compGood)，" +
                        "before=" + blockedBefore + " after=" + blockedAfter);

        // compGood 的旧值必须原样保留，不能被 compNull 触发的整行重 expand 连带抹空
        String goodRowsJson = readSnapshotRows(compGoodId);
        assertNotNull(goodRowsJson, "compGood.snapshot_rows 不应被抹成 NULL");
        try {
            JsonNode arr = M.readTree(goodRowsJson);
            assertTrue(arr.isArray() && arr.size() == 1,
                    "compGood.snapshot_rows 应仍是继承下来的 1 行，实际=" + goodRowsJson);
            assertEquals("OLD_GOOD_VALUE", arr.get(0).path("driverRow").path("名称").asText(""),
                    "compGood 保留的应是继承的旧值内容(OLD_GOOD_VALUE)，证明真的是护栏拦截而非巧合");
        } catch (Exception e) {
            fail("compGood.snapshot_rows 应是合法 JSON: " + goodRowsJson, e);
        }

        // compNull 应该真的被 expand 过了（不再是 NULL——即便 $view 本次也是 0 行，合法空数组 "[]" ≠ NULL），
        // 证明整行重 expand 确实执行了（不是因为异常提前中断而巧合保留原状）。
        String nullRowsJson = readSnapshotRows(compNullId);
        assertNotNull(nullRowsJson, "compNull.snapshot_rows 应已被真正 expand 过(不再是 NULL)，证明整行重 expand 确实执行了");
    }
}
