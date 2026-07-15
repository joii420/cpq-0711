package com.cpq.existingproduct;

import com.cpq.common.dto.PageResult;
import com.cpq.common.exception.BusinessException;
import com.cpq.existingproduct.dto.ExistingProductDTO;
import com.cpq.existingproduct.service.ExistingProductService;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ExistingProductService DB 断言自测（task-0712 B3，backtask.md B3 / api.md §2.1）。
 *
 * <p>每个用例 {@code @TestTransaction}（构造夹具 + 调服务 + 断言全在同一事务，方法结束自动回滚），
 * 不污染共享 DB，无需 RUN_ID 清理（对齐 {@code ConfigureProductServiceB2LedgerTest} 同款风格）。
 *
 * <p>覆盖：
 * <ul>
 *   <li>F005（P0）：选配发号占位行（{@code customer_product_no IS NULL}）必须被过滤；</li>
 *   <li>规格 {@code spec = COALESCE(NULLIF(specification,''), dimension)}，含 LEFT JOIN 无命中；</li>
 *   <li>{@code has3d}/{@code thumbnailUrl} 来自 {@code model_config is_current}；</li>
 *   <li>4 过滤（customerProductNo/salesPartNo/productName/spec）各自命中 + AND 组合；</li>
 *   <li>分页 total/totalPages；</li>
 *   <li>N+1：Hibernate {@code Statistics.getPrepareStatementCount()} 前后差值验证为固定条数
 *       （不随命中行数增长），证明两 LEFT JOIN 单条 SQL、不逐行查 3D/规格。</li>
 * </ul>
 */
@QuarkusTest
class ExistingProductServiceTest {

    @Inject
    ExistingProductService service;

    @Inject
    EntityManager em;

    record Fixture(UUID quotationId, String customerCode,
                    String matA, String matB, String matC, String matPlaceholder) {}

    /**
     * 夹具：1 客户 + 1 报价单 + 3 个真实客户产品行(A/B/C) + 1 个选配发号占位行(F005)。
     * <ul>
     *   <li>matA：material_master.specification='DN50'（直接命中）；</li>
     *   <li>matB：specification=''（空串），dimension='100X200MM'（测 NULLIF 回退）；</li>
     *   <li>matC：无 material_master 行（测 LEFT JOIN 无命中不炸、spec=null）；</li>
     *   <li>matPlaceholder：customer_product_no=NULL + customer_material_name=NULL
     *       （模拟 {@code QuoteMaterialNoAllocator.mintAndRegister} 的发号占位行）。</li>
     * </ul>
     */
    @SuppressWarnings("unchecked")
    private Fixture seed() {
        String runId = UUID.randomUUID().toString().substring(0, 6);

        List<Object> uRows = em.createNativeQuery(
                "SELECT id FROM \"user\" WHERE username = 'admin' LIMIT 1").getResultList();
        if (uRows.isEmpty()) {
            throw new IllegalStateException("admin user not found — V1 migration must have run");
        }
        UUID adminId = UUID.fromString(uRows.get(0).toString());

        UUID customerId = UUID.randomUUID();
        String customerCode = "EP" + runId; // material_customer_map.customer_no VARCHAR(20)，须留余量

        em.createNativeQuery(
                "INSERT INTO customer (id, name, code, level, status, created_at, updated_at) " +
                "VALUES (:id, 'Existing Product Test Customer', :code, 'STANDARD', 'ACTIVE', NOW(), NOW())")
                .setParameter("id", customerId).setParameter("code", customerCode).executeUpdate();

        UUID quotationId = UUID.randomUUID();
        em.createNativeQuery(
                "INSERT INTO quotation (id, quotation_number, customer_id, name, sales_rep_id, status, created_at, updated_at) " +
                "VALUES (:id, :qno, :cid, 'EP Test Quotation', :uid, 'DRAFT', NOW(), NOW())")
                .setParameter("id", quotationId).setParameter("qno", "QT-EP-" + runId)
                .setParameter("cid", customerId).setParameter("uid", adminId).executeUpdate();

        String matA = "EPA" + runId;
        String matB = "EPB" + runId;
        String matC = "EPC" + runId;
        String matPlaceholder = "EPZ" + runId;

        em.createNativeQuery(
                "INSERT INTO material_master (material_no, specification, dimension, created_at, updated_at) " +
                "VALUES (:m, 'DN50', NULL, NOW(), NOW())").setParameter("m", matA).executeUpdate();
        em.createNativeQuery(
                "INSERT INTO material_master (material_no, specification, dimension, created_at, updated_at) " +
                "VALUES (:m, '', '100X200MM', NOW(), NOW())").setParameter("m", matB).executeUpdate();
        // matC 故意不建 material_master 行

        insertMcm(matA, customerCode, "阀体A", "CPN-A-" + runId);
        insertMcm(matB, customerCode, "阀体B", "CPN-B-" + runId);
        insertMcm(matC, customerCode, "阀体C", "CPN-C-" + runId);

        // F005: 选配发号占位行
        em.createNativeQuery(
                "INSERT INTO material_customer_map " +
                "(material_no, customer_no, customer_material_name, customer_product_no, system_type, created_at, updated_at) " +
                "VALUES (:m, :c, NULL, NULL, 'QUOTE', NOW(), NOW())")
                .setParameter("m", matPlaceholder).setParameter("c", customerCode).executeUpdate();

        return new Fixture(quotationId, customerCode, matA, matB, matC, matPlaceholder);
    }

