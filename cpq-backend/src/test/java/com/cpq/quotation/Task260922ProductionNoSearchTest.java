package com.cpq.quotation;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * task-260922 · AC-6 · 生产料号搜索：不区分大小写 + 中间段模糊 + 按本单客户隔离。
 *
 * <p><b>AC-6 原文</b>（任务.md §④）：
 * <pre>
 * 前置：cpq_db_test，测试全程在一个事务里、结束回滚（不留任何数据）。造数前缀 T260922P：
 *   客户 A（code = T260922P-CA）、客户 B（code = T260922P-CB）；
 *   报价物料 ds_quote_material：(T260922P-CA, T260922P-S1) → production_no = 'T260922P-PrOd-Xy'（只绑 A）；
 *   报价单 QA（客户 A）、QB（客户 B）各一个产品行，销售料号都是 T260922P-S1，客户料号为空
 * 操作：在同一事务内调列表查询（QuotationService.list），依次用 partNo =
 *   ① t260922p-prod-xy  ② T260922P-PROD-XY  ③ PrOd-X
 * 断言：三次结果都恰好是 {QA}：totalElements = 1 且唯一一条的 id = QA。QB 不得出现
 *   （它有同一销售料号，但客户 B 没有这条绑定 —— 跨客户隔离）。
 *   ④ 阳性对照：partNo = T260922P-S1（销售料号）→ 结果 = {QA, QB}，totalElements = 2 ——
 *   证明两张单都造成功、查询看得见本事务的数据（否则 ①~③ 的「只有 QA」可能是空跑）
 * </pre>
 *
 * <p><b>为什么这组造数有区分力</b>：
 * <ul>
 *   <li>关键字 ①②③ 只出现在 {@code ds_quote_material.production_no} 里，销售料号 {@code T260922P-S1}
 *       与客户料号（空）都不含 {@code prod} —— 所以 QA 被命中<b>只可能</b>来自生产料号这一路；</li>
 *   <li>QB 与 QA 销售料号相同，差别<b>只在客户</b>：漏了按客户过滤的实现会把 QB 一起带出来（total=2）；</li>
 *   <li>生产料号存的是混合大小写 {@code PrOd-Xy}，① 全小写 / ② 全大写 / ③ 混合大小写的中间一段 ——
 *       任何一侧没转小写、或做成精确/前缀匹配，都会在其中至少一次得到 0；</li>
 *   <li>④ 证明 QA、QB 都真实存在且对 {@code list} 可见（同一事务、同一数据源）。没有 ④，
 *       ①~③「只返回 QA」在「QB 根本没造成功 / list 看不见本事务数据」时同样成立。</li>
 * </ul>
 *
 * <p><b>造数前置守卫</b>（AC 之外、只为防假绿）：造数<b>之前</b>先用同样四个关键字各查一次，
 * 必须全部为 0 —— 证明库里没有别的数据恰好命中（否则 ①~④ 的计数会被污染，红得像业务回归）。
 * 造数之后再按前缀回读一次行数，证明四张表确实写进去了。
 *
 * <p><b>零残留</b>：整个方法一个 {@code @TestTransaction}，结束即回滚；本类不写任何 DELETE。
 * 唯一不随回滚撤销的是 {@code ds_quote_material_id_seq} 的序列值（+1，PostgreSQL 序列非事务性），不是数据行。
 *
 * <p>🚫 本类只从 AC 派生，未读实现代码。{@code list} 签名取自 任务.md §⑥「测试用到的服务方法签名」。
 */
@QuarkusTest
class Task260922ProductionNoSearchTest {

    private static final String PREFIX = "T260922P";
    private static final String CODE_A = PREFIX + "-CA";
    private static final String CODE_B = PREFIX + "-CB";
    private static final String SALES_PART_NO = PREFIX + "-S1";
    private static final String PRODUCTION_NO = PREFIX + "-PrOd-Xy";

    private static final String KW_1_LOWER = "t260922p-prod-xy";
    private static final String KW_2_UPPER = "T260922P-PROD-XY";
    private static final String KW_3_PARTIAL = "PrOd-X";
    private static final String KW_4_SALES = SALES_PART_NO;

