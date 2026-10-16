package com.cpq.template.service;

import com.cpq.common.exception.BusinessException;
import com.cpq.quotation.dto.CreateQuotationRequest;
import com.cpq.quotation.dto.SaveDraftRequest;
import com.cpq.quotation.dto.QuotationDTO;
import com.cpq.quotation.entity.Quotation;
import com.cpq.quotation.service.QuotationService;
import com.cpq.template.entity.Template;
import com.cpq.template.entity.TemplateComponentSnapshot;
import com.cpq.template.dto.TemplateDTO;
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
 * Contract tests for the published-template soft-delete state.
 *
 * <p>These tests are deliberately service-level and use {@link TestTransaction}; no shared
 * template is mutated and every fixture rolls back with the test transaction.  The assertions
 * are derived from task-261015 AC-1, AC-2, AC-3, AC-6, AC-7 and AC-8 rather than from a
 * particular implementation (the delete operation may be exposed through REST or a service
 * call).
 */
@QuarkusTest
@DisplayName("task-261015: template soft-delete contract")
class TemplateSoftDeleteContractTest {

    private static final String TAG = "TMPLDEL-261015-S1-";

    @Inject
    TemplateService templateService;

    @Inject
    QuotationService quotationService;

    @Inject
    EntityManager em;

    @Inject
    PublishedTemplateReader publishedTemplateReader;

    private Template template(String status, String suffix) {
        Template t = new Template();
        t.templateSeriesId = UUID.randomUUID();
        t.name = TAG + suffix + "-" + UUID.randomUUID().toString().substring(0, 8);
        t.templateKind = "QUOTATION";
        t.status = status;
        t.componentsSnapshot = "[{\"fixture\":\"" + suffix + "\"}]";
        t.persist();
        em.flush();
        return t;
    }

    private UUID anyCustomerId() {
        List<?> rows = em.createNativeQuery("SELECT id FROM customer LIMIT 1").getResultList();
        assertFalse(rows.isEmpty(), "测试数据库必须至少有一个 customer 夹具");
        return asUuid(rows.get(0));
    }

    private UUID anyUserId() {
        List<?> rows = em.createNativeQuery("SELECT id FROM \"user\" LIMIT 1").getResultList();
        assertFalse(rows.isEmpty(), "测试数据库必须至少有一个 user 夹具");
        return asUuid(rows.get(0));
    }

    private static UUID asUuid(Object value) {
        return value instanceof UUID uuid ? uuid : UUID.fromString(value.toString());
    }

    @Test
    @TestTransaction
    @DisplayName("AC-1: PUBLISHED 删除只改成 DELETED，模板行与快照保留")
    void publishedDelete_isSoftAndRetainsSnapshot() {
        Template source = template("PUBLISHED", "published");
        UUID id = source.id;
        String snapshot = source.componentsSnapshot;
        TemplateComponentSnapshot frozen = new TemplateComponentSnapshot();
        frozen.templateId = id;
        frozen.templateComponentId = UUID.randomUUID();
        frozen.componentId = UUID.randomUUID();
        frozen.sortOrder = 0;
        frozen.tabName = "fixture";
        frozen.componentName = "fixture";
        frozen.componentCode = "TMPLDEL-FROZEN";
        frozen.persist();
        em.flush();
        long frozenRowsBefore = TemplateComponentSnapshot.count("templateId", id);

        templateService.delete(id);

        Template deleted = Template.findById(id);
        assertNotNull(deleted, "软删除不得物理删除 template 行");
        assertEquals("DELETED", deleted.status);
        assertEquals(snapshot, deleted.componentsSnapshot, "软删除不得清理已发布快照");
        assertEquals(frozenRowsBefore, TemplateComponentSnapshot.count("templateId", id),
                "软删除不得清理 template_component_snapshot 行");
    }

    @Test
    @TestTransaction
    @DisplayName("AC-10: DELETED 模板仍按冻结快照读取，不回落活表")
    void deletedTemplate_readerTreatsSnapshotAsFrozen() {
        Template source = template("DELETED", "reader");
        TemplateComponentSnapshot row = new TemplateComponentSnapshot();
        row.templateId = source.id;
        row.templateComponentId = UUID.randomUUID();
        row.componentId = UUID.randomUUID();
        row.sortOrder = 0;
        row.tabName = "reader-fixture";
        row.componentName = "Frozen component";
        row.componentCode = "TMPLDEL-READER";
        row.persist();
        em.flush();

        // The fixture's JSONB count matches the one frozen row.  A DELETED status must
        // therefore pass the same frozen-reader path as PUBLISHED/ARCHIVED.
        List<TemplateComponentSnapshot> rows = publishedTemplateReader.allTabsOf(source.id);
        assertEquals(1, rows.size());
        assertEquals("Frozen component", rows.get(0).componentName);
    }

