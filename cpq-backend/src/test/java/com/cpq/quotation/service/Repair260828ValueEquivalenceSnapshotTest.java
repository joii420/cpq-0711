package com.cpq.quotation.service;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * repair-260828 · T-3（AC-8/AC-9）：值逐位不变——「背靠背」快照采集器。
 *
 * <p><b>方法（对齐既有 `docs/RECORD.md` 已确立的手法</b>——"判改动是否改值用 stash 背靠背纯读对比"，
 * 与 {@code cpq-golden-cardvalues-preexisting-drift} 教训同款）：本测试固定夹具输入(相同的
 * component/template/row_data)，跑 {@code ensureCardValues}+{@code ensureExcelValues}，
 * 把结果打印为可 diff 的固定格式(每行 md5 + 头总额)。<b>本测试文件本身跑两遍</b>——
 * 一遍在当前 worktree 代码(backend WIP)上，一遍在 {@code git stash} 掉
 * {@code CardSnapshotService.java} 后的原始 master 基线上——用外部 diff 比对两次输出。
 * 过程脚本与实际输出见 test-report.md，本文件只负责"产出可比对的确定性快照"。
 *
 * <p>夹具刻意包含<b>非空业务值</b>(INPUT_NUMBER 字段 + 真实 row_data，对齐
 * {@code EnsureCardValuesEditConcurrencyTest} 已验证手法)，而不是空数组占位——
 * 空夹具会让 JSON 序列化差异无从体现，等于空验证。
 */
@QuarkusTest
class Repair260828ValueEquivalenceSnapshotTest {

    private static final String TAG = "T260828T3";

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

    /** UUID 形状的十六进制串(componentId 等每次运行随机生成,与"值是否不变"无关,归一化掉再取 md5,
     *  否则每次运行(哪怕改动前/后代码完全相同)都会因为随机 id 不同而 md5 恒不相同——是本测试
     *  开发期实测踩过的第二个"看似有效实则空验证"的坑,故显式记录在此。 */
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private static String normalize(String json) {
        if (json == null) return null;
        return UUID_PATTERN.matcher(json).replaceAll("<UUID>");
    }