    @Inject
    com.cpq.quotation.service.QuotationService quotationService;

    @Inject
    EntityManager em;

    /** 一次 list 调用的可观测结果。 */
    record Hit(String label, String partNo, long total, List<String> ids, List<String> numbers) {}

    @Test
    @TestTransaction
    void ac6_productionNo_caseInsensitive_partial_and_isolatedByCustomer() {
        // ---------- 0. 造数前：库里不得有别的数据命中这四个关键字 ----------
        List<Hit> before = List.of(
                call("造数前①", KW_1_LOWER),
                call("造数前②", KW_2_UPPER),
                call("造数前③", KW_3_PARTIAL),
                call("造数前④", KW_4_SALES));
        for (Hit h : before) {
            assertEquals(0L, h.total(),
                    "环境污染（不是业务缺陷）：造数之前 partNo=" + h.partNo() + " 就已命中 " + h.total()
                            + " 单 " + h.numbers() + " —— cpq_db_test 里有别的数据含该关键字，AC-6 的计数断言失效");
        }

        // ---------- 1. 造数（AC-6 前置原文） ----------
        UUID adminId = adminUserId();
        UUID customerA = insertCustomer(CODE_A, PREFIX + " 客户A");
        UUID customerB = insertCustomer(CODE_B, PREFIX + " 客户B");
        insertQuoteMaterial(CODE_A, SALES_PART_NO, PRODUCTION_NO);           // 只绑 A
        UUID qa = insertQuotation(PREFIX + "-QA", PREFIX + " 报价单QA", customerA, adminId);
        UUID qb = insertQuotation(PREFIX + "-QB", PREFIX + " 报价单QB", customerB, adminId);
        insertLineItem(qa, SALES_PART_NO);                                     // 客户料号为空
        insertLineItem(qb, SALES_PART_NO);
        System.out.println("[AC-6] 造数完成 QA=" + qa + " (客户 " + CODE_A + ")  QB=" + qb + " (客户 " + CODE_B + ")");

        // 回读：四张表确实写进去了（夹具非空）
        assertEquals(2L, countOf("SELECT count(*) FROM customer WHERE code LIKE 'T260922P-%'"), "客户应造 2 个");
        assertEquals(2L, countOf("SELECT count(*) FROM quotation WHERE quotation_number LIKE 'T260922P-%'"), "报价单应造 2 张");
        assertEquals(2L, countOf("SELECT count(*) FROM quotation_line_item WHERE product_part_no_snapshot = 'T260922P-S1'"
                + " AND customer_part_no IS NULL"), "产品行应造 2 行（客户料号为空）");
        assertEquals(1L, countOf("SELECT count(*) FROM ds_quote_material WHERE material_no = 'T260922P-S1'"
                + " AND customer_no = 'T260922P-CA' AND production_no = 'T260922P-PrOd-Xy'"), "报价物料应只绑 A 一行");
        assertEquals(0L, countOf("SELECT count(*) FROM ds_quote_material WHERE customer_no = 'T260922P-CB'"),
                "客户 B 不得有任何报价物料绑定");

        // ---------- 2. 四次查询（先全部跑完并打印，再统一断言，一次看全） ----------
        Hit h1 = call("①", KW_1_LOWER);
        Hit h2 = call("②", KW_2_UPPER);
        Hit h3 = call("③", KW_3_PARTIAL);
        Hit h4 = call("④阳性对照", KW_4_SALES);

        String qaId = qa.toString();
        String qbId = qb.toString();

        assertAll("AC-6",
                () -> assertExactlyQa(h1, qaId, qbId),
                () -> assertExactlyQa(h2, qaId, qbId),
                () -> assertExactlyQa(h3, qaId, qbId),
                () -> {
                    assertEquals(2L, h4.total(), "④ 阳性对照 partNo=" + h4.partNo()
                            + " 应命中 QA、QB 两张（totalElements=2），实际 " + h4.total() + " ids=" + h4.ids()
                            + " —— 若为 0：list 看不见本事务的数据（或 page 参数口径不是 0 起），①~③ 的结论不可信");
                    assertEquals(Set.of(qaId, qbId), new HashSet<>(h4.ids()),
                            "④ 阳性对照返回的 id 集合应恰为 {QA, QB}，实际 " + h4.ids());
                });
    }

