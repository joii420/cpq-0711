package com.cpq.quotation.task260901;

import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.persistence.EntityManager;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * task-260901 后端 HTTP 契约测试的**已提交**夹具。
 *
 * <p>为什么不能用 {@code @TestTransaction}：本任务的 AC-11/12/13/14 判的是 HTTP 状态码与响应形状
 * （409 / userDataVersion），HTTP 请求跑在自己的事务里，看不见测试事务内未提交的夹具。
 * 因此沿用 {@code Tc059ConcurrentSubmitHttpTest} 的做法：{@code QuarkusTransaction.requiringNew()}
 * 建committed 夹具 + {@code @AfterEach} 定点删除。
 *
 * <p>🚨 删除全部带 {@code WHERE} 且只命中本夹具自建的 id —— 不是清库、不是 TRUNCATE。
 *
 * <p><b>夹具形状（刻意造成"非空"，防 AC-3/AC-20 的断言空跑）：</b>
 * <pre>
 *   quotation (DRAFT)
 *     ├─ lineA  sortOrder=0  componentData×1(多键 row_data) + process×2 + lineItemSnapshot×1
 *     │         quote_card_values / costing_card_values 预置为非空哨兵
 *     └─ lineB  sortOrder=1  componentData×1 + process×1 + lineItemSnapshot×1
 *               同样预置非空哨兵
 * </pre>
 * dev 库 {@code cpq_db_0724} 的 quotation_line_process / quotation_line_item_snapshot 全库 0 行，
 * E2E 层测不到"删除后归零"和"未改动的行不丢工序"；这两条只有在这里才有分辨力。
 */
final class Task260901HttpFixture {

    /** 卡片值哨兵：非空且可辨认。断言"卡片值被清空"时要能区分「置 NULL」与「本来就没有」。 */
    static final String CARD_VALUES_SENTINEL =
            "{\"tabs\":[{\"tabName\":\"T260901\",\"baseRows\":[{\"__sentinel__\":\"KEEP-ME\"}]}]}";

    /**
     * lineA 的 componentData row_data。
     * 刻意用**多个键**且键长参差 —— PG jsonb 入库时会按自己的规则重排键序，
     * 于是"库里的文本"与"前端 JSON.stringify 的文本"必然不等（证据 E2-jsonb规范化.md）。
     * AC-9 就是要求后端在这种情况下判「未变」。
     */
    static final String ROW_DATA_A =
            "[{\"料件\":\"AgNi11#-Ⅰ\",\"单位\":\"g\",\"材料净重\":\"1000\",\"材料毛重\":\"1\",\"损耗率\":\"0\",\"组成数量\":\"1\",\"row_index\":1}]";

    /** 与 ROW_DATA_A 语义完全相同、**键顺序不同**的另一种写法（AC-9 的输入）。 */
    static final String ROW_DATA_A_REORDERED =
            "[{\"row_index\":1,\"组成数量\":\"1\",\"损耗率\":\"0\",\"材料毛重\":\"1\",\"材料净重\":\"1000\",\"单位\":\"g\",\"料件\":\"AgNi11#-Ⅰ\"}]";

    /** 只把「材料净重」的最后一位小数改掉（AC-10 的输入）—— T-9 的对照组。 */
    static final String ROW_DATA_A_LAST_DIGIT_DIFF =
            "[{\"row_index\":1,\"组成数量\":\"1\",\"损耗率\":\"0\",\"材料毛重\":\"1\",\"材料净重\":\"1000.00000000001\",\"单位\":\"g\",\"料件\":\"AgNi11#-Ⅰ\"}]";

    static final String ROW_DATA_B =
            "[{\"料件\":\"AgCu90\",\"单位\":\"g\",\"材料净重\":\"200\",\"材料毛重\":\"2\",\"损耗率\":\"0\",\"组成数量\":\"1\",\"row_index\":1}]";

    static final String TAB_NAME = "物料T260901";