    private static String md5(String s) {
        try {
            if (s == null) return "NULL";
            s = normalize(s);
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** 10 行,含真实 row_data(数值型字段,非空数组占位)。<b>刻意不设 data_driver_path</b>
     *  ——对齐 {@code EnsureCardValuesEditConcurrencyTest} 已验证手法:该组件不是 $view 驱动型,
     *  行完全由 row_data 静态给出。曾误加 data_driver_path=$view(WHERE FALSE)导致 10 行 md5
     *  恒定相同(0 条 baseRows 时 editRows 与 driver key 对不上被丢弃,是空验证的一种变体,
     *  已通过对比 EnsureCardValuesEditConcurrencyTest 的字段协议定位并改正)。 */
    private UUID buildFixtureWithRealComponentData() {
        UUID componentId = UUID.randomUUID();
        componentIds.add(componentId);
        String fields = "[{\"name\":\"rowKey\",\"field_type\":\"INPUT_TEXT\",\"sort_order\":0}," +
                "{\"name\":\"amount\",\"field_type\":\"INPUT_NUMBER\",\"sort_order\":1}]";
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("INSERT INTO component (id, name, code, fields, formulas, row_key_fields, created_at, updated_at) " +
                    "VALUES (:id, :name, :code, CAST(:fields AS jsonb), CAST('[]' AS jsonb), CAST('[\"rowKey\"]' AS jsonb), now(), now())")
                    .setParameter("id", componentId).setParameter("name", TAG + "-组件")
                    .setParameter("code", TAG + "-" + componentId.toString().substring(0, 8))
                    .setParameter("fields", fields)
                    .executeUpdate();
        });

        UUID templateId = UUID.randomUUID();
        templateIds.add(templateId);
        String snapshot = "[{\"id\":\"" + UUID.randomUUID() + "\",\"componentId\":\"" + componentId +
                "\",\"componentName\":\"" + TAG + "-组件\",\"componentCode\":\"" + TAG +
                "\",\"componentType\":\"NORMAL\",\"tabName\":\"" + TAG + "页签\",\"sortOrder\":0," +
                "\"fields\":" + fields + ",\"formulas\":[]," +
                "\"row_key_fields\":[\"rowKey\"]}]";
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
                    "sort_order,tab_name,component_name,component_code,component_type,fields,formulas,row_key_fields," +
                    "element_code_field,element_price_field,element_currency_field) " +
                    "VALUES (:templateId,:templateComponentId,:componentId,0,:tabName," +
                    ":componentName,:componentCode,'NORMAL',CAST(:fields AS jsonb),CAST('[]' AS jsonb),CAST('[\"rowKey\"]' AS jsonb),NULL,NULL,NULL)")
                    .setParameter("templateId", templateId).setParameter("templateComponentId", tcId)
                    .setParameter("componentId", componentId).setParameter("tabName", TAG + "页签")
                    .setParameter("componentName", TAG + "-组件").setParameter("componentCode", TAG)
                    .setParameter("fields", fields)
                    .executeUpdate();
        });

        UUID quotationId = UUID.randomUUID();
        QuarkusTransaction.requiringNew().run(() -> {
            UUID customerId = toUUID(em.createNativeQuery("SELECT id FROM customer ORDER BY id LIMIT 1").getResultList().get(0));
            UUID salesRepId = toUUID(em.createNativeQuery("SELECT id FROM \"user\" ORDER BY id LIMIT 1").getResultList().get(0));
            em.createNativeQuery("INSERT INTO quotation (id, quotation_number, customer_id, name, sales_rep_id, status, " +
                    "customer_template_id, tax_rate, tax_amount, created_at, updated_at) " +
                    "VALUES (:id, :qn, :cid, :name, :srid, 'DRAFT', :tid, 0, 0, now(), now())")
                    .setParameter("id", quotationId).setParameter("qn", TAG + "-" + quotationId.toString().substring(0, 8))
                    .setParameter("cid", customerId).setParameter("name", TAG).setParameter("srid", salesRepId)
                    .setParameter("tid", templateId).executeUpdate();
            for (int i = 0; i < 10; i++) {
                UUID lid = UUID.randomUUID();
                em.createNativeQuery("INSERT INTO quotation_line_item (id, quotation_id, template_id, " +
                        "product_part_no_snapshot, sort_order, created_at) VALUES (:id, :qid, :tid, :pn, :so, now())")
                        .setParameter("id", lid).setParameter("qid", quotationId).setParameter("tid", templateId)
                        .setParameter("pn", TAG + "-P" + i).setParameter("so", i).executeUpdate();
                // 真实 componentData:含手工编辑过的 row_data(数值随行号变化,非全 0)。
                // 关键:必须同时提供 snapshot_rows(driverRow,对齐 EnsureCardValuesEditConcurrencyTest
                // 已验证手法)——editRows 是按 rowKey 与 baseRows 匹配后才会出现,snapshot_rows 留空
                // 会导致 editRows 恒为空(所有行 md5 恒定相同,曾在本文件实测踩到,已定位并改正)。
                String rowData = "[{\"rowKey\":\"R0\",\"amount\":\"" + (i + 1) + ".23456789\"}," +
                        "{\"rowKey\":\"R1\",\"amount\":\"" + (i + 1) + "00.987654\"}]";
                String snapshotRows = "[{\"driverRow\":{\"rowKey\":\"R0\",\"amount\":\"" + (i + 1) + ".23456789\"}," +
                        "\"basicDataValues\":{}},{\"driverRow\":{\"rowKey\":\"R1\",\"amount\":\"" + (i + 1) + "00.987654\"}," +
                        "\"basicDataValues\":{}}]";
                em.createNativeQuery("INSERT INTO quotation_line_component_data (id, line_item_id, component_id, tab_name, " +
                        "row_data, snapshot_rows) VALUES (:id, :lid, :cid, :tab, CAST(:rd AS jsonb), CAST(:sr AS jsonb))")
                        .setParameter("id", UUID.randomUUID()).setParameter("lid", lid).setParameter("cid", componentId)
                        .setParameter("tab", TAG + "页签").setParameter("rd", rowData).setParameter("sr", snapshotRows)
                        .executeUpdate();
            }
        });
        quotationIds.add(quotationId);
        return quotationId;
    }

    @Test
    @DisplayName("T-3(AC-8/AC-9): 采集确定性快照(md5 + 头总额),供 stash 背靠背比对——本次跑的是当前 worktree 代码")
    void captureSnapshotForBackToBackComparison() {
        UUID qid = buildFixtureWithRealComponentData();

        int filledCard = cardSnapshotService.ensureCardValues(qid);
        int filledExcel = cardSnapshotService.ensureExcelValues(qid);
        assertEquals(10, filledCard, "前置数据非空:应补算 10 行卡片值(不是空跑)");
        assertEquals(10, filledExcel, "前置数据非空:应补算 10 行 Excel 值(不是空跑)");

        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                "SELECT product_part_no_snapshot, quote_card_values::text, costing_card_values::text, " +
                "quote_excel_values::text, costing_excel_values::text, subtotal::text " +
                "FROM quotation_line_item WHERE quotation_id = :q ORDER BY sort_order")
                .setParameter("q", qid).getResultList();
        assertEquals(10, rows.size(), "应查回 10 行(非空验证)");

        StringBuilder sb = new StringBuilder();
        sb.append("[T-3 SNAPSHOT] fixture=REAL_COMPONENT_DATA_N10\n");
        java.util.Set<String> distinctQcvMd5 = new java.util.HashSet<>();
        for (Object[] r : rows) {
            String qcvMd5 = md5((String) r[1]);
            distinctQcvMd5.add(qcvMd5);
            sb.append(String.format("  part=%s qcv_md5=%s ccv_md5=%s qev_md5=%s cev_md5=%s subtotal=%s%n",
                    r[0], qcvMd5, md5((String) r[2]), md5((String) r[3]), md5((String) r[4]), r[5]));
            // 非空验证:至少 qcv 不能是 NULL(否则说明整批没算出来,后面的 md5 比对毫无意义)
            assertNotNull(r[1], "part=" + r[0] + " quote_card_values 不应为 NULL(非空验证)");
        }
        // 证伪实验(§0):10 行的 row_data 各不相同,若 qcv_md5 全部相同则说明夹具/断言从未真正
        // 走到"逐行取值"这条路——本文件开发期真实撞过一次(忘记提供 snapshot_rows.driverRow,
        // 导致 editRows 恒为空、10 行 md5 恒定相同),已定位改正,这条断言就是防止同类回归的证伪线。
        assertEquals(10, distinctQcvMd5.size(),
                "10 行 row_data 各不相同,quote_card_values 的 md5 应有 10 个不同值(证伪:若相同说明本测试是空验证)");

        Object[] header = (Object[]) em.createNativeQuery(
                "SELECT total_amount::text, original_amount::text FROM quotation WHERE id = :q")
                .setParameter("q", qid).getSingleResult();
        sb.append(String.format("  header total_amount=%s original_amount=%s%n", header[0], header[1]));

        System.out.print(sb);
        if (System.getProperty("t3.dumpRaw") != null) {
            for (int i = 0; i < Math.min(2, rows.size()); i++) {
                System.out.println("[T-3 RAW " + i + "] qcv=" + rows.get(i)[1]);
            }
        }
    }
}
