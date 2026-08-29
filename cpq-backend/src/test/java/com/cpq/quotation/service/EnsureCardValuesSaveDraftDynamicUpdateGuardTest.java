package com.cpq.quotation.service;

import com.cpq.component.entity.Component;
import com.cpq.product.entity.Product;
import com.cpq.quotation.dto.SaveDraftRequest;
import com.cpq.quotation.entity.Quotation;
import com.cpq.quotation.entity.QuotationLineComponentData;
import com.cpq.quotation.entity.QuotationLineItem;
import com.cpq.quotation.entity.QuotationViewStructure;
import com.cpq.template.entity.Template;
import com.cpq.template.entity.TemplateComponent;
import com.cpq.template.entity.TemplateComponentSnapshot;
import com.cpq.system.entity.User;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.DisplayName;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

/**
 * repair-260828 · T-4（AC-6）：{@code @DynamicUpdate} 并发防线仍生效——warm(ensureCardValues)
 * 与 saveDraft 并发改<b>不同列</b>时不得互相整行覆盖。
 *
 * <p><b>为什么用确定性 latch 而不是纯线程赛跑</b>：3a69ca97 原实验用真线程赛跑,判据靠统计
 * 8 次里存活几次(OFF 0/8 → ON 8/8),因为时序不可控。本测试复用既有
 * {@link CardSnapshotConcurrencyProbe} 测试缝(与 {@code EnsureCardValuesEditConcurrencyTest}
 * 同款手法,该缝已在 3a69ca97 之后的代码里验证可靠)——它在 warm <b>已把 quoteCardValues
 * 赋到托管实体、尚未提交</b>的那一刻挂起,此时插入 saveDraft 写 annualVolume 并提交,再放行 warm
 * 提交。这样时序是<b>构造性确定</b>的,不依赖 JVM 调度赌概率——按 {@code testing.md} §4.1
 * "结构性修复少量重复即可"的标准,{@code @RepeatedTest} 跑 3 次即可,不需要凑 8 次统计。
 *
 * <p><b>断言的是状态不变量</b>(哨兵值 == 写入值),不是 delta。
 *
 * <p><b>还原实验</b>:本任务不改 {@code @DynamicUpdate} 注解本身(E-4 明令禁止),故"改回原样"
 * 无法通过改当前 repair 的代码触发;改用等价证伪——把断言反过来验证 harness 会正确抓到"整行覆盖"
 * 这种坏结果(证明断言不是摆设),而不是删除生产注解。过程见 test-report.md。
 */
@QuarkusTest
class EnsureCardValuesSaveDraftDynamicUpdateGuardTest {

    private static final String TAB_NAME = "T260828T4 Tab";
    private static final int INITIAL_ANNUAL_VOLUME = 1;
    private static final int SAVE_DRAFT_ANNUAL_VOLUME = 42;

    @Inject CardSnapshotService service;
    @Inject QuotationService quotationService;
    @Inject EntityManager em;

    @InjectMock CardSnapshotConcurrencyProbe concurrencyProbe;

    private Fixture fixture;
    private CountDownLatch releaseEnsure;
    private ExecutorService executor;

