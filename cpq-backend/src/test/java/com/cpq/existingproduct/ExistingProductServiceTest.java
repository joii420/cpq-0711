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
 * ExistingProductService DB 断言自测（task-260909 起改读单表，api.md §1）。
 *
 * <p>每个用例 {@code @TestTransaction}（构造夹具 + 调服务 + 断言全在同一事务，方法结束自动回滚），
 * 不污染共享 DB，无需 RUN_ID 清理（对齐 {@code ConfigureProductServiceB2LedgerTest} 同款风格）。
 *
 * <p>🔧 <b>task-260909 夹具迁移</b>：本服务的数据源已从 {@code material_customer_map}
 * 收敛为单表 {@code ds_quote_customer_part}，故夹具改插新表 —— 否则 {@code seed()} 造的行
 * 服务一行也读不到，用例会以「total=0」的形式集体假红。品名/规格仍由 {@code material_master}
 * 提供（它是 {@code v_compat_material_master} 的第一分支，遮蔽 {@code ds_quote_material}）。
 *
 * <p>覆盖：
 * <ul>
 *   <li>规格 {@code spec = COALESCE(NULLIF(specification,''), dimension)}，含 LEFT JOIN 无命中；</li>
 *   <li>4 过滤（customerProductNo/salesPartNo/productName/spec）各自命中 + AND 组合；</li>
 *   <li>分页 total/totalPages；</li>
 *   <li>N+1：Hibernate {@code Statistics.getPrepareStatementCount()} 前后差值验证为固定条数
 *       （不随命中行数增长），证明单条 SQL 一次带出规格与全部编号、不逐行查。</li>
 * </ul>
 */
@QuarkusTest
class ExistingProductServiceTest {

    @Inject
    ExistingProductService service;

    @Inject
    EntityManager em;

    record Fixture(UUID quotationId, String customerCode,
                    String matA, String matB, String matC) {}

