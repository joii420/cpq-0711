package com.cpq.template.service;

import com.cpq.common.exception.BusinessException;
import com.cpq.component.entity.Component;
import com.cpq.template.entity.Template;
import com.cpq.template.entity.TemplateComponent;
import com.cpq.template.entity.TemplateComponentSnapshot;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Status;
import jakarta.transaction.TransactionManager;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * repair-0814 D-2 —— {@code TemplateService.publish()} 的「核价模板最多一个 BOM 树页签」
 * 状态不变量断言。
 *
 * <p><b>本类存在的首要理由是 {@link #netCountUnchanged_swappingWhichTabIsTree_isAllowed}（AC-10）</b>：
 * 它是防止实现漂回「与上一版快照 diff」这个已被否掉的参照系的专项闸。delta 式实现
 * （「找出 tab_type 从非 BOM 变成 BOM 的页签就报警」）在该用例上<b>必然</b>假阳性，
 * 因为那里确实存在一个「非BOM→BOM」的页签，只是同时另一个「BOM→非BOM」，净数量没变。
 *
 * <p>约束本身不是新规则：{@code TemplateComponentService.addComponent} 早有同款校验
 * （「一个核价模板最多一个核价树页签」），本次只是补到 publish 这个漏掉的入口。
 * 因此本类构造数据时<b>刻意绕开</b> {@code addComponent}，直接 persist {@code TemplateComponent}
 * ——否则在前置阶段就被拦住，测不到 publish 这道闸。
 */
@QuarkusTest
class TemplateServiceTreeTabInvariantTest {

    @Inject
    TemplateService templateService;

    @Inject
    TransactionManager txManager;

    // ---- 夹具 ----

    private Component component(String name, boolean tree) {
        Component c = new Component();
        c.name = name;
        c.code = "COMP-TEST-" + UUID.randomUUID().toString().substring(0, 8);
        c.fields = "[]";
        c.formulas = "[]";
        c.excelColumns = "[]";
        c.columnCount = 0;
        c.componentType = "NORMAL";
        c.status = "ACTIVE";
        c.bomRecursiveExpand = tree;
        c.tabType = tree ? "BOM" : null;
        c.createdAt = OffsetDateTime.now();
        c.updatedAt = OffsetDateTime.now();
        c.persist();
        return c;
    }

    private Template draftTemplate(String kind, String name) {
        Template t = new Template();
        t.templateSeriesId = UUID.randomUUID();
        t.name = name;
        t.version = "v1.0";
        t.templateKind = kind;
        t.status = "DRAFT";
        t.createdAt = OffsetDateTime.now();
        t.updatedAt = OffsetDateTime.now();
        t.persist();
        return t;
    }

    /** 绕开 TemplateComponentService.addComponent 的前置校验，直接建关联。 */
    private void bind(Template t, Component c, int sortOrder, String tabName) {
        TemplateComponent tc = new TemplateComponent();
        tc.templateId = t.id;
        tc.componentId = c.id;
        tc.tabName = tabName;
        tc.sortOrder = sortOrder;
        tc.createdAt = OffsetDateTime.now();
        tc.persist();
    }

    private long frozenTreeTabCount(UUID templateId) {
        return TemplateComponentSnapshot.<TemplateComponentSnapshot>list("templateId", templateId)
                .stream().filter(s -> Boolean.TRUE.equals(s.bomRecursiveExpand)).count();
    }

    // ---- 用例 ----

    /** TC-09（AC-8）：核价模板含 2 个树页签 → publish 400，且事务回滚（快照零行、仍 DRAFT）。 */
    @Test
    @TestTransaction
    void costingTemplateWithTwoTreeTabs_publishRejected() throws Exception {
        Template t = draftTemplate("COSTING", "核价模板-双树页签");
        bind(t, component("物料BOM", true), 0, "物料BOM");
        bind(t, component("物料与元素BOM", true), 1, "物料与元素BOM");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> templateService.publish(t.id, null));

        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("最多只能有一个 BOM 树页签"), ex.getMessage());
        assertTrue(ex.getMessage().contains("物料BOM"), "文案须点名冲突页签: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("物料与元素BOM"), "文案须点名冲突页签: " + ex.getMessage());

        assertEquals("DRAFT", ((Template) Template.findById(t.id)).status, "被拦后模板须停留 DRAFT");

        // 事务回滚证据 —— 这里【不能】断言「快照行数 = 0」。
        //
        // 原因是 @TestTransaction 的测试假象，不是产品缺陷：@TestTransaction 让【测试方法】
        // 拥有事务，publish() 的 @Transactional(REQUIRED) 只是加入它；assertThrows 又把异常
        // 吞在测试内部，外层事务此刻并未结束，故 persistSnapshotRows 刚插的 2 行对同一事务
        // 仍然可见。生产环境不同：TemplateResource.publish() 不带 @Transactional，
        // TemplateService.publish() 自己就是事务边界，而 BusinessException extends
        // RuntimeException（unchecked）→ JTA 自动回滚，快照不会落库。
        //
        // 能在测试里如实验证的是「事务已被标记为只回滚」——这正是 REQUIRED 加入既有事务时，
        // 拦截器对 RuntimeException 的处理结果，也是生产环境最终回滚的同一机制。
        assertEquals(Status.STATUS_MARKED_ROLLBACK, txManager.getStatus(),
                "publish 抛 BusinessException 后事务须被标记为只回滚（生产环境据此真回滚）");
    }

    /**
     * TC-10（AC-10）★ <b>防实现漂回 delta 判据的专项闸</b>。
     *
     * <p>场景：上一版快照里 A 是树、B 非树；本次把 A 改成非树、B 改成树 —— 净树页签数<b>仍是 1</b>，
     * 完全合法，必须放行。
     *
     * <p>若有人把实现改成「与上一版 diff、发现某页签 tab_type 从非 BOM 变成 BOM 就报警」，
     * 这里的 B 正是这样一个页签 → 该实现必在本用例上假阳性失败。
     * <b>本用例变红 = 实现用错了参照系，打回重做，不要改用例。</b>
     */
    @Test
    @TestTransaction
    void netCountUnchanged_swappingWhichTabIsTree_isAllowed() {
        Template t = draftTemplate("COSTING", "核价模板-换树页签");
        Component a = component("页签A", false);   // 本次：非树（上一版是树）
        Component b = component("页签B", true);    // 本次：树（上一版非树）
        bind(t, a, 0, "页签A");
        bind(t, b, 1, "页签B");

        // 伪造「上一版快照」：A 是树、B 非树 —— delta 式实现会拿它来比对
        TemplateComponentSnapshot prevA = new TemplateComponentSnapshot();
        prevA.templateId = t.id;
        prevA.templateComponentId = UUID.randomUUID();
        prevA.componentId = a.id;
        prevA.sortOrder = 0;
        prevA.tabName = "页签A";
        prevA.bomRecursiveExpand = true;          // 上一版 A 是树
        prevA.tabType = "BOM";
        prevA.persist();

        TemplateComponentSnapshot prevB = new TemplateComponentSnapshot();
        prevB.templateId = t.id;
        prevB.templateComponentId = UUID.randomUUID();
        prevB.componentId = b.id;
        prevB.sortOrder = 1;
        prevB.tabName = "页签B";
        prevB.bomRecursiveExpand = false;         // 上一版 B 非树
        prevB.persist();

        // 净树页签数仍为 1 → 必须放行
        assertDoesNotThrow(() -> templateService.publish(t.id, null),
                "净树页签数未变（仍为 1）时必须放行；此处失败 = 实现用了 delta 判据，应打回");
        assertEquals(1, frozenTreeTabCount(t.id), "冻结后树页签数应为 1（B）");
    }

    /** TC-11（AC-9）：恰好 1 个树页签 → 正常发布，不误伤。 */
    @Test
    @TestTransaction
    void costingTemplateWithExactlyOneTreeTab_publishes() {
        Template t = draftTemplate("COSTING", "核价模板-单树页签");
        bind(t, component("物料BOM", true), 0, "物料BOM");
        bind(t, component("工序", false), 1, "工序");

        assertDoesNotThrow(() -> templateService.publish(t.id, null));
        assertEquals(1, frozenTreeTabCount(t.id));
    }

    /** TC-12（AC-9）：约束只对 COSTING —— 报价模板挂 2 个树页签照常发布。 */
    @Test
    @TestTransaction
    void quotationTemplateWithTwoTreeTabs_publishesFine() {
        Template t = draftTemplate("QUOTATION", "报价模板-双树页签");
        bind(t, component("报价树1", true), 0, "报价树1");
        bind(t, component("报价树2", true), 1, "报价树2");

        assertDoesNotThrow(() -> templateService.publish(t.id, null),
                "树页签数约束只对 COSTING 生效，报价模板不受限");
        assertEquals(2, frozenTreeTabCount(t.id));
    }

    /** TC-13（边界）：0 个树页签也合法（≤1 含 0）。 */
    @Test
    @TestTransaction
    void costingTemplateWithZeroTreeTabs_publishes() {
        Template t = draftTemplate("COSTING", "核价模板-无树页签");
        bind(t, component("工序", false), 0, "工序");

        assertDoesNotThrow(() -> templateService.publish(t.id, null));
        assertEquals(0, frozenTreeTabCount(t.id));
    }

    /**
     * TC-14（救援路径不砖化）：违规模板走 {@code archive()} 补冻时<b>不阻断</b>，只记 WARN。
     *
     * <p>理由见 {@code TemplateService#assertAtMostOneTreeTab} 末段：archive/freeze 是救援路径，
     * 硬拦会让存量违规模板既不能冻结（不能渲染）又不能编辑（非 DRAFT 不许改 tc）= 彻底砖化。
     */
    @Test
    @TestTransaction
    void archiveAutoFreeze_withTwoTreeTabs_isNotBlocked() {
        Template t = draftTemplate("COSTING", "核价模板-归档补冻双树");
        bind(t, component("树1", true), 0, "树1");
        bind(t, component("树2", true), 1, "树2");
        // 直接置为 PUBLISHED 且不落快照 —— 模拟「存量违规 + 未冻结」
        t.status = "PUBLISHED";
        t.persist();

        assertDoesNotThrow(() -> templateService.archive(t.id, true),
                "救援路径不得因树页签超限而阻断（会砖化存量）");
        assertEquals("ARCHIVED", ((Template) Template.findById(t.id)).status);
        assertEquals(2, frozenTreeTabCount(t.id), "补冻照常落 2 行（仅记 WARN，不改数据）");
    }

    /** 回归：QUOTATION 模板的既有发布行为不受本次改动影响（1 个树页签）。 */
    @Test
    @TestTransaction
    void quotationTemplateWithOneTreeTab_publishesFine() {
        Template t = draftTemplate("QUOTATION", "报价模板-单树页签");
        bind(t, component("报价树", true), 0, "报价树");
        assertDoesNotThrow(() -> templateService.publish(t.id, null));
        assertEquals(1, frozenTreeTabCount(t.id));
    }
}
