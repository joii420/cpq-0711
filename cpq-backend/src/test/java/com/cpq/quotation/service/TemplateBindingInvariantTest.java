package com.cpq.quotation.service;

import com.cpq.common.exception.BusinessException;
import com.cpq.quotation.dto.CreateQuotationRequest;
import com.cpq.quotation.dto.QuotationDTO;
import com.cpq.quotation.dto.SaveDraftRequest;
import com.cpq.quotation.entity.Quotation;
import com.cpq.template.entity.Template;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * task-0729 — QuotationService 模板绑定服务层不变量（存在 / 类型 / 状态）测试。
 *
 * <p>对应 dev-docs/task-0729-模板绑定状态校验/需求与实现计划.md §四 T1~T12。验证
 * {@link QuotationService#create} / {@link QuotationService#saveDraft} /
 * {@link QuotationService#copy} 三入口共用的 {@code validateTemplateBinding} 私有方法语义：
 * <ol>
 *   <li>templateId == null → 放行（不是绑定动作）</li>
 *   <li>模板不存在 → 400</li>
 *   <li>模板类型 != expectedKind → 400</li>
 *   <li>templateId == currentValue → 放行（维持原绑定，豁免状态校验 —— §2.2 防回归核心）</li>
 *   <li>模板 status != PUBLISHED → 400</li>
 * </ol>
 *
 * <p>策略：每个测试方法 {@code @TestTransaction} 自动回滚，无需手工清理（沿用
 * {@code SaveDraftRestrictedTabValidationTest} 的取舍）；服务层方法均为
 * {@code @Transactional(REQUIRED)}，在已激活的 TestTransaction 内直接加入同一事务，
 * 抛出的 {@link BusinessException} 只影响本方法内后续操作，不跨测试污染。
 */
@QuarkusTest
@DisplayName("TemplateBindingInvariantTest — 模板绑定服务层不变量 T1~T12")
class TemplateBindingInvariantTest {

    @Inject
    QuotationService quotationService;

    @Inject
    EntityManager em;

    private static final String TAG = "T0729BIND";

    // -----------------------------------------------------------------------
    // Fixture helpers
    // -----------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private UUID findCustomerId() {
        List<Object> rows = em.createNativeQuery("SELECT id FROM customer LIMIT 1").getResultList();
        assertFalse(rows.isEmpty(), "DB 无任何 customer，无法建报价单 fixture");
        return toUUID(rows.get(0));
    }

    @SuppressWarnings("unchecked")
    private UUID findSalesRepId() {
        List<Object> rows = em.createNativeQuery("SELECT id FROM \"user\" LIMIT 1").getResultList();
        assertFalse(rows.isEmpty(), "DB 无任何 user，无法建报价单 fixture");
        return toUUID(rows.get(0));
    }

    private static UUID toUUID(Object o) {
        if (o instanceof UUID u) return u;
        return UUID.fromString(o.toString());
    }

    /** 建一个最小 Template fixture，返回其 id。 */
    private UUID createTemplate(String kind, String status) {
        Template tpl = new Template();
        tpl.templateSeriesId = UUID.randomUUID();
        tpl.name = TAG + "-" + kind + "-" + status + "-" + UUID.randomUUID().toString().substring(0, 8);
        tpl.templateKind = kind;
        tpl.status = status;
        tpl.persist();
        return tpl.id;
    }

    private CreateQuotationRequest baseCreateRequest(UUID customerId) {
        CreateQuotationRequest req = new CreateQuotationRequest();
        req.customerId = customerId;
        req.name = TAG + "-报价单-" + UUID.randomUUID().toString().substring(0, 8);
        return req;
    }

    // -----------------------------------------------------------------------
    // create()
    // -----------------------------------------------------------------------

    @Test
    @TestTransaction
    @DisplayName("T1: create 绑 DRAFT 报价模板 → 400")
    void t1_create_bindDraftQuotationTemplate_rejected() {
        UUID customerId = findCustomerId();
        UUID salesRepId = findSalesRepId();
        UUID draftTplId = createTemplate("QUOTATION", "DRAFT");

        CreateQuotationRequest req = baseCreateRequest(customerId);
        req.customerTemplateId = draftTplId;

        BusinessException ex = assertThrows(BusinessException.class,
                () -> quotationService.create(req, salesRepId));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains(draftTplId.toString()), "错误信息应含 templateId: " + ex.getMessage());
    }

    @Test
    @TestTransaction
    @DisplayName("T2: create 绑 ARCHIVED 报价模板 → 400")
    void t2_create_bindArchivedQuotationTemplate_rejected() {
        UUID customerId = findCustomerId();
        UUID salesRepId = findSalesRepId();
        UUID archivedTplId = createTemplate("QUOTATION", "ARCHIVED");

        CreateQuotationRequest req = baseCreateRequest(customerId);
        req.customerTemplateId = archivedTplId;

        BusinessException ex = assertThrows(BusinessException.class,
                () -> quotationService.create(req, salesRepId));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains(archivedTplId.toString()), "错误信息应含 templateId: " + ex.getMessage());
    }

    @Test
    @TestTransaction
    @DisplayName("T3: create 绑不存在的核价模板 id → 400（覆盖 §2.4 行为变更：原 warn+静默忽略，现改 400）")
    void t3_create_bindNonExistentCostingTemplate_rejected() {
        UUID customerId = findCustomerId();
        UUID salesRepId = findSalesRepId();
        UUID nonExistentId = UUID.randomUUID();

        CreateQuotationRequest req = baseCreateRequest(customerId);
        req.costingTemplateId = nonExistentId;

        BusinessException ex = assertThrows(BusinessException.class,
                () -> quotationService.create(req, salesRepId));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains(nonExistentId.toString()), "错误信息应含 templateId: " + ex.getMessage());
    }

    @Test
    @TestTransaction
    @DisplayName("T4: create 把 COSTING 模板绑到 customerTemplateId → 400（类型错配）")
    void t4_create_bindCostingKindToCustomerTemplateField_rejected() {
        UUID customerId = findCustomerId();
        UUID salesRepId = findSalesRepId();
        UUID costingTplId = createTemplate("COSTING", "PUBLISHED");

        CreateQuotationRequest req = baseCreateRequest(customerId);
        req.customerTemplateId = costingTplId;

        BusinessException ex = assertThrows(BusinessException.class,
                () -> quotationService.create(req, salesRepId));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("COSTING"), "错误信息应含实际类型 COSTING: " + ex.getMessage());
    }

    @Test
    @TestTransaction
    @DisplayName("T5: create 把 QUOTATION 模板绑到 costingTemplateId → 400（反向类型错配）")
    void t5_create_bindQuotationKindToCostingTemplateField_rejected() {
        UUID customerId = findCustomerId();
        UUID salesRepId = findSalesRepId();
        UUID quotationTplId = createTemplate("QUOTATION", "PUBLISHED");

        CreateQuotationRequest req = baseCreateRequest(customerId);
        req.costingTemplateId = quotationTplId;

        BusinessException ex = assertThrows(BusinessException.class,
                () -> quotationService.create(req, salesRepId));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("QUOTATION"), "错误信息应含实际类型 QUOTATION: " + ex.getMessage());
    }

    @Test
    @TestTransaction
    @DisplayName("T6: create 绑正常 PUBLISHED（两个字段都给）→ 成功，且落库值正确")
    void t6_create_bindPublishedBothFields_success() {
        UUID customerId = findCustomerId();
        UUID salesRepId = findSalesRepId();
        UUID quotationTplId = createTemplate("QUOTATION", "PUBLISHED");
        UUID costingTplId = createTemplate("COSTING", "PUBLISHED");

        CreateQuotationRequest req = baseCreateRequest(customerId);
        req.customerTemplateId = quotationTplId;
        req.costingTemplateId = costingTplId;

        QuotationDTO dto = quotationService.create(req, salesRepId);

        assertEquals(quotationTplId, dto.customerTemplateId, "customerTemplateId 应落库为传入值");
        assertEquals(costingTplId, dto.costingCardTemplateId, "costingCardTemplateId 应落库为传入值");

        Quotation persisted = Quotation.findById(dto.id);
        assertNotNull(persisted);
        assertEquals(quotationTplId, persisted.customerTemplateId);
        assertEquals(costingTplId, persisted.costingCardTemplateId);
    }

    // -----------------------------------------------------------------------
    // saveDraft()
    // -----------------------------------------------------------------------

    @Test
    @TestTransaction
    @DisplayName("T7: saveDraft 维持原值，而该模板已被归档 → 放行（§2.2 防回归核心）")
    void t7_saveDraft_sameValueAfterArchive_allowed() {
        UUID customerId = findCustomerId();
        UUID salesRepId = findSalesRepId();
        UUID tplId = createTemplate("QUOTATION", "PUBLISHED");

        CreateQuotationRequest createReq = baseCreateRequest(customerId);
        createReq.customerTemplateId = tplId;
        QuotationDTO created = quotationService.create(createReq, salesRepId);
        assertEquals(tplId, created.customerTemplateId);

        // 模板归档（生产场景：用户在编辑期间模板被下线）
        Template tpl = Template.findById(tplId);
        assertNotNull(tpl);
        tpl.status = "ARCHIVED";

        SaveDraftRequest draftReq = new SaveDraftRequest();
        draftReq.customerTemplateId = tplId; // 前端回传同一个 templateId（维持原值）

        assertDoesNotThrow(() -> quotationService.saveDraft(created.id, draftReq),
                "模板归档后，维持原值的 saveDraft 必须放行，否则历史草稿单被锁死");

        Quotation persisted = Quotation.findById(created.id);
        assertEquals(tplId, persisted.customerTemplateId, "customerTemplateId 应仍为原模板");
    }

    @Test
    @TestTransaction
    @DisplayName("T8: saveDraft 换绑到另一个 DRAFT 模板 → 400")
    void t8_saveDraft_switchToDraftTemplate_rejected() {
        UUID customerId = findCustomerId();
        UUID salesRepId = findSalesRepId();
        UUID originalTplId = createTemplate("QUOTATION", "PUBLISHED");
        UUID draftTplId = createTemplate("QUOTATION", "DRAFT");

        CreateQuotationRequest createReq = baseCreateRequest(customerId);
        createReq.customerTemplateId = originalTplId;
        QuotationDTO created = quotationService.create(createReq, salesRepId);

        SaveDraftRequest draftReq = new SaveDraftRequest();
        draftReq.customerTemplateId = draftTplId; // 换绑到另一个未发布模板

        BusinessException ex = assertThrows(BusinessException.class,
                () -> quotationService.saveDraft(created.id, draftReq));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains(draftTplId.toString()), "错误信息应含 templateId: " + ex.getMessage());
    }

    @Test
    @TestTransaction
    @DisplayName("T9: saveDraft 不传模板字段（null）→ 放行且不清空已有值")
    void t9_saveDraft_nullTemplateField_doesNotClearExisting() {
        UUID customerId = findCustomerId();
        UUID salesRepId = findSalesRepId();
        UUID tplId = createTemplate("QUOTATION", "PUBLISHED");

        CreateQuotationRequest createReq = baseCreateRequest(customerId);
        createReq.customerTemplateId = tplId;
        QuotationDTO created = quotationService.create(createReq, salesRepId);

        SaveDraftRequest draftReq = new SaveDraftRequest();
        draftReq.customerTemplateId = null; // 本次调用未传该字段

        assertDoesNotThrow(() -> quotationService.saveDraft(created.id, draftReq));

        Quotation persisted = Quotation.findById(created.id);
        assertEquals(tplId, persisted.customerTemplateId, "customerTemplateId 不应被 null 清空");
    }

    // -----------------------------------------------------------------------
    // copy()
    // -----------------------------------------------------------------------

    @Test
    @TestTransaction
    @DisplayName("T10: copy 同模板复制，源模板已 ARCHIVED → 放行（豁免生效）")
    void t10_copy_sameTemplateAfterArchive_allowed() {
        UUID customerId = findCustomerId();
        UUID salesRepId = findSalesRepId();
        UUID tplId = createTemplate("QUOTATION", "PUBLISHED");

        CreateQuotationRequest createReq = baseCreateRequest(customerId);
        createReq.customerTemplateId = tplId;
        QuotationDTO source = quotationService.create(createReq, salesRepId);

        // 模板归档
        Template tpl = Template.findById(tplId);
        assertNotNull(tpl);
        tpl.status = "ARCHIVED";

        QuotationDTO copyDto = assertDoesNotThrow(() -> quotationService.copy(source.id, null),
                "同模板复制（templateId=null → 落回 source.customerTemplateId）必须豁免状态校验");
        assertEquals(tplId, copyDto.customerTemplateId, "复制单应延续同一模板 id");
    }

    @Test
    @TestTransaction
    @DisplayName("T11: copy 换模板到 DRAFT 模板 → 400")
    void t11_copy_switchToDraftTemplate_rejected() {
        UUID customerId = findCustomerId();
        UUID salesRepId = findSalesRepId();
        UUID originalTplId = createTemplate("QUOTATION", "PUBLISHED");
        UUID draftTplId = createTemplate("QUOTATION", "DRAFT");

        CreateQuotationRequest createReq = baseCreateRequest(customerId);
        createReq.customerTemplateId = originalTplId;
        QuotationDTO source = quotationService.create(createReq, salesRepId);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> quotationService.copy(source.id, draftTplId));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains(draftTplId.toString()), "错误信息应含 templateId: " + ex.getMessage());
    }

    @Test
    @TestTransaction
    @DisplayName("T12: copy 换模板到正常 PUBLISHED → 成功")
    void t12_copy_switchToPublishedTemplate_success() {
        UUID customerId = findCustomerId();
        UUID salesRepId = findSalesRepId();
        UUID originalTplId = createTemplate("QUOTATION", "PUBLISHED");
        UUID newTplId = createTemplate("QUOTATION", "PUBLISHED");

        CreateQuotationRequest createReq = baseCreateRequest(customerId);
        createReq.customerTemplateId = originalTplId;
        QuotationDTO source = quotationService.create(createReq, salesRepId);

        QuotationDTO copyDto = quotationService.copy(source.id, newTplId);
        assertEquals(newTplId, copyDto.customerTemplateId, "复制单应绑定新模板 id");

        Quotation persisted = Quotation.findById(copyDto.id);
        assertNotNull(persisted);
        assertEquals(newTplId, persisted.customerTemplateId);
    }
}
