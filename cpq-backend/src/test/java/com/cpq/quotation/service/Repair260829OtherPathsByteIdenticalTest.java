package com.cpq.quotation.service;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.MessageDigest;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * repair-260829（卡片值算早了骨架值锁死）· T-08（AC-8，其它路径逐位不变）。
 *
 * <p><b>怎么做 A/B 对比，为什么不用 git stash</b>：backend-engineer 正在同一个 worktree 里改
 * {@code CardSnapshotService.java}/{@code CreateQuotationMaterializer.java}，{@code git stash}
 * 会直接冲突/污染对方在飞的编辑。改用主线已批准的方案——另开一个临时 {@code git worktree}
 * checkout 到返修前的基线提交（{@code cf76bb8e}，已确认含 V396 迁移，不会卡 Flyway 校验），
 * 把本文件<b>原样复制</b>过去跑一遍拿"改动前"的输出，再在当前 worktree 跑一遍"改动后"，
 * 逐行 diff 两次的 stdout（含 MD5）。临时 worktree 跑完即删，过程与结论见 test-report.md。
 *
 * <p><b>为什么用固定 UUID 而非随机</b>：两次运行发生在两个不同的 worktree 进程里，只有约定同一组
 * 固定 UUID 才能让"改动前"与"改动后"操作的是同一份可比对数据（各自跑完各自清理，不会互相冲突，
 * 因为是先后串行跑，不是同时跑）。
 *
 * <p><b>覆盖范围（如实登记，不超范围认领）</b>：B-1/B-1b 改动集中在 {@code CardSnapshotService}
 * 的 {@code ensureCardValuesDetailed}（含 {@code ensureCardValues}/{@code ensureExcelValues}
 * 复用的同一批处理内核）与 {@code CreateQuotationMaterializer.materialize}；"加产品"/"从基础刷新"
 * 两个前端触发点在后端最终都落到同一个 {@code ensureCardValues} 入口（RECORD.md「lazy-cardvalues」
 * 系列既有结论），核价侧独立方法（{@code CostingFreezeService}）本线未改（B-5 隔离纪律，
 * 见 backtask.md），故本测试直接调 {@code ensureCardValues}/{@code ensureExcelValues} 作为
 * "其它路径"的代表性入口，不逐一搭建 saveDraft 的完整 HTTP 请求体/核价单独立流程（那些路径
 * 本线代码零改动，风险面不在这条 AC 上）。
 */
@QuarkusTest
class Repair260829OtherPathsByteIdenticalTest {

    private static final String TAG = "T260829T8";
    // 固定 UUID:两个 worktree 各自跑一遍,靠这组常量对齐"同一份数据"。
    private static final UUID COMPONENT_ID = UUID.fromString("260829a0-0000-4000-8000-000000000001");
    private static final UUID TEMPLATE_ID = UUID.fromString("260829a0-0000-4000-8000-000000000002");
    private static final UUID QUOTATION_ID = UUID.fromString("260829a0-0000-4000-8000-000000000003");
    private static final UUID LINE_ID = UUID.fromString("260829a0-0000-4000-8000-000000000004");

    @Inject EntityManager em;
    @Inject CardSnapshotService cardSnapshotService;