    /**
     * 阳性对照（<b>不是 AC 断言</b>，只为证明主用例里「QB 不出现」确实是被<b>客户维度</b>挡掉的）：
     * 同样造数，但<b>给客户 B 也绑上同一条</b> (T260922P-CB, T260922P-S1) → T260922P-PrOd-Xy，
     * 此时 partNo=① 必须返回 {QA, QB}。
     * <p>若这里也只返回 QA，说明 QB 在主用例里消失另有原因（例如 QB 因别的条件根本进不了结果），
     * 主用例 ①~③ 的「跨客户隔离」结论就不成立 —— testing.md §4.4「断言某事没发生，必须配阳性对照」。
     */
    @Test
    @TestTransaction
    void ac6_control_whenCustomerBAlsoBound_qbBecomesVisible() {
        assertEquals(0L, call("对照-造数前", KW_1_LOWER).total(), "环境污染：造数前已有命中");
        UUID adminId = adminUserId();
        UUID customerA = insertCustomer(CODE_A, PREFIX + " 客户A");
        UUID customerB = insertCustomer(CODE_B, PREFIX + " 客户B");
        insertQuoteMaterial(CODE_A, SALES_PART_NO, PRODUCTION_NO);
        insertQuoteMaterial(CODE_B, SALES_PART_NO, PRODUCTION_NO);            // 对照组：B 也绑
        UUID qa = insertQuotation(PREFIX + "-QA", PREFIX + " 报价单QA", customerA, adminId);
        UUID qb = insertQuotation(PREFIX + "-QB", PREFIX + " 报价单QB", customerB, adminId);
        insertLineItem(qa, SALES_PART_NO);
        insertLineItem(qb, SALES_PART_NO);

        Hit h = call("对照-B也绑定", KW_1_LOWER);
        assertEquals(2L, h.total(), "对照组：B 也绑定后 partNo=" + h.partNo() + " 应命中 QA、QB 两张，实际 "
                + h.total() + " ids=" + h.ids() + " —— 主用例「QB 不出现」不能归因于客户隔离");
        assertEquals(Set.of(qa.toString(), qb.toString()), new HashSet<>(h.ids()), "对照组 id 集合应为 {QA, QB}");
    }

    // ------------------------------------------------------------------
    // 断言：恰好是 {QA}
    // ------------------------------------------------------------------
    private static void assertExactlyQa(Hit h, String qaId, String qbId) {
        assertFalse(h.ids().contains(qbId), h.label() + " partNo=" + h.partNo()
                + " 返回了 QB（客户 B 没有该生产料号绑定）—— 生产料号没有按本单客户过滤，跨客户串号。ids=" + h.ids());
        assertEquals(1L, h.total(), h.label() + " partNo=" + h.partNo()
                + " 应恰好命中 1 单（QA），实际 totalElements=" + h.total() + " ids=" + h.ids());
        assertEquals(List.of(qaId), h.ids(), h.label() + " partNo=" + h.partNo()
                + " 唯一一条应为 QA=" + qaId + "，实际 ids=" + h.ids());
    }

    // ------------------------------------------------------------------
    // 调用被测服务：QuotationService.list(page, size, status, salesRepId, assignedApproverId,
    //                                      keyword, partNo, categoryId, templateSeriesId)
    // 除 partNo 外全部不过滤；page 取 0（接口层 page 从 0 起，见 task-260914 S-API README）。
    // 每次调用都打印实际返回的 totalElements 与 id 列表（testing.md §3：要求打印实际值）。
    // ------------------------------------------------------------------
    private Hit call(String label, String partNo) {
        var page = quotationService.list(0, 20, null, null, null, null, partNo, null, null);
        List<String> ids = new ArrayList<>();
        List<String> numbers = new ArrayList<>();
        for (var dto : page.getContent()) {
            ids.add(String.valueOf(dto.id));
            numbers.add(dto.quotationNumber);
        }
        long total = page.getTotalElements();
        System.out.println("[AC-6 " + label + "] partNo=" + partNo + " totalElements=" + total
                + " ids=" + ids + " quotationNumbers=" + numbers);
        return new Hit(label, partNo, total, ids, numbers);
    }