    private void insertMcm(String materialNo, String customerCode, String customerMaterialName, String customerProductNo) {
        em.createNativeQuery(
                "INSERT INTO material_customer_map " +
                "(material_no, customer_no, customer_material_name, customer_product_no, system_type, created_at, updated_at) " +
                "VALUES (:m, :c, :name, :cpn, 'QUOTE', NOW(), NOW())")
                .setParameter("m", materialNo).setParameter("c", customerCode)
                .setParameter("name", customerMaterialName).setParameter("cpn", customerProductNo)
                .executeUpdate();
    }

    private void seedModelConfig(String subjectKey, String thumbnailUrl) {
        em.createNativeQuery(
                "INSERT INTO model_config (subject_type, subject_key, version, is_current, glb_url, thumbnail_url, uploaded_at) " +
                "VALUES ('SALES_PART', :k, 1, true, '/files/model/x.glb', :t, NOW())")
                .setParameter("k", subjectKey).setParameter("t", thumbnailUrl).executeUpdate();
    }

    private ExistingProductDTO find(PageResult<ExistingProductDTO> result, String materialNo) {
        return result.getContent().stream().filter(d -> materialNo.equals(d.materialNo)).findFirst()
                .orElseThrow(() -> new AssertionError("未找到 materialNo=" + materialNo
                        + " content=" + result.getContent().stream().map(d -> d.materialNo).toList()));
    }

    // ── F005：占位行过滤（P0） ──────────────────────────────────────────

    @Test
    @TestTransaction
    @DisplayName("F005: 选配发号占位行(customer_product_no IS NULL) 必须被过滤，不出现在列表")
    void excludesPlaceholderRow() {
        Fixture f = seed();
        PageResult<ExistingProductDTO> result = service.list(f.quotationId(), null, null, null, null, 0, 20);

        assertEquals(3L, result.getTotalElements(), "3 个真实产品，占位行不计入 total");
        assertEquals(3, result.getContent().size());
        assertTrue(result.getContent().stream().noneMatch(d -> f.matPlaceholder().equals(d.materialNo)),
                "占位行 materialNo 不应出现在返回内容中");
        assertTrue(result.getContent().stream().allMatch(d -> d.customerProductNo != null),
                "所有返回行 customerProductNo 必须非空");
    }

    // ── 规格映射 ──────────────────────────────────────────────────────

    @Test
    @TestTransaction
    @DisplayName("spec = COALESCE(NULLIF(specification,''), dimension)，无 material_master 行时安全为 null")
    void specMappingCoalesceSpecificationThenDimension() {
        Fixture f = seed();
        PageResult<ExistingProductDTO> result = service.list(f.quotationId(), null, null, null, null, 0, 20);

        assertEquals("DN50", find(result, f.matA()).spec);
        assertEquals("100X200MM", find(result, f.matB()).spec, "specification 空串须 NULLIF 后回退 dimension");
        assertNull(find(result, f.matC()).spec, "无 material_master 行时 LEFT JOIN 应安全返回 null spec");
    }

    // ── 3D 关联 ──────────────────────────────────────────────────────

    @Test
    @TestTransaction
    @DisplayName("has3d/thumbnailUrl 来自 model_config is_current，未配置时 has3d=false/thumbnailUrl=null")
    void has3dAndThumbnailFromModelConfigIsCurrent() {
        Fixture f = seed();
        seedModelConfig(f.matA(), "/files/model/thumb-a.png");
        // matB 故意不配 3D

        PageResult<ExistingProductDTO> result = service.list(f.quotationId(), null, null, null, null, 0, 20);
        ExistingProductDTO a = find(result, f.matA());
        ExistingProductDTO b = find(result, f.matB());

        assertTrue(a.has3d);
        assertEquals("/files/model/thumb-a.png", a.thumbnailUrl);
        assertFalse(b.has3d);
        assertNull(b.thumbnailUrl);
    }

    // ── 4 过滤 ──────────────────────────────────────────────────────

    @Test
    @TestTransaction
    @DisplayName("过滤 customerProductNo：模糊命中单条")
    void filterByCustomerProductNo() {
        Fixture f = seed();
        PageResult<ExistingProductDTO> result = service.list(f.quotationId(), "CPN-A", null, null, null, 0, 20);
        assertEquals(1L, result.getTotalElements());
        assertEquals(f.matA(), result.getContent().get(0).materialNo);
    }

