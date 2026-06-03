package com.cpq.template;

import com.cpq.template.dto.QuoteImportAutoDefaults;
import com.cpq.template.service.TemplateService;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * computeAutoDefaults 6 分支验收。每个用例自建隔离的 category / customer / template /
 * quotation 数据,@TestTransaction 跑完回滚,互不污染。
 */
@QuarkusTest
class AutoDefaultsServiceTest {

    @Inject TemplateService templateService;
    @Inject EntityManager em;

    // ---- fixture helpers(native SQL,避免依赖各实体构造细节) ----

    private UUID newCategory(String name) {
        UUID id = UUID.randomUUID();
        em.createNativeQuery(
                "INSERT INTO product_category(id, code, name, status, sort_order, created_at, updated_at) " +
                "VALUES (:id, :code, :name, 'ACTIVE', 0, NOW(), NOW())")
                .setParameter("id", id)
                .setParameter("code", "AD_" + id.toString().substring(0, 8))
                .setParameter("name", name)
                .executeUpdate();
        return id;
    }

    private UUID newCustomer() {
        UUID id = UUID.randomUUID();
        String suffix = id.toString().substring(0, 8);
        // customer NOT NULL 列: name/code/level/accumulated_amount/status/version(@Version)/时间戳
        em.createNativeQuery(
                "INSERT INTO customer(id, name, code, level, accumulated_amount, status, version, created_at, updated_at) " +
                "VALUES (:id, :name, :code, 'STANDARD', 0, 'ACTIVE', 0, NOW(), NOW())")
                .setParameter("id", id)
                .setParameter("name", "AD客户_" + suffix)
                .setParameter("code", "ADC_" + suffix)
                .executeUpdate();
        return id;
    }

    /**
     * 无历史分支依赖服务端 `name='默认分类'` 的解析,必须保证测试模板挂在「服务会解析到的那一行」上。
     * select-or-create:复用已存在的默认分类(seed 通常已有一行),没有才新建,避免出现两行同名导致 firstResult() 歧义。
     */
    private UUID defaultCategoryId() {
        var rs = em.createNativeQuery(
                "SELECT id FROM product_category WHERE name = '默认分类' LIMIT 1").getResultList();
        return rs.isEmpty() ? newCategory("默认分类") : (UUID) rs.get(0);
    }

    /** quotation.sales_rep_id 是 NOT NULL FK → "user"(id),需预置一个销售。 */
    private UUID newSalesRep() {
        UUID id = UUID.randomUUID();
        String suffix = id.toString().substring(0, 8);
        em.createNativeQuery(
                "INSERT INTO \"user\"(id, username, full_name, email, password_hash, role, status, " +
                "is_first_login, failed_login_attempts, created_at, updated_at) " +
                "VALUES (:id, :u, '自动测试用户', :e, 'x', 'SALES_REP', 'ACTIVE', false, 0, NOW(), NOW())")
                .setParameter("id", id)
                .setParameter("u", "ad_" + suffix)
                .setParameter("e", "ad_" + suffix + "@test.local")
                .executeUpdate();
        return id;
    }

    /** 插一条模板版本;customerId 传 null = 通用;publishedOffsetSec 控制 published_at 先后。 */
    private UUID newTemplate(UUID seriesId, UUID categoryId, UUID customerId,
                            String kind, String status, String version, int publishedOffsetSec) {
        UUID id = UUID.randomUUID();
        em.createNativeQuery(
                "INSERT INTO template(id, template_series_id, name, version, category_id, customer_id, " +
                "template_kind, status, template_sql_views_snapshot, formulas, product_attributes, subtotal_formula, " +
                "published_at, created_at, updated_at) " +
                "VALUES (:id, :sid, :name, :ver, :cat, :cust, :kind, :status, '{}', '[]', '[]', '[]', " +
                "NOW() + (:off || ' seconds')::interval, NOW(), NOW())")
                .setParameter("id", id)
                .setParameter("sid", seriesId)
                .setParameter("name", "T_" + version)
                .setParameter("ver", version)
                .setParameter("cat", categoryId)
                .setParameter("cust", customerId)
                .setParameter("kind", kind)
                .setParameter("status", status)
                .setParameter("off", publishedOffsetSec)
                .executeUpdate();
        return id;
    }

    private void newQuotation(UUID customerId, UUID customerTemplateId, int createdOffsetSec) {
        UUID rep = newSalesRep();
        UUID id = UUID.randomUUID();
        // quotation NOT NULL 列: quotation_number(unique)/customer_id/name/sales_rep_id/status/tax_rate/tax_amount/时间戳
        em.createNativeQuery(
                "INSERT INTO quotation(id, quotation_number, customer_id, name, sales_rep_id, customer_template_id, " +
                "status, tax_rate, tax_amount, created_at, updated_at) " +
                "VALUES (:id, :qn, :cust, 'AD报价单', :rep, :tpl, 'DRAFT', 13, 0, " +
                "NOW() + (:off || ' seconds')::interval, NOW())")
                .setParameter("id", id)
                .setParameter("qn", "ADQ_" + id.toString().substring(0, 8))
                .setParameter("cust", customerId)
                .setParameter("rep", rep)
                .setParameter("tpl", customerTemplateId)
                .setParameter("off", createdOffsetSec)
                .executeUpdate();
    }

    // ---- 6 分支 ----

