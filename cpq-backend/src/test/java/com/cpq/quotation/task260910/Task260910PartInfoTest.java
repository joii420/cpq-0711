package com.cpq.quotation.task260910;

import com.cpq.quotation.dto.QuotationDTO;
import com.cpq.quotation.service.ProductionPartInfoService;
import com.cpq.quotation.service.QuotationService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.hibernate.Session;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * task-260910 后端单测片（S-后端单测）：**AC-7 N+1 守恒** + **AC-11 union 合并口径**。
 *
 * <p>造数纪律：所有自造行一律 {@code T260910-} 前缀，{@link #cleanup()} 里逐表按前缀定点删除。
 * 🚫 无 TRUNCATE、无清库、无无 WHERE 的 DELETE（{@code CLAUDE.md §3.2}）。
 *
 * <p>⚠️ 为什么不用 {@code @TestTransaction}：AC-7 的测量对象是 {@code getById} 这条真实读路径，
 * 夹具与被测调用必须在同一可见性下；这里用「显式建 + 显式删」的已提交夹具，
 * 与 {@code Task260901HttpFixture} 同型，且删除条件只命中本测试自造的前缀行。
 */
@QuarkusTest
@TestProfile(Task260910StatsProfile.class)
class Task260910PartInfoTest {

    private static final String P = "T260910-";

    @Inject
    EntityManager em;

    @Inject
    QuotationService quotationService;

    @Inject
    ProductionPartInfoService productionPartInfoService;

    @Inject
    com.cpq.quotation.service.CustomerPartCandidateService candidateService;

    private final List<UUID> createdQuotations = new ArrayList<>();

    // ---------------------------------------------------------------- AC-7

    /**
     * AC-7：打开含 N 个产品行的报价单，本次新增的三类查询各只发 1 条 SQL，条数与 N 无关。
     * N=1 与 N=5 两次的每类条数必须相同且为 1。
     */
    @Test
    void ac7_newQueriesAreConstantWithRespectToLineCount() {
        String suffix = shortId();
        String code1 = P + "1-" + suffix;   // customer.code varchar(20)：8+2+8=18 ✓
        Fixture f1 = createFixture(code1, 1);
        Fixture f5 = createFixture(P + "5-" + suffix, 5);

        Map<String, Long> n1 = measure(f1.quotationId);
        Map<String, Long> n5 = measure(f5.quotationId);

        System.out.println("[AC-7] N=1 → " + n1);
        System.out.println("[AC-7] N=5 → " + n5);

        for (String table : List.of("ds_quote_customer_part", "ds_quote_material", "ds_cost_basic_material")) {
            assertEquals(1L, n1.get(table), "N=1 时 " + table + " 应恰好 1 条 SQL，实际 " + n1.get(table));
            assertEquals(1L, n5.get(table), "N=5 时 " + table + " 应恰好 1 条 SQL，实际 " + n5.get(table));
        }

        // 分辨力自检：如果三类 SQL 一条都没发（例如客户码解析失败），上面的断言会全变成 0 而不是 1，
        // 但为了让"测到了真数据"这件事可证，这里再确认返回值确实非空。
        QuotationDTO dto5 = readQuotation(f5.quotationId);
        assertEquals(5, dto5.lineItems.size());
        long withHf = dto5.lineItems.stream().filter(li -> li.hfPartInfo != null).count();
        assertEquals(5L, withHf, "5 行都绑了生产料号，hfPartInfo 不应为 null —— 否则 AC-7 是空跑");
    }

    /** 统计三类新增 SQL 的执行次数（按 SQL 文本里的表名归类）。 */
    private Map<String, Long> measure(UUID quotationId) {
        Statistics st = em.unwrap(Session.class).getSessionFactory().getStatistics();
        st.clear();
        readQuotation(quotationId);
        Map<String, Long> out = new LinkedHashMap<>();
        for (String table : List.of("ds_quote_customer_part", "ds_quote_material", "ds_cost_basic_material")) {
            long c = 0;
            for (String q : st.getQueries()) {
                if (q.contains(table)) c += st.getQueryStatistics(q).getExecutionCount();
            }
            out.put(table, c);
        }
        out.put("__allQueries__", st.getQueryExecutionCount());
        return out;
    }

    private QuotationDTO readQuotation(UUID quotationId) {
        return QuarkusTransaction.requiringNew().call(() -> quotationService.getById(quotationId));
    }

    // --------------------------------------------------------------- AC-11

    /**
     * AC-11：同一 production_no 同时存在于两张核价表且**列值冲突**时，
     * 取 basic 优先 + 逐列取非空（basic 某列为空/空白 → 取 detail 同列）。
     */
    @Test
    void ac11_unionMergeIsBasicFirstThenColumnwiseCoalesce() {
        String suffix = shortId();
        String customerNo = P + "U-" + suffix;
        String materialNo = P + "M-" + suffix;
        String productionNo = P + "P-" + suffix;

        QuarkusTransaction.requiringNew().run(() -> {
            insertQuoteMaterial(customerNo, materialNo, productionNo);
            // basic：material_name / dimension 有值；specification 为 NULL；old_material_no 为空白串
            insertCostMaterial("ds_cost_basic_material", productionNo,
                    "BASIC-NAME", null, "BASIC-DIM", "   ");
            // detail：四列全有值且与 basic 冲突
            insertCostMaterial("ds_cost_detail_material", productionNo,
                    "DETAIL-NAME", "DETAIL-SPEC", "DETAIL-DIM", "DETAIL-OLD");
        });

        var infos = productionPartInfoService.loadByMaterialNos(customerNo, List.of(materialNo));
        ProductionPartInfoService.ProductionPartInfo pi = infos.get(materialNo);
        assertNotNull(pi, "应取到生产料号详情");

        System.out.println("[AC-11] productionNo=" + pi.productionNo
                + " partName=" + pi.partName
                + " specification=" + pi.specification
                + " sizeInfo=" + pi.sizeInfo
                + " oldMaterialNo=" + pi.oldMaterialNo);

        assertEquals(productionNo, pi.productionNo);
        assertEquals("BASIC-NAME", pi.partName, "basic 有值 → 必须用 basic，不许被 detail 覆盖");
        assertEquals("DETAIL-SPEC", pi.specification, "basic 该列为 NULL → 逐列 COALESCE 取 detail");
        assertEquals("BASIC-DIM", pi.sizeInfo, "basic 有值 → 用 basic");
        assertEquals("DETAIL-OLD", pi.oldMaterialNo, "basic 该列为空白串 → 视同为空，取 detail");
    }

    /**
     * AC-11 边界（同源口径，防「只在两表都有时才对」）：
     * production_no 有值但两张核价表都没有该行 → 详情非 null，但只有 productionNo 有值。
     * 这是现网 2688/2696 的绝大多数情况。
     */
    @Test
    void ac11b_productionNoWithoutAnyCostRowKeepsPartNoOnly() {
        String suffix = shortId();
        String customerNo = P + "V-" + suffix;
        String materialNo = P + "M-" + suffix;
        String productionNo = P + "ORPHAN-" + suffix;
        QuarkusTransaction.requiringNew().run(() -> insertQuoteMaterial(customerNo, materialNo, productionNo));

        var pi = productionPartInfoService.loadByMaterialNos(customerNo, List.of(materialNo)).get(materialNo);
        assertNotNull(pi);
        assertEquals(productionNo, pi.productionNo);
        assertNull(pi.partName);
        assertNull(pi.specification);
        assertNull(pi.sizeInfo);
        assertNull(pi.oldMaterialNo);
    }

    /**
     * 客户维度隔离（B-1/B-2 的自有不变量；AC-5 的最终验收由 test-engineer 从 UI 侧做）：
     * 同一销售料号在两个客户下绑不同生产料号，取 C1 必须拿到 C1 的那个。
     */
    @Test
    void customerIsolation_sameMaterialNoDifferentCustomersDoNotCross() {
        String suffix = shortId();
        String c1 = P + "A-" + suffix;
        String c2 = P + "B-" + suffix;
        String materialNo = P + "M-" + suffix;
        QuarkusTransaction.requiringNew().run(() -> {
            insertQuoteMaterial(c1, materialNo, P + "P-AAA-" + suffix);
            insertQuoteMaterial(c2, materialNo, P + "P-BBB-" + suffix);
        });

        assertEquals(P + "P-AAA-" + suffix,
                productionPartInfoService.loadByMaterialNos(c1, List.of(materialNo)).get(materialNo).productionNo);
        assertEquals(P + "P-BBB-" + suffix,
                productionPartInfoService.loadByMaterialNos(c2, List.of(materialNo)).get(materialNo).productionNo);
    }

    // ---------------------------------------------------------------- B-4

    /**
     * B-4 正向证据：选品候选（{@code GET /quotations/customer-part-candidates} 背后的
     * {@code listCandidates}）的 hfPartInfo 走的是与报价单卡片**同一条**新链路。
     *
     * <p>手法：候选行的**行主轴**（material_master ∩ material_customer_map）不动，
     * 只给一个临时客户在 {@code ds_quote_material} + {@code ds_cost_basic_material} 里
     * 造出绑定 —— 于是同一个候选料号在这个客户下就该带出生产料号详情。
     * 🚫 不写 material_customer_map / material_master（那是 task-260909 在途任务的断言面）。
     */
    @Test
    void b4_candidateHfPartInfoComesFromNewChain() {
        String suffix = shortId();
        String customerCode = P + "K-" + suffix;
        String productionNo = P + "PK-" + suffix;

        String candidatePartNo = firstCandidatePartNo();
        assertNotNull(candidatePartNo,
                "候选行主轴（material_master ∩ material_customer_map）为空 —— 本用例会退化成空跑。"
                + " 请先给测试库导入至少一个带 customer_product_no 的客户映射料号。");

        UUID customerId = UUID.randomUUID();
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery(
                    "INSERT INTO customer(id,code,name,level,status) VALUES (:id,:code,:name,'STANDARD','ACTIVE')")
                    .setParameter("id", customerId).setParameter("code", customerCode)
                    .setParameter("name", "task-260910 B-4 fixture").executeUpdate();
            insertQuoteMaterial(customerCode, candidatePartNo, productionNo);
            insertCostMaterial("ds_cost_basic_material", productionNo,
                    "候选生产件", null, "9×9×9", "OLD-B4");
        });

        var candidates = QuarkusTransaction.requiringNew()
                .call(() -> candidateService.listCandidates(customerId, null));
        var hit = candidates.stream().filter(c -> candidatePartNo.equals(c.partNo)).findFirst().orElse(null);
        assertNotNull(hit, "候选列表应含 " + candidatePartNo);
        assertNotNull(hit.hfPartInfo, "该候选在本客户下已绑生产料号，hfPartInfo 不应为 null");
        System.out.println("[B-4] candidate " + candidatePartNo + " → partNo=" + hit.hfPartInfo.partNo
                + " partName=" + hit.hfPartInfo.partName
                + " sizeInfo=" + hit.hfPartInfo.sizeInfo
                + " oldMaterialNo=" + hit.hfPartInfo.oldMaterialNo);
        assertEquals(productionNo, hit.hfPartInfo.partNo, "🚫 必须是生产料号，不是销售料号");
        assertEquals("候选生产件", hit.hfPartInfo.partName);
        assertNull(hit.hfPartInfo.specification);
        assertEquals("9×9×9", hit.hfPartInfo.sizeInfo);
        assertEquals("OLD-B4", hit.hfPartInfo.oldMaterialNo);
    }

    @SuppressWarnings("unchecked")
    private String firstCandidatePartNo() {
        List<Object> rows = em.createNativeQuery(
                "SELECT p.material_no FROM material_master p " +
                "WHERE p.material_no IN (SELECT material_no FROM material_customer_map " +
                "  WHERE system_type='QUOTE' AND customer_product_no IS NOT NULL AND pending_quotation_id IS NULL) " +
                "ORDER BY p.material_no LIMIT 1").getResultList();
        return rows.isEmpty() ? null : rows.get(0).toString();
    }

    // ------------------------------------------------------------- fixture

    private record Fixture(UUID quotationId, String customerCode) {}

    /** 建 1 个客户 + 1 张报价单 + n 个产品行，并给每个行的销售料号造齐 ds_* 三张表的数据。 */
    private Fixture createFixture(String customerCode, int n) {
        UUID quotationId = UUID.randomUUID();
        QuarkusTransaction.requiringNew().run(() -> {
            UUID customerId = UUID.randomUUID();
            em.createNativeQuery(
                    "INSERT INTO customer(id,code,name,level,status) VALUES (:id,:code,:name,'STANDARD','ACTIVE')")
                    .setParameter("id", customerId).setParameter("code", customerCode)
                    .setParameter("name", "task-260910 fixture " + customerCode).executeUpdate();

            em.createNativeQuery(
                    "INSERT INTO quotation(id,quotation_number,customer_id,name,sales_rep_id,status,created_at,updated_at) " +
                    "VALUES (:id,:no,:cid,:name,:uid,'DRAFT',now(),now())")
                    .setParameter("id", quotationId)
                    .setParameter("no", customerCode + "-Q")
                    .setParameter("cid", customerId)
                    .setParameter("name", "task-260910 fixture quotation")
                    .setParameter("uid", firstUserId()).executeUpdate();

            for (int i = 0; i < n; i++) {
                String materialNo = customerCode + "-M" + i;
                String productionNo = customerCode + "-P" + i;
                String customerProductNo = customerCode + "-CPN" + i;
                em.createNativeQuery(
                        "INSERT INTO quotation_line_item(id,quotation_id,sort_order,created_at," +
                        "product_part_no_snapshot,product_name_snapshot,customer_part_no,composite_type) " +
                        "VALUES (gen_random_uuid(),:q,:so,now(),:pn,:pn,:cpn,'SIMPLE')")
                        .setParameter("q", quotationId).setParameter("so", i)
                        .setParameter("pn", materialNo).setParameter("cpn", customerProductNo)
                        .executeUpdate();
                insertQuoteMaterial(customerCode, materialNo, productionNo);
                em.createNativeQuery(
                        "INSERT INTO ds_quote_customer_part(customer_no,customer_part_name,customer_product_no," +
                        "customer_drawing_no,material_no,source) VALUES (:c,:nm,:cpn,:dw,:m,'T260910')")
                        .setParameter("c", customerCode).setParameter("nm", "客户料号名" + i)
                        .setParameter("cpn", customerProductNo).setParameter("dw", "DWG" + i)
                        .setParameter("m", materialNo).executeUpdate();
                insertCostMaterial("ds_cost_basic_material", productionNo,
                        "生产件" + i, "SPEC" + i, "DIM" + i, "OLD" + i);
            }
        });
        createdQuotations.add(quotationId);
        return new Fixture(quotationId, customerCode);
    }

    private void insertQuoteMaterial(String customerNo, String materialNo, String productionNo) {
        em.createNativeQuery(
                "INSERT INTO ds_quote_material(customer_no,material_no,production_no,source) " +
                "VALUES (:c,:m,:p,'T260910')")
                .setParameter("c", customerNo).setParameter("m", materialNo)
                .setParameter("p", productionNo).executeUpdate();
    }

    private void insertCostMaterial(String table, String productionNo,
                                    String name, String spec, String dim, String oldNo) {
        em.createNativeQuery(
                "INSERT INTO " + table + "(production_no,material_name,specification,dimension,old_material_no,source) " +
                "VALUES (:p,:n,:s,:d,:o,'T260910')")
                .setParameter("p", productionNo).setParameter("n", name).setParameter("s", spec)
                .setParameter("d", dim).setParameter("o", oldNo).executeUpdate();
    }

    @SuppressWarnings("unchecked")
    private UUID firstUserId() {
        List<Object> rows = em.createNativeQuery("SELECT id FROM \"user\" ORDER BY created_at LIMIT 1").getResultList();
        if (rows.isEmpty()) {
            throw new IllegalStateException("测试库无任何用户 —— 夹具无法满足 quotation.sales_rep_id 非空约束");
        }
        Object o = rows.get(0);
        return o instanceof UUID u ? u : UUID.fromString(o.toString());
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * 定点清理。每条 DELETE 都带 WHERE，且只命中本测试自造的 {@code T260910-} 前缀行 /
     * 自建 quotation 的子行。🚫 不是 TRUNCATE、不是清库。
     */
    @AfterEach
    void cleanup() {
        QuarkusTransaction.requiringNew().run(() -> {
            for (UUID qid : createdQuotations) {
                em.createNativeQuery("DELETE FROM quotation_line_item WHERE quotation_id = :q")
                        .setParameter("q", qid).executeUpdate();
                em.createNativeQuery("DELETE FROM quotation WHERE id = :q")
                        .setParameter("q", qid).executeUpdate();
            }
            em.createNativeQuery("DELETE FROM ds_quote_customer_part WHERE customer_no LIKE :p")
                    .setParameter("p", P + "%").executeUpdate();
            em.createNativeQuery("DELETE FROM ds_quote_material WHERE customer_no LIKE :p")
                    .setParameter("p", P + "%").executeUpdate();
            em.createNativeQuery("DELETE FROM ds_cost_basic_material WHERE production_no LIKE :p")
                    .setParameter("p", P + "%").executeUpdate();
            em.createNativeQuery("DELETE FROM ds_cost_detail_material WHERE production_no LIKE :p")
                    .setParameter("p", P + "%").executeUpdate();
            em.createNativeQuery("DELETE FROM customer WHERE code LIKE :p")
                    .setParameter("p", P + "%").executeUpdate();
        });
        createdQuotations.clear();
    }
}