    // ------------------------------------------------------------------
    // 造数（原生 INSERT，同一事务的 EntityManager）。
    // NOT NULL 且无默认值的列（2026-09-22 查 cpq_db_test information_schema）：
    //   customer:            name, code                         （level/status/accumulated_amount/version/时间戳 有默认）
    //   quotation:           quotation_number, customer_id, name, sales_rep_id（status 默认 'DRAFT'，其余有默认）
    //   quotation_line_item: quotation_id                       （composite_type/part_version_locked/row_version 有默认）
    //   ds_quote_material:   material_no, customer_no           （id 走序列，source 默认 'IMPORT'）
    // 长度：customer.code ≤50、ds_quote_material.customer_no ≤20、material_no/production_no ≤128、quotation_number ≤50。
    // 唯一键：customer.code、quotation.quotation_number、ds_quote_material(customer_no, material_no) ——
    //   2026-09-22 已查 cpq_db_test 前缀 T260922P 在四张表均 0 行。
    // ------------------------------------------------------------------
    private UUID adminUserId() {
        List<?> rows = em.createNativeQuery("SELECT id FROM \"user\" WHERE username = 'admin' LIMIT 1").getResultList();
        assertFalse(rows.isEmpty(), "cpq_db_test 里找不到 admin 用户（quotation.sales_rep_id 外键需要）");
        return UUID.fromString(rows.get(0).toString());
    }

    private UUID insertCustomer(String code, String name) {
        UUID id = UUID.randomUUID();
        int n = em.createNativeQuery("INSERT INTO customer (id, name, code) VALUES (:id, :name, :code)")
                .setParameter("id", id).setParameter("name", name).setParameter("code", code)
                .executeUpdate();
        assertEquals(1, n, "插入客户 " + code);
        return id;
    }

    private void insertQuoteMaterial(String customerNo, String materialNo, String productionNo) {
        int n = em.createNativeQuery(
                        "INSERT INTO ds_quote_material (material_no, customer_no, production_no) VALUES (:m, :c, :p)")
                .setParameter("m", materialNo).setParameter("c", customerNo).setParameter("p", productionNo)
                .executeUpdate();
        assertEquals(1, n, "插入报价物料 (" + customerNo + ", " + materialNo + ")");
    }

    private UUID insertQuotation(String number, String name, UUID customerId, UUID salesRepId) {
        UUID id = UUID.randomUUID();
        int n = em.createNativeQuery(
                        "INSERT INTO quotation (id, quotation_number, customer_id, name, sales_rep_id) "
                                + "VALUES (:id, :no, :cid, :name, :rep)")
                .setParameter("id", id).setParameter("no", number).setParameter("cid", customerId)
                .setParameter("name", name).setParameter("rep", salesRepId)
                .executeUpdate();
        assertEquals(1, n, "插入报价单 " + number);
        return id;
    }

    private void insertLineItem(UUID quotationId, String salesPartNo) {
        int n = em.createNativeQuery(
                        "INSERT INTO quotation_line_item (id, quotation_id, product_part_no_snapshot, customer_part_no) "
                                + "VALUES (:id, :qid, :pn, NULL)")
                .setParameter("id", UUID.randomUUID()).setParameter("qid", quotationId).setParameter("pn", salesPartNo)
                .executeUpdate();
        assertEquals(1, n, "插入产品行 " + quotationId);
    }

    private long countOf(String sql) {
        return ((Number) em.createNativeQuery(sql).getSingleResult()).longValue();
    }
}