    UUID customerId, productAId, productBId, componentId,
         quoteTemplateId, costingTemplateId, templateComponentId,
         quotationId, lineAId, lineBId, cdAId, cdBId;
    String quotationNumber;
    /** 本夹具实际挂上去的工序编号（从 process_master 现取的真实值，供断言消息回显）。 */
    List<String> usedProcessNos = List.of();

    private final EntityManager em;

    Task260901HttpFixture(EntityManager em) {
        this.em = em;
    }

    static Task260901HttpFixture create(EntityManager em, String label) {
        Task260901HttpFixture f = new Task260901HttpFixture(em);
        QuarkusTransaction.requiringNew().run(() -> f.build(label));
        return f;
    }

    /**
     * 从 {@code process_master} 现取真实工序编号。
     *
     * <p>🚨 <b>不能编造</b>：{@code quotation_line_process.process_no} 有外键
     * {@code quotation_line_process_process_no_fkey → process_master(process_no)}，
     * 写死的 "T260901-PROC-01" 之类会在 INSERT 时直接违反外键，导致本包 13 例全红
     * （2026-09-01 后端跑测试时实测到，根因是夹具而非产品）。
     *
     * <p>取不到足够行数时**显式抛错**而不是跳过 —— 工序数据是 AC-3 / AC-20 唯一的分辨力来源，
     * 没有它这两条 AC 就退回 {@code 0 == 0} 的空跑，而那正是它们被移到后端来测的原因。
     */
    @SuppressWarnings("unchecked")
    private List<String> takeRealProcessNos(int need) {
        List<Object> rows = em.createNativeQuery(
                "SELECT process_no FROM process_master ORDER BY process_no LIMIT " + need).getResultList();
        List<String> out = new ArrayList<>();
        for (Object o : rows) if (o != null) out.add(o.toString());
        if (out.size() < need) {
            throw new IllegalStateException(
                    "process_master 只有 " + out.size() + " 条可用工序编号，本夹具需要 " + need + " 条。"
                    + "AC-3（删除后工序归零）与 AC-20（未改动的行不丢工序）的分辨力全部来自这些数据，"
                    + "缺了就只能测 0==0。请先给测试库导入工序主数据（cpq_db 实测有 43 条）。");
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private UUID firstUserId() {
        List<Object> rows = em.createNativeQuery("SELECT id FROM \"user\" ORDER BY created_at LIMIT 1").getResultList();
        if (rows.isEmpty()) {
            throw new IllegalStateException("测试库无任何用户 —— 夹具无法满足 quotation.sales_rep_id 非空约束");
        }
        return toUUID(rows.get(0));
    }

    private void build(String label) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        OffsetDateTime now = OffsetDateTime.now();

        customerId = UUID.randomUUID();
        em.createNativeQuery("INSERT INTO customer(id,code,name,level,status) VALUES (:id,:code,:name,'STANDARD','ACTIVE')")
                .setParameter("id", customerId)
                .setParameter("code", "T260901-" + suffix)
                .setParameter("name", "task-260901 fixture customer " + label)
                .executeUpdate();

        productAId = insertProduct("T260901-PA-" + suffix, "task-260901 product A " + label);
        productBId = insertProduct("T260901-PB-" + suffix, "task-260901 product B " + label);

        componentId = UUID.randomUUID();
        em.createNativeQuery(
                "INSERT INTO component(id,name,code,column_count,fields,formulas,status,tab_type,row_key_fields) " +
                "VALUES (:id,:name,:code,0,CAST(:fields AS jsonb),'[]'::jsonb,'ACTIVE','主件',CAST(:rk AS jsonb))")
                .setParameter("id", componentId)
                .setParameter("name", TAB_NAME)
                .setParameter("code", "T260901-C-" + suffix)
                .setParameter("fields", "[" +
                        "{\"name\":\"料件\",\"field_type\":\"INPUT_TEXT\",\"is_amount\":false,\"is_subtotal\":false}," +
                        "{\"name\":\"单位\",\"field_type\":\"INPUT_TEXT\",\"is_amount\":false,\"is_subtotal\":false}," +
                        "{\"name\":\"材料净重\",\"field_type\":\"INPUT_NUMBER\",\"is_amount\":false,\"is_subtotal\":false}," +
                        "{\"name\":\"材料毛重\",\"field_type\":\"INPUT_NUMBER\",\"is_amount\":false,\"is_subtotal\":false}," +
                        "{\"name\":\"损耗率\",\"field_type\":\"INPUT_NUMBER\",\"is_amount\":false,\"is_subtotal\":false}," +
                        "{\"name\":\"组成数量\",\"field_type\":\"INPUT_NUMBER\",\"is_amount\":false,\"is_subtotal\":false}]")
                .setParameter("rk", "[\"料件\"]")
                .executeUpdate();

        quoteTemplateId = insertTemplate("QUOTATION", "task-260901 quote tpl " + label + " " + suffix);
        costingTemplateId = insertTemplate("COSTING", "task-260901 costing tpl " + label + " " + suffix);
        templateComponentId = bindComponent(quoteTemplateId);
        bindComponent(costingTemplateId);

        quotationId = UUID.randomUUID();
        quotationNumber = "T260901-" + label + "-" + suffix;
        em.createNativeQuery(
                "INSERT INTO quotation(id,quotation_number,customer_id,name,sales_rep_id,status," +
                "customer_template_id,costing_card_template_id,final_discount_rate,total_amount,original_amount,created_at,updated_at) " +
                "VALUES (:id,:no,:cid,:name,:uid,'DRAFT',:qt,:ct,100.00,0,0,:now,:now)")
                .setParameter("id", quotationId)
                .setParameter("no", quotationNumber)
                .setParameter("cid", customerId)
                .setParameter("name", "task-260901 fixture " + label)
                .setParameter("uid", firstUserId())
                .setParameter("qt", quoteTemplateId)
                .setParameter("ct", costingTemplateId)
                .setParameter("now", now)
                .executeUpdate();

        lineAId = insertLine(productAId, 0, "T260901-A-" + suffix, new BigDecimal("100.000000"), now);
        lineBId = insertLine(productBId, 1, "T260901-B-" + suffix, new BigDecimal("200.000000"), now);
        cdAId = insertComponentData(lineAId, ROW_DATA_A, 0, now);
        cdBId = insertComponentData(lineBId, ROW_DATA_B, 0, now);

        // 🔑 工序 / 组合工艺 / 行快照：这三张表在 dev 库全库 0 行，
        //    只有在这里造出来，AC-3（删除后归零）与 AC-20（未改动的行不丢）才有分辨力。
        //    ⚠️ process_no 走外键，必须用 process_master 里的**真实编号**，不能编造。
        usedProcessNos = takeRealProcessNos(2);
        insertProcess(lineAId, usedProcessNos.get(0));
        insertProcess(lineAId, usedProcessNos.get(1));   // A 行挂 2 条 → 能验「多条一起保 / 一起删」
        insertProcess(lineBId, usedProcessNos.get(0));   // 不同 line_item 复用同一编号不冲突（无 unique 约束）

        // 组合工艺：def_code **没有**外键约束（实测 pg_constraint 只有 pkey + line_item_id 一条 fkey），
        // 故可自由构造。AC-20 原文同时点名了工序与「选配-组合工艺」两张表，只造工序会漏掉一半。
        insertCompositeProcess(lineAId, "T260901-ASM-01", 1);
        insertCompositeProcess(lineAId, "T260901-ASM-02", 2);
        insertCompositeProcess(lineBId, "T260901-ASM-03", 1);

        insertLineSnapshot(lineAId, "T260901-A-" + suffix);
        insertLineSnapshot(lineBId, "T260901-B-" + suffix);

        em.flush();
    }

    private UUID insertProduct(String partNo, String name) {
        UUID id = UUID.randomUUID();
        em.createNativeQuery("INSERT INTO product(id,name,part_no,category,status) VALUES (:id,:n,:p,'T260901','ACTIVE')")
                .setParameter("id", id).setParameter("n", name).setParameter("p", partNo).executeUpdate();
        return id;
    }

    private UUID insertTemplate(String kind, String name) {
        UUID id = UUID.randomUUID();
        em.createNativeQuery(
                "INSERT INTO template(id,template_series_id,name,status,template_kind,components_snapshot,created_at,updated_at) " +
                "VALUES (:id,:sid,:n,'PUBLISHED',:k,'[{}]'::jsonb,now(),now())")
                .setParameter("id", id).setParameter("sid", UUID.randomUUID())
                .setParameter("n", name).setParameter("k", kind).executeUpdate();
        return id;
    }

    private UUID bindComponent(UUID templateId) {
        UUID tcId = UUID.randomUUID();
        em.createNativeQuery(
                "INSERT INTO template_component(id,template_id,component_id,tab_name,sort_order,created_at) " +
                "VALUES (:id,:t,:c,:tab,0,now())")
                .setParameter("id", tcId).setParameter("t", templateId)
                .setParameter("c", componentId).setParameter("tab", TAB_NAME).executeUpdate();
        em.createNativeQuery(
                "INSERT INTO template_component_snapshot(id,template_id,template_component_id,component_id,sort_order," +
                "tab_name,component_name,component_code,fields,formulas,tab_type) " +
                "VALUES (gen_random_uuid(),:t,:tc,:c,0,:tab,:cn,:cc," +
                "(SELECT fields FROM component WHERE id=:c),'[]'::jsonb,'主件')")
                .setParameter("t", templateId).setParameter("tc", tcId).setParameter("c", componentId)
                .setParameter("tab", TAB_NAME).setParameter("cn", TAB_NAME)
                .setParameter("cc", "T260901-C").executeUpdate();
        return tcId;
    }

    private UUID insertLine(UUID productId, int sortOrder, String partNo, BigDecimal subtotal, OffsetDateTime now) {
        UUID id = UUID.randomUUID();
        em.createNativeQuery(
                "INSERT INTO quotation_line_item(id,quotation_id,product_id,template_id,sort_order,created_at," +
                "product_part_no_snapshot,product_name_snapshot,subtotal,annual_volume,composite_type," +
                "quote_card_values,costing_card_values,quote_values_at) " +
                "VALUES (:id,:q,:p,:t,:so,:now,:pn,:pn,:st,1,'SIMPLE'," +
                "CAST(:cv AS jsonb),CAST(:cv AS jsonb),:now)")
                .setParameter("id", id).setParameter("q", quotationId).setParameter("p", productId)
                .setParameter("t", quoteTemplateId).setParameter("so", sortOrder).setParameter("now", now)
                .setParameter("pn", partNo).setParameter("st", subtotal)
                .setParameter("cv", CARD_VALUES_SENTINEL).executeUpdate();
        return id;
    }

    private UUID insertComponentData(UUID lineItemId, String rowData, int sortOrder, OffsetDateTime now) {
        UUID id = UUID.randomUUID();
        em.createNativeQuery(
                "INSERT INTO quotation_line_component_data(id,line_item_id,component_id,tab_name,row_data,subtotal,sort_order,created_at) " +
                "VALUES (:id,:l,:c,:tab,CAST(:rd AS jsonb),0,:so,:now)")
                .setParameter("id", id).setParameter("l", lineItemId).setParameter("c", componentId)
                .setParameter("tab", TAB_NAME).setParameter("rd", rowData)
                .setParameter("so", sortOrder).setParameter("now", now).executeUpdate();
        return id;
    }

    private void insertProcess(UUID lineItemId, String processNo) {
        em.createNativeQuery("INSERT INTO quotation_line_process(id,line_item_id,process_no) VALUES (gen_random_uuid(),:l,:p)")
                .setParameter("l", lineItemId).setParameter("p", processNo).executeUpdate();
    }

    private void insertCompositeProcess(UUID lineItemId, String defCode, int seqNo) {
        em.createNativeQuery(
                "INSERT INTO quotation_line_composite_process(id,line_item_id,def_code,seq_no,participating_parts,param_values,created_at) " +
                "VALUES (gen_random_uuid(),:l,:d,:s,CAST(:pp AS jsonb),CAST(:pv AS jsonb),now())")
                .setParameter("l", lineItemId).setParameter("d", defCode).setParameter("s", seqNo)
                .setParameter("pp", "[\"T260901-PART\"]")
                .setParameter("pv", "{\"T260901-KEY\":\"" + defCode + "\"}")
                .executeUpdate();
    }

    private void insertLineSnapshot(UUID lineItemId, String partNo) {
        em.createNativeQuery(
                "INSERT INTO quotation_line_item_snapshot(id,line_item_id,product_part_no,product_category,created_at) " +
                "VALUES (gen_random_uuid(),:l,:p,'T260901',now())")
                .setParameter("l", lineItemId).setParameter("p", partNo).executeUpdate();
    }

    /** 定点清理。每条 DELETE 都带 WHERE 且只命中本夹具自建对象。 */
    void cleanup() {
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("DELETE FROM costing_order_version_override WHERE costing_order_id IN (SELECT id FROM costing_order WHERE quotation_id=:q)")
                    .setParameter("q", quotationId).executeUpdate();
            em.createNativeQuery("DELETE FROM costing_order WHERE quotation_id=:q").setParameter("q", quotationId).executeUpdate();
            em.createNativeQuery("DELETE FROM quotation_line_process WHERE line_item_id IN (SELECT id FROM quotation_line_item WHERE quotation_id=:q)")
                    .setParameter("q", quotationId).executeUpdate();
            em.createNativeQuery("DELETE FROM quotation_line_composite_process WHERE line_item_id IN (SELECT id FROM quotation_line_item WHERE quotation_id=:q)")
                    .setParameter("q", quotationId).executeUpdate();
            em.createNativeQuery("DELETE FROM quotation_line_item_snapshot WHERE line_item_id IN (SELECT id FROM quotation_line_item WHERE quotation_id=:q)")
                    .setParameter("q", quotationId).executeUpdate();
            em.createNativeQuery("DELETE FROM quotation_line_component_data WHERE line_item_id IN (SELECT id FROM quotation_line_item WHERE quotation_id=:q)")
                    .setParameter("q", quotationId).executeUpdate();
            em.createNativeQuery("DELETE FROM quotation_line_item WHERE quotation_id=:q").setParameter("q", quotationId).executeUpdate();
            em.createNativeQuery("DELETE FROM quotation_component_sql_snapshot WHERE quotation_id=:q").setParameter("q", quotationId).executeUpdate();
            em.createNativeQuery("DELETE FROM quotation WHERE id=:q").setParameter("q", quotationId).executeUpdate();
            em.createNativeQuery("DELETE FROM template_component_snapshot WHERE template_id IN (:a,:b)")
                    .setParameter("a", quoteTemplateId).setParameter("b", costingTemplateId).executeUpdate();
            em.createNativeQuery("DELETE FROM template_component WHERE template_id IN (:a,:b)")
                    .setParameter("a", quoteTemplateId).setParameter("b", costingTemplateId).executeUpdate();
            em.createNativeQuery("DELETE FROM template WHERE id IN (:a,:b)")
                    .setParameter("a", quoteTemplateId).setParameter("b", costingTemplateId).executeUpdate();
            em.createNativeQuery("DELETE FROM component WHERE id=:c").setParameter("c", componentId).executeUpdate();
            em.createNativeQuery("DELETE FROM product WHERE id IN (:a,:b)")
                    .setParameter("a", productAId).setParameter("b", productBId).executeUpdate();
            em.createNativeQuery("DELETE FROM customer WHERE id=:c").setParameter("c", customerId).executeUpdate();
        });
    }

    static UUID toUUID(Object o) {
        if (o == null) return null;
        if (o instanceof UUID u) return u;
        return UUID.fromString(o.toString());
    }
}