    @Test
    @TestTransaction
    @DisplayName("AC-8: ARCHIVED/DELETED 不允许再次删除，且状态不被改写")
    void archivedOrDeleted_deleteIsRejected() {
        Template archived = template("ARCHIVED", "archived");
        BusinessException archivedError = assertThrows(BusinessException.class,
                () -> templateService.delete(archived.id));
        assertEquals(400, archivedError.getCode());
        assertEquals("ARCHIVED", ((Template) Template.findById(archived.id)).status);

        Template deleted = template("DELETED", "deleted");
        BusinessException deletedError = assertThrows(BusinessException.class,
                () -> templateService.delete(deleted.id));
        assertEquals(400, deletedError.getCode());
        assertEquals("DELETED", ((Template) Template.findById(deleted.id)).status);
    }

    @Test
    @TestTransaction
    @DisplayName("AC-3: 默认列表隐藏 DELETED，显式 DELETED 查询仍可得到历史模板")
    void list_defaultHidesDeleted_explicitStatusShowsDeleted() {
        Template deleted = template("DELETED", "list-deleted");
        Template published = template("PUBLISHED", "list-published");

        List<TemplateDTO> defaults = templateService.list(0, 100, null, null, null, null,
                TAG, null);
        assertTrue(defaults.stream().noneMatch(row -> deleted.id.equals(row.id)),
                "默认列表不得返回 DELETED 模板");
        assertTrue(defaults.stream().anyMatch(row -> published.id.equals(row.id)),
                "前置 PUBLISHED 夹具必须出现在默认列表，防止断言空跑");

        List<TemplateDTO> deletedRows = templateService.list(0, 100, null, null, null,
                "DELETED", TAG, null);
        assertTrue(deletedRows.stream().anyMatch(row -> deleted.id.equals(row.id)),
                "显式 DELETED 查询必须能看到历史模板");
    }

    @Test
    @TestTransaction
    @DisplayName("AC-7: DELETED 模板可创建新的 DRAFT，源模板保持 DELETED")
    void deletedSource_createNewDraft_createsIndependentDraft() {
        Template deleted = template("DELETED", "new-draft-source");

        BusinessException nonAdminError = assertThrows(BusinessException.class,
                () -> templateService.createNewDraft(deleted.id),
                "非管理员路径不得从 DELETED 模板创建新模板");
        assertEquals(403, nonAdminError.getCode());

        // The resource derives this flag from SYSTEM_ADMIN; passing true models that
        // authenticated administrator path without disabling RBAC globally in production.
        var draft = templateService.createNewDraft(deleted.id, true);

        assertNotNull(draft);
        assertEquals("DRAFT", draft.status);
        assertNotEquals(deleted.id, draft.id, "新草稿必须使用新的模板 id");
        assertEquals("DELETED", ((Template) Template.findById(deleted.id)).status,
                "创建新草稿不得恢复或改写源模板");
    }

    @Test
    @TestTransaction
    @DisplayName("AC-6: 已有 DRAFT 报价维持 DELETED 模板原绑定时仍可保存")
    void existingDraft_sameDeletedTemplate_saveIsAllowed() {
        UUID customerId = anyCustomerId();
        UUID userId = anyUserId();
        Template published = template("PUBLISHED", "draft-save");

        CreateQuotationRequest create = new CreateQuotationRequest();
        create.customerId = customerId;
        create.name = TAG + "draft-quote-" + UUID.randomUUID();
        create.customerTemplateId = published.id;
        QuotationDTO quotation = quotationService.create(create, userId);

        Template managed = Template.findById(published.id);
        managed.status = "DELETED";
        em.flush();

        SaveDraftRequest save = new SaveDraftRequest();
        save.customerTemplateId = published.id;
        assertDoesNotThrow(() -> quotationService.saveDraft(quotation.id, save));
        assertEquals(published.id, ((Quotation) Quotation.findById(quotation.id)).customerTemplateId);
    }

    @Test
    @TestTransaction
    @DisplayName("AC-2/AC-6: 新建报价或切换到 DELETED 模板必须拒绝")
    void deletedTemplate_newBindingIsRejected() {
        UUID customerId = anyCustomerId();
        UUID userId = anyUserId();
        Template deleted = template("DELETED", "new-binding");

        CreateQuotationRequest create = new CreateQuotationRequest();
        create.customerId = customerId;
        create.name = TAG + "new-quote-" + UUID.randomUUID();
        create.customerTemplateId = deleted.id;

        BusinessException createError = assertThrows(BusinessException.class,
                () -> quotationService.create(create, userId));
        assertEquals(400, createError.getCode());

        Template published = template("PUBLISHED", "switch-source");
        create.customerTemplateId = published.id;
        QuotationDTO quotation = quotationService.create(create, userId);

        SaveDraftRequest save = new SaveDraftRequest();
        save.customerTemplateId = deleted.id;
        BusinessException switchError = assertThrows(BusinessException.class,
                () -> quotationService.saveDraft(quotation.id, save));
        assertEquals(400, switchError.getCode());
    }
}