    @AfterEach
    void cleanup() {
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("DELETE FROM quotation_view_structure WHERE quotation_id = :q").setParameter("q", QUOTATION_ID).executeUpdate();
            em.createNativeQuery("DELETE FROM quotation_line_component_data WHERE line_item_id = :l").setParameter("l", LINE_ID).executeUpdate();
            em.createNativeQuery("DELETE FROM quotation_line_item WHERE id = :l").setParameter("l", LINE_ID).executeUpdate();
            em.createNativeQuery("DELETE FROM quotation WHERE id = :q").setParameter("q", QUOTATION_ID).executeUpdate();
            em.createNativeQuery("DELETE FROM template_component_snapshot WHERE template_id = :t").setParameter("t", TEMPLATE_ID).executeUpdate();
            em.createNativeQuery("DELETE FROM template_component WHERE template_id = :t").setParameter("t", TEMPLATE_ID).executeUpdate();
            em.createNativeQuery("DELETE FROM template WHERE id = :t").setParameter("t", TEMPLATE_ID).executeUpdate();
            em.createNativeQuery("DELETE FROM component_sql_view WHERE component_id = :c").setParameter("c", COMPONENT_ID).executeUpdate();
            em.createNativeQuery("DELETE FROM component WHERE id = :c").setParameter("c", COMPONENT_ID).executeUpdate();
        });
    }

    private void buildFixedFixture() {
        cleanup(); // 幂等:先清一遍,防止上一次异常中断留下残留(同一组固定UUID)
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("INSERT INTO component (id, name, code, fields, formulas, data_driver_path, created_at, updated_at) " +
                    "VALUES (:id, :name, :code, CAST(:fields AS jsonb), CAST('[]' AS jsonb), :ddp, now(), now())")
                    .setParameter("id", COMPONENT_ID).setParameter("name", TAG + "-驱动组件")
                    .setParameter("code", TAG + "-fixed")
                    .setParameter("fields", "[{\"name\":\"名称\",\"field_type\":\"INPUT_TEXT\"},{\"name\":\"金额\",\"field_type\":\"INPUT_NUMBER\"}]")
                    .setParameter("ddp", "$" + TAG.toLowerCase() + "_view").executeUpdate();
            em.createNativeQuery("INSERT INTO component_sql_view (id, component_id, sql_view_name, sql_template, declared_columns, created_at, updated_at) " +
                    "VALUES (:id, :cid, :vn, :tpl, '[]', now(), now())")
                    .setParameter("id", UUID.randomUUID()).setParameter("cid", COMPONENT_ID)
                    .setParameter("vn", TAG.toLowerCase() + "_view")
                    .setParameter("tpl", "SELECT 'T8-P1'::text AS hf_part_no, '固定内容ABC'::text AS \"名称\", 88.88::numeric AS \"金额\"")
                    .executeUpdate();
        });

        String snapshot = "[{\"id\":\"" + UUID.randomUUID() + "\",\"componentId\":\"" + COMPONENT_ID +
                "\",\"componentName\":\"" + TAG + "-驱动组件\",\"componentCode\":\"" + TAG +
                "\",\"componentType\":\"NORMAL\",\"tabName\":\"" + TAG + "页签\",\"sortOrder\":0," +
                "\"fields\":[{\"name\":\"名称\",\"field_type\":\"INPUT_TEXT\"},{\"name\":\"金额\",\"field_type\":\"INPUT_NUMBER\"}]," +
                "\"formulas\":[],\"data_driver_path\":\"$" + TAG.toLowerCase() + "_view\"}]";
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("INSERT INTO template (id, template_series_id, name, template_kind, status, components_snapshot, created_at, updated_at) " +
                    "VALUES (:id, :tsid, :name, 'QUOTATION', 'PUBLISHED', CAST(:snap AS jsonb), now(), now())")
                    .setParameter("id", TEMPLATE_ID).setParameter("tsid", UUID.randomUUID())
                    .setParameter("name", TAG + "-模板").setParameter("snap", snapshot).executeUpdate();
            UUID tcId = UUID.randomUUID();
            em.createNativeQuery("INSERT INTO template_component (id, template_id, component_id, sort_order, tab_name, created_at) " +
                    "VALUES (:id, :tid, :cid, 0, :tab, now())")
                    .setParameter("id", tcId).setParameter("tid", TEMPLATE_ID).setParameter("cid", COMPONENT_ID)
                    .setParameter("tab", TAG + "页签").executeUpdate();
            em.createNativeQuery("INSERT INTO template_component_snapshot (template_id,template_component_id,component_id," +
                    "sort_order,tab_name,component_name,component_code,component_type,fields,formulas," +
                    "element_code_field,element_price_field,element_currency_field) " +
                    "VALUES (:templateId,:templateComponentId,:componentId,0,:tabName," +
                    ":componentName,:componentCode,'NORMAL',CAST(:fields AS jsonb),CAST('[]' AS jsonb),NULL,NULL,NULL)")
                    .setParameter("templateId", TEMPLATE_ID).setParameter("templateComponentId", tcId)
                    .setParameter("componentId", COMPONENT_ID).setParameter("tabName", TAG + "页签")
                    .setParameter("componentName", TAG + "-驱动组件").setParameter("componentCode", TAG)
                    .setParameter("fields", "[{\"name\":\"名称\",\"field_type\":\"INPUT_TEXT\"},{\"name\":\"金额\",\"field_type\":\"INPUT_NUMBER\"}]")
                    .executeUpdate();
        });

        QuarkusTransaction.requiringNew().run(() -> {
            UUID customerId = toUUID(em.createNativeQuery("SELECT id FROM customer LIMIT 1").getResultList().get(0));
            UUID salesRepId = toUUID(em.createNativeQuery("SELECT id FROM \"user\" LIMIT 1").getResultList().get(0));
            em.createNativeQuery("INSERT INTO quotation (id, quotation_number, customer_id, name, sales_rep_id, status, " +
                    "customer_template_id, tax_rate, tax_amount, created_at, updated_at) " +
                    "VALUES (:id, :qn, :cid, :name, :srid, 'DRAFT', :tid, 0, 0, now(), now())")
                    .setParameter("id", QUOTATION_ID).setParameter("qn", TAG + "-fixed")
                    .setParameter("cid", customerId).setParameter("name", TAG).setParameter("srid", salesRepId)
                    .setParameter("tid", TEMPLATE_ID).executeUpdate();
            em.createNativeQuery("INSERT INTO quotation_line_item (id, quotation_id, template_id, " +
                    "product_part_no_snapshot, sort_order, created_at) VALUES (:id, :qid, :tid, :pn, 0, now())")
                    .setParameter("id", LINE_ID).setParameter("qid", QUOTATION_ID).setParameter("tid", TEMPLATE_ID)
                    .setParameter("pn", TAG + "-P0").executeUpdate();
            em.createNativeQuery("INSERT INTO quotation_line_component_data (id, line_item_id, component_id, tab_name, " +
                    "row_data, snapshot_rows) VALUES (:id, :lid, :cid, :tab, CAST(:rd AS jsonb), CAST(:sr AS jsonb))")
                    .setParameter("id", UUID.randomUUID()).setParameter("lid", LINE_ID).setParameter("cid", COMPONENT_ID)
                    .setParameter("tab", TAG + "页签")
                    .setParameter("rd", "[{\"row_index\":0,\"名称\":\"固定内容ABC\",\"金额\":88.88}]")
                    .setParameter("sr", "[{\"driverRow\":{\"hf_part_no\":\"T8-P1\",\"名称\":\"固定内容ABC\",\"金额\":88.88}," +
                            "\"basicDataValues\":{}}]")
                    .executeUpdate();
        });
    }

    private static UUID toUUID(Object o) {
        return (o instanceof UUID u) ? u : UUID.fromString(o.toString());
    }

    private String readColumn(String col) {
        Object v = em.createNativeQuery("SELECT " + col + "::text FROM quotation_line_item WHERE id = :id")
                .setParameter("id", LINE_ID).getSingleResult();
        return v == null ? "<NULL>" : v.toString();
    }

    private static String md5(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName("T-08(AC-8): 固定fixture跑ensureCardValues/ensureExcelValues,输出quote_card_values等的MD5供跨worktree diff")
    void printDeterministicOutputForCrossWorktreeDiff() {
        buildFixedFixture();

        int filledCard = cardSnapshotService.ensureCardValues(QUOTATION_ID);
        assertEquals(1, filledCard, "前置数据非空:应补算1行(不是空跑)");
        int filledExcel = cardSnapshotService.ensureExcelValues(QUOTATION_ID);
        assertEquals(1, filledExcel, "前置数据非空:应补算1行Excel值(不是空跑)");

        String quoteCardValues = readColumn("quote_card_values");
        String quoteExcelValues = readColumn("quote_excel_values");
        assertNotEquals("<NULL>", quoteCardValues, "quote_card_values不应为NULL");
        assertTrue(quoteCardValues.contains("固定内容ABC"), "应包含预插的真实内容,实际=" + quoteCardValues);

        // ⚠️ 本行是跨worktree A/B对比的唯一依据——两次运行的这几行输出必须逐字节相同。
        System.out.println("[T-08-DIFF-BEGIN]");
        System.out.println("quote_card_values.md5=" + md5(quoteCardValues));
        System.out.println("quote_excel_values.md5=" + md5(quoteExcelValues));
        System.out.println("quote_card_values.raw=" + quoteCardValues);
        System.out.println("[T-08-DIFF-END]");
    }
}