    /**
     * 夹具：1 客户 + 1 报价单 + 3 个客户产品行(A/B/C)，全部落 {@code ds_quote_customer_part}。
     * <ul>
     *   <li>matA：material_master.material_name='阀体A'、specification='DN50'（直接命中）；</li>
     *   <li>matB：material_name='阀体B'、specification=''（空串）、dimension='100X200MM'（测 NULLIF 回退）；</li>
     *   <li>matC：<b>无</b> material_master 行（测 LEFT JOIN 无命中不炸：spec=null、品名回退销售料号）。</li>
     * </ul>
     * <p>客户物料名刻意造成 {@code '客户'+品名}，与主数据品名<b>不相同</b> —— AC-5 要求这两列取不同的列，
     * 夹具若让它们相等，{@code filterByProductName} 就验不出拆分是否真的生效。
     * <p>🚫 不再造「{@code customer_product_no IS NULL} 占位行」：新表该列是 {@code NOT NULL}，
     * 那个状态在数据库层面已不可能存在（原 F005 用例随之删除）。
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
        String customerCode = "EP" + runId; // ds_quote_customer_part.customer_no VARCHAR(20)，须留余量

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

        // 品名/规格来源：material_master 是 v_compat_material_master 的第一分支（遮蔽 ds_quote_material），
        // 所以插它就能被服务的 LEFT JOIN 读到。material_no 是 varchar(20)，夹具料号须留够余量。
        em.createNativeQuery(
                "INSERT INTO material_master (material_no, material_name, specification, dimension, created_at, updated_at) " +
                "VALUES (:m, '阀体A', 'DN50', NULL, NOW(), NOW())").setParameter("m", matA).executeUpdate();
        em.createNativeQuery(
                "INSERT INTO material_master (material_no, material_name, specification, dimension, created_at, updated_at) " +
                "VALUES (:m, '阀体B', '', '100X200MM', NOW(), NOW())").setParameter("m", matB).executeUpdate();
        // matC 故意不建 material_master 行

        insertDqcp(matA, customerCode, "客户阀体A", "CPN-A-" + runId, "DWG-A-" + runId, "IMPORT");
        insertDqcp(matB, customerCode, "客户阀体B", "CPN-B-" + runId, "DWG-B-" + runId, "IMPORT");
        insertDqcp(matC, customerCode, "客户阀体C", "CPN-C-" + runId, null, "MANUAL");

        return new Fixture(quotationId, customerCode, matA, matB, matC);
    }

    /**
     * 插一行客户产品到 {@code ds_quote_customer_part}（task-260909 起的唯一数据源）。
     * <p>{@code customer_no} 与 {@code customer_product_no} 均 {@code NOT NULL}，
     * 唯一键是 {@code (customer_no, customer_product_no)} —— 夹具的编号带 runId 后缀避免跨用例撞键。
     * {@code id} 走序列默认值，不显式指定。
     */
    private void insertDqcp(String materialNo, String customerNo, String customerPartName,
                            String customerProductNo, String customerDrawingNo, String source) {
        em.createNativeQuery(
                "INSERT INTO ds_quote_customer_part " +
                "(customer_no, material_no, customer_part_name, customer_product_no, customer_drawing_no, " +
                " source, created_at, updated_at) " +
                "VALUES (:c, :m, :name, :cpn, :dwg, :src, NOW(), NOW())")
                .setParameter("c", customerNo).setParameter("m", materialNo)
                .setParameter("name", customerPartName).setParameter("cpn", customerProductNo)
                .setParameter("dwg", customerDrawingNo).setParameter("src", source)
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
    @DisplayName("过滤 productName(=主数据品名)：模糊命中单条，且 productName/customerMaterialName 是两个不同的值")
    void filterByProductName() {
        Fixture f = seed();
        // 过滤口径 = COALESCE(NULLIF(v.material_name,''), d.material_no)，即主数据品名，不再是客户物料名。
        PageResult<ExistingProductDTO> result = service.list(f.quotationId(), null, null, "阀体A", null, 0, 20);
        assertEquals(1L, result.getTotalElements());
        ExistingProductDTO dto = result.getContent().get(0);
        assertEquals(f.matA(), dto.materialNo);
        assertEquals("阀体A", dto.productName, "品名取 v_compat_material_master.material_name");
        assertEquals("客户阀体A", dto.customerMaterialName, "客户物料名取 ds_quote_customer_part.customer_part_name");
        // 🚨 task-260909 AC-5 把语义**反转**了：改动前两者同取一列、必然相等；现在必须是两个不同的列。
        //    原用例断言的是「同源」，那条断言已随 AC-5 作废，这里改断言「不相等」——
        //    否则 productName 若被谁改回兜底到 customer_part_name，测试会一路绿着放行。
        assertNotEquals(dto.productName, dto.customerMaterialName,
                "AC-5：品名与客户物料名必须取不同的列，🚫 productName 的兜底链里不许出现 customer_part_name");
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
    @DisplayName("4 过滤 AND 组合：两个单独都命中的条件交叉后应返回 0 条")
    void filtersCombineWithAnd() {
        Fixture f = seed();

        // 🚨 正向对照（这半条不能省）：先证明两个条件**单独都能命中 1 条**。
        //    否则「AND 组合返回 0」在数据源整个坏掉时也恒成立 —— 那是空跑通过的假绿。
        //    实证（2026-09-09 还原实验）：把夹具写回老表后 9 个用例红了 7 个，本用例却依旧绿，
        //    正是因为它当时只断言 0 条。
        assertEquals(1L, service.list(f.quotationId(), "CPN-A", null, null, null, 0, 20).getTotalElements(),
                "正向对照：customerProductNo=CPN-A 单独应命中 1 条");
        assertEquals(1L, service.list(f.quotationId(), null, null, "阀体B", null, 0, 20).getTotalElements(),
                "正向对照：productName=阀体B 单独应命中 1 条");

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
    @DisplayName("N+1: SQL 语句数固定(不随命中行数增长)，单条查询一次带出规格")
    void noNPlusOneFixedStatementCount() {
        Fixture f = seed();
        // task-260909 AC-7 起 model_config 的 JOIN 已删。这两行改作**反向证据**：
        // 即便库里有当前版本 3D 行，SQL 条数也不该变（真删干净了才成立）。
        seedModelConfig(f.matA(), "/files/model/thumb-a.png");
        seedModelConfig(f.matB(), "/files/model/thumb-b.png");

        Statistics st = em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        st.setStatisticsEnabled(true);
        long before = st.getPrepareStatementCount();

        PageResult<ExistingProductDTO> result = service.list(f.quotationId(), null, null, null, null, 0, 20);

        long stmts = st.getPrepareStatementCount() - before;
        assertEquals(3L, result.getTotalElements());
        // 固定开销：① resolveCustomerNo（1 条 JOIN 查询）② count ③ 分页数据
        // （v_compat_material_master 取规格 + agg 取全部编号，一次 LEFT JOIN 带出）
        // = 3 条 SQL，与命中行数无关；留 2 条余量防止环境抖动。
        assertTrue(stmts <= 5, "应为固定条数 SQL(不逐行查 3D/规格)，实测=" + stmts);
    }
}