    @Test
    @TestTransaction
    void branch1_hasHistory_picksLatestPublishedOfUsedSeries() {
        UUID cat = newCategory("AD分类1");
        UUID cust = newCustomer();
        UUID series = UUID.randomUUID();
        UUID v1 = newTemplate(series, cat, cust, "QUOTATION", "PUBLISHED", "v1", 0);
        UUID v2 = newTemplate(series, cat, cust, "QUOTATION", "PUBLISHED", "v2", 100); // 同线更新版本
        newQuotation(cust, v1, 0); // 上次用 v1
        em.flush();

        QuoteImportAutoDefaults d = templateService.computeAutoDefaults(cust);

        assertEquals("LAST_USED", d.customerTemplateSource);
        assertEquals(v2, d.customerTemplateId, "应取该线最新发布版本 v2,而非历史所用 v1");
        assertEquals(cat, d.categoryId);
    }

    // 设计:branch2~4 用「历史钉到隔离分类 + 上次线归档」走 fallback 路径,categoryId 完全受控,
    // 不依赖共享「默认分类」内容,避免 seed/其他数据污染断言。branch5 专门验证「无历史→解析默认分类」。

    @Test
    @TestTransaction
    void branch2_archivedSeries_fallbackCustomerSpecific() {
        UUID cat = newCategory("AD分类2");
        UUID cust = newCustomer();
        UUID archived = newTemplate(UUID.randomUUID(), cat, cust, "QUOTATION", "ARCHIVED", "v1", 0);
        newQuotation(cust, archived, 0); // 上次用的线现已整条归档
        UUID specific = newTemplate(UUID.randomUUID(), cat, cust, "QUOTATION", "PUBLISHED", "v1", 50);
        em.flush();

        QuoteImportAutoDefaults d = templateService.computeAutoDefaults(cust);

        assertEquals("CUSTOMER_SPECIFIC_FALLBACK", d.customerTemplateSource);
        assertEquals(specific, d.customerTemplateId);
        assertEquals(cat, d.categoryId, "分类仍从历史模板反推");
    }

    @Test
    @TestTransaction
    void branch3_archivedSeries_onlyGeneral_fallbackGeneral() {
        UUID cat = newCategory("AD分类3");
        UUID cust = newCustomer();
        UUID archived = newTemplate(UUID.randomUUID(), cat, cust, "QUOTATION", "ARCHIVED", "v1", 0);
        newQuotation(cust, archived, 0);
        UUID general = newTemplate(UUID.randomUUID(), cat, null, "QUOTATION", "PUBLISHED", "G1", 50);
        em.flush();

        QuoteImportAutoDefaults d = templateService.computeAutoDefaults(cust);

        assertEquals("GENERAL_FALLBACK", d.customerTemplateSource);
        assertEquals(general, d.customerTemplateId);
        assertEquals(cat, d.categoryId);
    }

    @Test
    @TestTransaction
    void branch4_archivedSeries_nothingElse_none() {
        UUID cat = newCategory("AD分类4");
        UUID cust = newCustomer();
        UUID archived = newTemplate(UUID.randomUUID(), cat, cust, "QUOTATION", "ARCHIVED", "v1", 0);
        newQuotation(cust, archived, 0);
        em.flush();

        QuoteImportAutoDefaults d = templateService.computeAutoDefaults(cust);

        assertEquals("NONE", d.customerTemplateSource);
        assertNull(d.customerTemplateId);
        assertEquals(cat, d.categoryId, "分类仍从历史模板反推");
    }

    @Test
    @TestTransaction
    void branch5_noHistory_resolvesDefaultCategory_multipleSpecific_picksLatest() {
        UUID cat = defaultCategoryId(); // 服务在无历史时会解析到这一行
        UUID cust = newCustomer();       // 全新客户:其唯一的客户专属就是下面两条, 不会被其他数据遮蔽
        newTemplate(UUID.randomUUID(), cat, cust, "QUOTATION", "PUBLISHED", "A", 0);
        UUID newer = newTemplate(UUID.randomUUID(), cat, cust, "QUOTATION", "PUBLISHED", "B", 100);
        em.flush();

        QuoteImportAutoDefaults d = templateService.computeAutoDefaults(cust);

        assertEquals(cat, d.categoryId, "无历史应解析到默认分类");
        assertEquals("CUSTOMER_SPECIFIC_FALLBACK", d.customerTemplateSource);
        assertEquals(newer, d.customerTemplateId, "无历史多条客户专属取最新发布");
    }

    @Test
    @TestTransaction
    void branch6_costing_specificBeatsGeneral_andNone() {
        // 用历史把 categoryId 钉到隔离分类 cat, quote 侧给个已发布模板让其有历史
        UUID cat = newCategory("AD分类6");
        UUID cust = newCustomer();
        UUID q = newTemplate(UUID.randomUUID(), cat, cust, "QUOTATION", "PUBLISHED", "Q", 0);
        newQuotation(cust, q, 0);
        newTemplate(UUID.randomUUID(), cat, null, "COSTING", "PUBLISHED", "CG", 0);          // 通用核价
        UUID custCosting = newTemplate(UUID.randomUUID(), cat, cust, "COSTING", "PUBLISHED", "CC", 10); // 客户专属核价
        em.flush();

        QuoteImportAutoDefaults d = templateService.computeAutoDefaults(cust);

        assertEquals(cat, d.categoryId);
        assertEquals("CUSTOMER_SPECIFIC", d.costingTemplateSource);
        assertEquals(custCosting, d.costingTemplateId, "客户专属核价优先于通用");

        // 另一客户:历史钉到隔离分类 cat2(无核价模板)→ 核价 NONE
        UUID cat2 = newCategory("AD分类6b");
        UUID cust2 = newCustomer();
        UUID q2 = newTemplate(UUID.randomUUID(), cat2, cust2, "QUOTATION", "PUBLISHED", "Q2", 0);
        newQuotation(cust2, q2, 0);
        em.flush();

        QuoteImportAutoDefaults d2 = templateService.computeAutoDefaults(cust2);
        assertEquals("NONE", d2.costingTemplateSource);
        assertNull(d2.costingTemplateId);
    }
}