    @AfterEach
    void cleanup() {
        if (releaseEnsure != null) releaseEnsure.countDown();
        if (executor != null) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(30, TimeUnit.SECONDS)) executor.shutdownNow();
            } catch (InterruptedException interrupted) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        if (fixture == null) return;
        QuarkusTransaction.requiringNew().run(() -> {
            QuotationLineComponentData.delete("lineItemId", fixture.lineItemId());
            QuotationViewStructure.delete("quotationId", fixture.quotationId());
            QuotationLineItem.deleteById(fixture.lineItemId());
            Quotation.deleteById(fixture.quotationId());
            TemplateComponentSnapshot.delete("templateId", fixture.templateId());
            TemplateComponent.delete("templateId", fixture.templateId());
            Template.deleteById(fixture.templateId());
            Component.deleteById(fixture.componentId());
            Product.deleteById(fixture.productId());
            em.createNativeQuery("DELETE FROM customer WHERE id=:id").setParameter("id", fixture.customerId()).executeUpdate();
            User.deleteById(fixture.userId());
        });
    }

    @RepeatedTest(3)
    @DisplayName("T-4(AC-6): warm 在飞时 saveDraft 改 annualVolume 并提交,warm 随后提交不得把 annualVolume 冲回旧值")
    void saveDraftDuringWarm_annualVolumeSurvives() throws Exception {
        fixture = createOwnedFixture();
        CountDownLatch ensureBuilt = new CountDownLatch(1);
        releaseEnsure = new CountDownLatch(1);

        doAnswer(invocation -> {
            ensureBuilt.countDown();
            if (!releaseEnsure.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test did not release ensure barrier");
            }
            return null;
        }).when(concurrencyProbe).afterEnsureValuesBuilt(eq(fixture.quotationId()));

        executor = Executors.newFixedThreadPool(2);
        Future<Integer> ensureFuture = executor.submit(() -> service.ensureCardValues(fixture.quotationId()));

        assertTrue(ensureBuilt.await(30, TimeUnit.SECONDS),
                "warm(ensureCardValues) 必须先把值赋到托管实体、到达 afterEnsureValuesBuilt 屏障");

        // warm 在飞时,saveDraft 写不同列(annualVolume),必须能拿到锁并正常提交(不应被 warm 卡住/覆盖)。
        SaveDraftRequest req = new SaveDraftRequest();
        req.name = "T-4 saveDraft during warm";
        req.finalDiscountRate = new BigDecimal("100.00");
        SaveDraftRequest.LineItemDraft li = new SaveDraftRequest.LineItemDraft();
        li.id = fixture.lineItemId();
        li.templateId = fixture.templateId();
        li.productPartNo = "T260828T4-PN";
        li.productName = "T260828T4 product";
        li.subtotal = BigDecimal.ZERO.setScale(12);
        li.annualVolume = SAVE_DRAFT_ANNUAL_VOLUME;
        li.sortOrder = 0;
        req.lineItems = java.util.List.of(li);
        quotationService.saveDraft(fixture.quotationId(), req);

        long annualVolumeAfterSaveDraft = readAnnualVolume(fixture.lineItemId());
        assertEquals(SAVE_DRAFT_ANNUAL_VOLUME, annualVolumeAfterSaveDraft,
                "saveDraft 提交后(warm 尚未放行)annualVolume 应已经是新值(非空验证:saveDraft 真的生效了)");

        // 放行 warm,让它带着"旧内存快照"提交。
        releaseEnsure.countDown();
        Integer filled = ensureFuture.get(30, TimeUnit.SECONDS);
        assertNotNull(filled, "warm 应正常完成(不应因 saveDraft 并发而抛异常)");

        // 核心断言(AC-6 · 状态不变量):warm 提交后,annualVolume 必须仍是 saveDraft 写入的值,
        // 不能被 warm 的整行覆盖冲回 INITIAL_ANNUAL_VOLUME。
        long annualVolumeAfterWarmCommit = readAnnualVolume(fixture.lineItemId());
        System.out.printf("[T-4 AC-6] annualVolume: 初始=%d saveDraft后=%d warm提交后=%d(哨兵应保持=%d)%n",
                INITIAL_ANNUAL_VOLUME, annualVolumeAfterSaveDraft, annualVolumeAfterWarmCommit, SAVE_DRAFT_ANNUAL_VOLUME);
        assertEquals(SAVE_DRAFT_ANNUAL_VOLUME, annualVolumeAfterWarmCommit,
                "warm 提交后 annualVolume 哨兵必须仍存活(== " + SAVE_DRAFT_ANNUAL_VOLUME + ")," +
                "不应被 warm 的旧内存快照整行覆盖冲回 " + INITIAL_ANNUAL_VOLUME +
                "(@DynamicUpdate 防线削弱的直接证据)");

        // 附带诊断(非断言,不计入 AC-6 判据):warm 是否真的把 quoteCardValues 落库。
        // ⚠️ 实测该值为 NULL——warm 日志显示 "补算 1 行" 且 "[perf] ensure-cardvalues-write rows=1 updates=2"
        // (证明 warm 确实跑了完整的批量写路径,不是空跑),但最终读回 quote_card_values 却是 NULL。
        // 这与"annualVolume 哨兵存活"的核心判据(AC-6)相互独立、不冲突——本测试的证据范围到此为止,
        // 未继续深挖是否为 saveDraft 的失效化(invalidate)时序与 warm 写入时序的正常交错,还是别的问题,
        // 因为继续排查需要读 CardSnapshotService 的实现(测试工程师被禁止读),已如实记入 test-report.md
        // 的"未验证/待主线核实"条目,不在此断言真假。
        Object qcv = em.createNativeQuery(
                "SELECT quote_card_values::text FROM quotation_line_item WHERE id=:id")
                .setParameter("id", fixture.lineItemId()).getSingleResult();
        System.out.printf("[T-4 诊断-非AC6判据] warm 提交后 quote_card_values=%s(filled返回值=%d)%n", qcv, filled);
    }

    private long readAnnualVolume(UUID lineItemId) {
        return QuarkusTransaction.requiringNew().call(() -> {
            em.clear();
            Number n = (Number) em.createNativeQuery(
                    "SELECT annual_volume FROM quotation_line_item WHERE id=:id")
                    .setParameter("id", lineItemId).getSingleResult();
            return n.longValue();
        });
    }

    private Fixture createOwnedFixture() {
        return QuarkusTransaction.requiringNew().call(() -> {
            UUID customerId = UUID.randomUUID();
            em.createNativeQuery("INSERT INTO customer(id,code,name,level,region,industry,address) "
                    + "VALUES (:id,:code,:name,'GOLD','TEST','TEST','TEST')")
                .setParameter("id", customerId)
                .setParameter("code", "T260828T4-" + customerId.toString().substring(0, 8))
                .setParameter("name", "T-4 DynamicUpdate guard customer")
                .executeUpdate();

            User user = new User();
            String userSuffix = UUID.randomUUID().toString().replace("-", "");
            user.username = "t260828t4_" + userSuffix;
            user.fullName = "T-4 test user";
            user.email = "t260828t4_" + userSuffix + "@cpq-test.internal";
            user.passwordHash = "not-used-by-direct-service-test";
            user.role = "SALES_REP";
            user.status = "ACTIVE";
            user.isFirstLogin = false;
            user.failedLoginAttempts = 0;
            user.persist();
            em.flush();

            Component component = new Component();
            component.name = "T260828T4 precision component";
            component.code = "T260828T4-" + UUID.randomUUID().toString().substring(0, 8);
            component.fields = fieldsJson();
            component.formulas = "[]";
            component.rowKeyFields = "[\"rowKey\"]";
            component.persist();

            Template template = createPublishedTemplate(component);

            Product product = new Product();
            product.name = "T260828T4 product";
            product.partNo = "T260828T4-P-" + UUID.randomUUID().toString().substring(0, 8);
            product.category = "TEST";
            product.specification = "TEST";
            product.persist();

            Quotation quotation = new Quotation();
            quotation.quotationNumber = "T260828T4-" + UUID.randomUUID();
            quotation.customerId = customerId;
            quotation.salesRepId = user.id;
            quotation.name = "T-4 DynamicUpdate guard quotation";
            quotation.status = "DRAFT";
            quotation.customerTemplateId = template.id;
            quotation.finalDiscountRate = new BigDecimal("100.00");
            quotation.persist();

            QuotationLineItem line = new QuotationLineItem();
            line.quotationId = quotation.id;
            line.productId = product.id;
            line.templateId = template.id;
            line.productNameSnapshot = product.name;
            line.productPartNoSnapshot = product.partNo;
            line.productAttributeValues = "{}";
            line.subtotal = BigDecimal.ZERO;
            line.sortOrder = 0;
            line.compositeType = "SIMPLE";
            line.annualVolume = INITIAL_ANNUAL_VOLUME;
            line.discountBaseAmount = BigDecimal.ZERO;
            line.discountRateApplied = BigDecimal.ZERO;
            line.lineDiscountAmount = BigDecimal.ZERO;
            line.lineUnitPrice = BigDecimal.ZERO;
            line.lineFinalPrice = BigDecimal.ZERO;
            line.lineTotalAmount = BigDecimal.ZERO;
            line.quoteCardValues = null;
            line.persist();

            QuotationLineComponentData data = new QuotationLineComponentData();
            data.lineItemId = line.id;
            data.componentId = component.id;
            data.tabName = TAB_NAME;
            data.rowData = rowData();
            data.snapshotRows = snapshotRows();
            data.subtotal = BigDecimal.ZERO;
            data.sortOrder = 0;
            data.persist();

            return new Fixture(quotation.id, line.id, customerId, user.id, component.id, template.id, product.id);
        });
    }

    private Template createPublishedTemplate(Component component) {
        Template template = new Template();
        template.templateSeriesId = UUID.randomUUID();
        template.name = "T260828T4 quotation template";
        template.templateKind = "QUOTATION";
        template.status = "PUBLISHED";
        template.productAttributes = "[]";
        template.componentsSnapshot = componentSnapshotJson(component);
        template.sqlViewsSnapshot = "{}";
        template.templateSqlViewsSnapshot = "{}";
        template.excelViewConfig = "[]";
        template.persist();

        TemplateComponent mounted = new TemplateComponent();
        mounted.templateId = template.id;
        mounted.componentId = component.id;
        mounted.tabName = TAB_NAME;
        mounted.sortOrder = 0;
        mounted.persist();

        TemplateComponentSnapshot snapshot = new TemplateComponentSnapshot();
        snapshot.templateId = template.id;
        snapshot.templateComponentId = mounted.id;
        snapshot.componentId = component.id;
        snapshot.sortOrder = 0;
        snapshot.tabName = TAB_NAME;
        snapshot.componentName = component.name;
        snapshot.componentCode = component.code;
        snapshot.componentType = "NORMAL";
        snapshot.fields = component.fields;
        snapshot.formulas = component.formulas;
        snapshot.rowKeyFields = component.rowKeyFields;
        snapshot.persist();
        return template;
    }

    private static String fieldsJson() {
        return "[{\"name\":\"rowKey\",\"field_type\":\"INPUT_TEXT\",\"sort_order\":0},"
            + "{\"name\":\"amount\",\"field_type\":\"INPUT_NUMBER\",\"sort_order\":1}]";
    }

    private static String componentSnapshotJson(Component component) {
        return "[{\"id\":\"" + UUID.randomUUID() + "\",\"componentId\":\"" + component.id
            + "\",\"componentName\":\"" + component.name + "\",\"componentCode\":\""
            + component.code + "\",\"componentType\":\"NORMAL\",\"tabName\":\"" + TAB_NAME
            + "\",\"sortOrder\":0,\"fields\":" + fieldsJson()
            + ",\"formulas\":[],\"formula_assignments\":{}}]";
    }

    private static String rowData() {
        return "[{\"rowKey\":\"R0\",\"amount\":\"1.111111111111\"}]";
    }

    private static String snapshotRows() {
        return "[{\"driverRow\":{\"rowKey\":\"R0\",\"amount\":\"1.111111111111\"},\"basicDataValues\":{}}]";
    }

    private record Fixture(
            UUID quotationId,
            UUID lineItemId,
            UUID customerId,
            UUID userId,
            UUID componentId,
            UUID templateId,
            UUID productId) {
    }
}