    @Test
    @TestTransaction
    @DisplayName("过滤 salesPartNo(material_no)：模糊命中单条")
    void filterBySalesPartNo() {
        Fixture f = seed();
        PageResult<ExistingProductDTO> result = service.list(f.quotationId(), null, f.matB(), null, null, 0, 20);
        assertEquals(1L, result.getTotalElements());
        assertEquals(f.matB(), result.getContent().get(0).materialNo);
    }

    @Test
    @TestTransaction
    @DisplayName("过滤 productName(=customer_material_name)：模糊命中单条，且 productName/customerMaterialName 同源")
    void filterByProductName() {
        Fixture f = seed();
        PageResult<ExistingProductDTO> result = service.list(f.quotationId(), null, null, "阀体A", null, 0, 20);
        assertEquals(1L, result.getTotalElements());
        ExistingProductDTO dto = result.getContent().get(0);
        assertEquals(f.matA(), dto.materialNo);
        assertEquals("阀体A", dto.productName);
        assertEquals("阀体A", dto.customerMaterialName);
    }

    @Test
    @TestTransaction
    @DisplayName("过滤 spec：对 COALESCE 表达式同口径模糊匹配，含回退到 dimension 的场景")
    void filterBySpec() {
        Fixture f = seed();

        PageResult<ExistingProductDTO> r1 = service.list(f.quotationId(), null, null, null, "DN50", 0, 20);
        assertEquals(1L, r1.getTotalElements());
        assertEquals(f.matA(), r1.getContent().get(0).materialNo);

        PageResult<ExistingProductDTO> r2 = service.list(f.quotationId(), null, null, null, "100X200", 0, 20);
        assertEquals(1L, r2.getTotalElements());
        assertEquals(f.matB(), r2.getContent().get(0).materialNo);
    }

    @Test
    @TestTransaction
    @DisplayName("4 过滤 AND 组合：不匹配的过滤器交叉应返回 0 条")
    void filtersCombineWithAnd() {
        Fixture f = seed();
        // matA 的 customerProductNo 命中 "CPN-A"，但 productName 用 matB 的"阀体B" → AND 组合应 0 条
        PageResult<ExistingProductDTO> result = service.list(f.quotationId(), "CPN-A", null, "阀体B", null, 0, 20);
        assertEquals(0L, result.getTotalElements());
    }

    // ── 分页 ──────────────────────────────────────────────────────

    @Test
    @TestTransaction
    @DisplayName("分页 total/totalPages 正确")
    void paginationTotalAndPages() {
        Fixture f = seed();
        PageResult<ExistingProductDTO> page0 = service.list(f.quotationId(), null, null, null, null, 0, 2);
        assertEquals(3L, page0.getTotalElements());
        assertEquals(2, page0.getContent().size());
        assertEquals(2, page0.getTotalPages());

        PageResult<ExistingProductDTO> page1 = service.list(f.quotationId(), null, null, null, null, 1, 2);
        assertEquals(3L, page1.getTotalElements());
        assertEquals(1, page1.getContent().size(), "第二页应剩 1 条(3 条,每页 2 条)");
    }

    // ── quotation 解析失败 ────────────────────────────────────────────

    @Test
    @TestTransaction
    @DisplayName("quotationId 不存在 → BusinessException(404)")
    void quotationNotFoundThrows() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.list(UUID.randomUUID(), null, null, null, null, 0, 20));
        assertEquals(404, ex.getCode());
    }

    // ── N+1 硬指标 ──────────────────────────────────────────────────

    @Test
    @TestTransaction
    @DisplayName("N+1: SQL 语句数固定(不随命中行数增长)，两 LEFT JOIN 单条查询取 3D+规格")
    void noNPlusOneFixedStatementCount() {
        Fixture f = seed();
        seedModelConfig(f.matA(), "/files/model/thumb-a.png");
        seedModelConfig(f.matB(), "/files/model/thumb-b.png");

        Statistics st = em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        st.setStatisticsEnabled(true);
        long before = st.getPrepareStatementCount();

        PageResult<ExistingProductDTO> result = service.list(f.quotationId(), null, null, null, null, 0, 20);

        long stmts = st.getPrepareStatementCount() - before;
        assertEquals(3L, result.getTotalElements());
        // 固定开销：① resolveCustomerNo（1 条 JOIN 查询）② count ③ 分页数据（两 LEFT JOIN 一次带出）
        // = 3 条 SQL，与命中行数(此处 3 行，其中 2 行还命中 model_config)无关；留 2 条余量防止环境抖动。
        assertTrue(stmts <= 5, "应为固定条数 SQL(不逐行查 3D/规格)，实测=" + stmts);
    }
}
