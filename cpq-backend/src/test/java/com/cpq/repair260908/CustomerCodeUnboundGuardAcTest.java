package com.cpq.repair260908;

import com.cpq.component.dto.RuntimeContext;
import com.cpq.component.entity.Component;
import com.cpq.component.entity.ComponentSqlView;
import com.cpq.component.service.CostingTreeSqlValidator;
import com.cpq.datasource.sqlview.QuoteViewValidationService;
import com.cpq.datasource.sqlview.SqlViewExecutor;
import com.cpq.datasource.sqlview.SqlViewRuntimeContext;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * repair-260908 · <b>测试分片 S-2（执行器 · 私有写）</b> —— 只认领 <b>AC-5</b>，正反两面。
 *
 * <h3>AC-5 原文（{@code 问题说明.md §⑥ 阳性类}，逐字）</h3>
 * <blockquote>
 * 前置：任一含 {@code :customerCode} 的视图 ｜ 操作：在不提供 {@code customerCode} 的上下文下执行 ｜
 * 断言：抛 <b>400</b>，错误文案指明缺哪个参数；🚫 <b>不是返 0 行</b>。
 * 同时验证 {@code /preview}、{@code QuoteViewValidationService}、{@code CostingTreeSqlValidator}
 * 三条路径<b>仍正常</b>（它们各自做字面量替换）。
 * </blockquote>
 *
 * <h3>本类覆盖的两面</h3>
 * <table border="1">
 *   <tr><th>面</th><th>测试方法</th></tr>
 *   <tr><td><b>正面</b>：不提供 customerCode ⇒ 400，不是静默 0 行</td>
 *       <td>{@link #t02_missingCustomerCode_hardFails400_notSilentZeroRows}（两步合一）</td></tr>
 *   <tr><td>正面 · 量具自证（完整参数 ⇒ 有行）</td>
 *       <td>{@link #t01_boundCustomerCode_returnsOnlyMyCustomerRows}</td></tr>
 *   <tr><td><b>反面 ②</b>：{@code QuoteViewValidationService} 不被误伤</td>
 *       <td>{@link #t03_quoteViewValidation_doesNotFailOnCustomerCodeView}</td></tr>
 *   <tr><td><b>反面 ③</b>：{@code CostingTreeSqlValidator} 不被误伤</td>
 *       <td>{@link #t04_costingTreeValidator_acceptsCustomerCodePlaceholder}</td></tr>
 *   <tr><td>反面 ①：{@code POST /builder/preview}</td>
 *       <td>另一文件 {@code PreviewLiteralBindingAcTest}</td></tr>
 * </table>
 *
 * <h3>🚨 量具自证（{@code test.md §3}，本项目实证教训，不是形式）</h3>
 * 「断言某操作返回 400」之前<b>必须先证明该操作在正常参数下真的能跑通</b>。
 * {@code task-260907} 的 {@code AC-16} 就栽在这上面：请求漏带 {@code previewToken} 返 400、
 * <b>操作根本没执行</b>，副作用自然没发生，差点被记成 ✅。
 * ⇒ {@link #t02_missingCustomerCode_hardFails400_notSilentZeroRows} 内部<b>两步同方法、同夹具、同 JVM</b>：
 * 先用完整参数打一次断言 200 + 有行，再<b>只去掉 customerCode</b> 打一次断言 400。
 * 🚫 只写第二步 = 零证据。
 *
 * <h3>🚨 证伪设计（{@code test.md §4}）</h3>
 * 把 {@code B-2} 的硬阻断改回「未绑定 ⇒ 替换成字面量 {@code NULL}」，
 * {@link #t02_missingCustomerCode_hardFails400_notSilentZeroRows} 的
 * <b>{@code assertNotNull(caught, ...)}</b> 必须变红 —— 届时 {@code customer_no = NULL} 恒 false，
 * 调用<b>正常返回 0 行</b>，异常为 null。断言消息会把「实际返回 N 行」打出来，
 * 与「AC-5 要堵的就是这个静默 0 行」直接对上。
 * <p>🚫 不变红 = 白测，该判据作废重写。
 *
 * <h3>🚨 共库纪律（{@code CLAUDE.md §3.2}「测试也算」 / {@code testing.md §4.5}）</h3>
 * {@code test} profile 的库<b>就是共享开发库</b> {@code 10.177.152.12:5432/cpq_db_0724}，
 * 且当前有 3 条并发会话在用。故本类：
 * <ul>
 *   <li>造数一律带分片前缀 {@link #FX}（{@code R260908S2} + 本轮 RUN_ID），
 *       视图 SQL 自己也用该前缀过滤 ⇒ <b>别人往同一张表造数打不红我</b>；</li>
 *   <li>🚫 <b>无全局计数断言</b> —— 不写「{@code unit_price} 共 N 条」「校验总数 = 28」这类；
 *       {@link #t03_quoteViewValidation_doesNotFailOnCustomerCodeView} 只断言
 *       <b>我自己那张视图</b>不在失败清单里，全局 failed 数只 {@code println} 供报告；</li>
 *   <li>清理写在 {@link #cleanup()}（{@code @AfterEach} = finally 语义，用例中途崩溃也执行），
 *       每条 {@code DELETE} 的命中面被本轮前缀/自建 id 限死；
 *       🚫 无 {@code TRUNCATE} / {@code DROP} / 无 WHERE 的删除 / 清库 / 全局状态重置。</li>
 * </ul>
 *
 * <h3>本类不读实现代码</h3>
 * 断言来源只有 {@code 问题说明.md §⑥} 的 AC 原文、{@code test.md}、{@code api.md §3} 与既有测试代码。
 * 🚫 未读 {@code cpq-backend/src/main/java/**}。
 */
@QuarkusTest
class CustomerCodeUnboundGuardAcTest {

    // ═══════════════════════════ 分片命名空间 ═══════════════════════════

    /** 分片前缀（主线分配）。长字段用它。 */
    private static final String PREFIX = "R260908S2_";

    /**
     * 本次 JVM 运行的唯一标记 —— 两轮运行不撞唯一约束，也不会把上轮残留误当本轮数据。
     * <p>🚨 6 位：V6 表（{@code unit_price.finished_material_no} 等）历史上有 {@code varchar(20)}
     * 列宽约束（{@code task-260907} 实测报过 {@code value too long}，那个错长得像业务缺陷）。
     */
    private static final String RUN_ID = UUID.randomUUID().toString().replace("-", "").substring(0, 6);

    /** 夹具<b>料号</b>前缀，15 字符（≤20）。视图 SQL 用它做私有过滤。 */
    private static final String FX = "R260908S2" + RUN_ID;

    /** 我这一片的客户号，16 字符（≤20）。 */
    private static final String MY_CUST = "R260908S2C" + RUN_ID;

    /** 对照客户号 —— 用来证明「绑了 customerCode 时确实按客户收窄」，不是恒返全部。 */
    private static final String OTHER_CUST = "R260908S2X" + RUN_ID;

    /** {@code $view} 名（小写标识符）。 */
    private static final String VIEW_NAME = "r260908s2_" + RUN_ID;

    /** {@code unit_price.price_type} 取既有合法值，避免撞 check 约束；隔离靠料号前缀不靠它。 */
    private static final String PRICE_TYPE = "PROCESS";

    @Inject SqlViewExecutor executor;
    @Inject EntityManager em;
    @Inject CostingTreeSqlValidator treeValidator;
    @Inject QuoteViewValidationService quoteViewValidation;

    private UUID componentId;

    // ═══════════════════════════ 夹具 ═══════════════════════════

    /**
     * 建一个<b>只看得见我自己那批行</b>的 {@code :customerCode} 视图 + 3 行数据
     * （2 行属 {@link #MY_CUST}，1 行属 {@link #OTHER_CUST}）。
     *
     * <p>🔑 视图里的 {@code finished_material_no LIKE '<FX>%'} 是<b>并发隔离</b>的关键：
     * 别的会话往 {@code unit_price} 造多少行都进不了我的结果集
     * ⇒ 我可以放心写「恰好 2 行」这种精确断言，而它<b>不是</b>全局计数断言。
     */
    @BeforeEach
    void setUp() {
        // 构造自检：本轮命名空间必须是干净的，否则「我造的行出现了」会被残留冒充
        assertEquals(0L, count("SELECT count(*) FROM unit_price WHERE finished_material_no LIKE '" + FX + "%'"),
                "构造自检：unit_price 里已存在本轮前缀 " + FX + " 的行 ⇒ 行数断言会被残留冒充");
        assertEquals(0L, count("SELECT count(*) FROM component_sql_view WHERE sql_view_name = '" + VIEW_NAME + "'"),
                "构造自检：$view 名 " + VIEW_NAME + " 已存在 ⇒ 会拿到别人的模板");

        UUID[] holder = new UUID[1];
        QuarkusTransaction.requiringNew().run(() -> {
            Component c = new Component();
            c.name = PREFIX + "AC5执行器_" + RUN_ID;
            c.code = PREFIX + RUN_ID;
            c.fields = "[{\"name\":\"_销售料号\",\"field_type\":\"INPUT_TEXT\"}]";
            c.formulas = "[]";
            c.tabType = "零件";
            c.partNoField = "_销售料号";
            c.rowKeyFields = "[\"_销售料号\"]";
            c.dataDriverPath = "$" + VIEW_NAME;
            c.persist();

            ComponentSqlView v = new ComponentSqlView();
            v.componentId = c.id;
            v.sqlViewName = VIEW_NAME;
            v.sqlTemplate = viewTemplate();
            v.declaredColumns = "[]";
            v.persist();

            insertUnitPrice(FX + "A", MY_CUST, 10, new BigDecimal("11.00"));
            insertUnitPrice(FX + "B", MY_CUST, 20, new BigDecimal("22.00"));
            insertUnitPrice(FX + "C", OTHER_CUST, 30, new BigDecimal("33.00"));

            holder[0] = c.id;
        });
        componentId = holder[0];

        // 阳性对照：夹具真的落库了。不落库时后面「我的行出现了」会红成看似产品缺陷。
        assertEquals(3L, count("SELECT count(*) FROM unit_price WHERE finished_material_no LIKE '" + FX + "%'"),
                "夹具自检：unit_price 应落 3 行（MY×2 + OTHER×1）");
        assertEquals(2L, count("SELECT count(*) FROM unit_price WHERE finished_material_no LIKE '" + FX
                        + "%' AND customer_no = '" + MY_CUST + "'"),
                "夹具自检：我这个客户名下应有 2 行 —— 这是 t01/t02 第一步「有行」的数据来源");
        System.out.println("[S-2·fixture] component=" + componentId + " view=$" + VIEW_NAME
                + " 料号前缀=" + FX + " myCust=" + MY_CUST + " otherCust=" + OTHER_CUST);
    }

    /**
     * 视图模板：{@code :customerCode} 是<b>唯一</b>的命名占位符。
     * <p>🔑 刻意不放 {@code :total_material_no} —— 否则「抛 400」可能来自<b>另一个</b>参数的硬阻断，
     * 那正是 AC-5 要区分开的东西（错误文案必须指名 {@code customerCode}）。
     */
    private static String viewTemplate() {
        return "SELECT\n"
             + "  up.finished_material_no AS hf_part_no,\n"
             + "  up.finished_material_no AS _销售料号,\n"
             + "  up.customer_no          AS _客户编号,\n"
             + "  up.pricing_price        AS _单价\n"
             + "FROM unit_price up\n"
             + "WHERE up.system_type = 'QUOTE' AND up.is_current = true\n"
             + "  AND up.price_type = '" + PRICE_TYPE + "'\n"
             + "  AND up.finished_material_no LIKE '" + FX + "%'\n"   // 分片隔离：只看我造的行
             + "  AND up.customer_no = :customerCode\n"
             + "ORDER BY up.seq_no";
    }

    private void insertUnitPrice(String materialNo, String customerNo, int seq, BigDecimal price) {
        em.createNativeQuery(
                "INSERT INTO unit_price (id, system_type, price_type, version_no, code, finished_material_no, "
              + "  seq_no, pricing_price, unit, customer_no, is_current, created_at, updated_at) "
              + "VALUES (:id, 'QUOTE', :pt, :vn, :code, :fmn, :seq, :price, '元', :cn, true, now(), now())")
            .setParameter("id", UUID.randomUUID()).setParameter("pt", PRICE_TYPE)
            .setParameter("vn", "R" + RUN_ID).setParameter("code", materialNo)
            .setParameter("fmn", materialNo).setParameter("seq", seq)
            .setParameter("price", price).setParameter("cn", customerNo)
            .executeUpdate();
    }

    /**
     * 还原本类写进共享库的一切。
     * <p>🚨 {@code @AfterEach} = finally 语义：{@code assertThrows} 失败即抛，写在方法体尾部的清理跑不到。
     * <p>每条 DELETE 的命中面被本轮前缀 / 自建 id 限死；🚫 无 TRUNCATE / DROP / 无 WHERE 的删除。
     */
    @AfterEach
    void cleanup() {
        SqlViewRuntimeContext.clear();
        List<String> errors = new ArrayList<>();
        try {
            QuarkusTransaction.requiringNew().run(() -> {
                em.createNativeQuery("DELETE FROM unit_price WHERE finished_material_no LIKE :p")
                        .setParameter("p", FX + "%").executeUpdate();
                if (componentId != null) {
                    em.createNativeQuery("DELETE FROM component_sql_view WHERE component_id = :id")
                            .setParameter("id", componentId).executeUpdate();
                    em.createNativeQuery("DELETE FROM component WHERE id = :id")
                            .setParameter("id", componentId).executeUpdate();
                }
            });
        } catch (RuntimeException e) {
            errors.add("清理失败: " + e);
        }
        if (!errors.isEmpty()) System.out.println("[S-2·cleanup] ⚠️ " + errors);
        assertResidueFree();
        componentId = null;
    }

    /** 清完立刻自检：脏库必须以「残留」的名义硬失败，🚫 不许伪装成下一轮的业务缺陷。 */
    private void assertResidueFree() {
        long prices = count("SELECT count(*) FROM unit_price WHERE finished_material_no LIKE '" + FX + "%'");
        long views = count("SELECT count(*) FROM component_sql_view WHERE sql_view_name = '" + VIEW_NAME + "'");
        long comps = count("SELECT count(*) FROM component WHERE code = '" + PREFIX + RUN_ID + "'");
        System.out.println("[S-2·residue] unit_price=" + prices + " sql_view=" + views + " component=" + comps);
        assertEquals(0L, prices + views + comps,
                "还原自检：本轮夹具仍有残留（unit_price=" + prices + " sql_view=" + views + " component=" + comps
                        + "）—— 共享 dev 库必须清干净");
    }

    // ═══════════════════════════ 正面 ═══════════════════════════

    /**
     * <b>AC-5 量具自证（第 1 步）</b>：绑上 {@code customerCode} 时这条路径是<b>通</b>的、视图是<b>有数据</b>的。
     *
     * <p>没有这一条，{@link #t02_missingCustomerCode_hardFails400_notSilentZeroRows} 的「400」
     * 可能来自完全无关的原因（视图名写错 / 组件不存在 / SQL 语法错），那时 AC-5 记成 ✅ 就是假绿。
     *
     * <p>断言的是<b>存在性 + 私有集合</b>，不是全局计数：结果集被视图里的 {@code LIKE '<FX>%'} 锁死在我造的 3 行内。
     */
    @Test
    void t01_boundCustomerCode_returnsOnlyMyCustomerRows() {
        SqlViewRuntimeContext.set(componentId, null, null, null);
        List<Map<String, Object>> rows;
        try {
            rows = executor.executeAllRows("$" + VIEW_NAME, ctxWithCustomer(MY_CUST), null);
        } finally {
            SqlViewRuntimeContext.clear();
        }

        System.out.println("---- AC-5 量具自证：绑 customerCode=" + MY_CUST + " ----");
        for (Map<String, Object> r : rows) System.out.println("  " + r);

        // 🚨 空跑守卫写在最前：结果为空时「每行的客户都是我」恒真通过（testing.md §3 假绿第 3 类）
        assertFalse(rows.isEmpty(), "AC-5 量具自证：绑了 customerCode 却返 0 行 ⇒ "
                + "这条路径根本没跑通，后面「缺参数返 400」的断言不可信（400 可能来自别的原因）。"
                + "先查夹具是否落库、$view 名是否匹配，🚫 不要据此判 AC-5 通过或不通过。");

        assertEquals(2, rows.size(), "AC-5 量具自证：应恰好取到我造的 2 行（视图已用料号前缀 " + FX
                + " 锁死结果集，此数与其他会话的造数无关）。实际=" + rows.size() + " → " + rows);

        Set<String> parts = new LinkedHashSet<>();
        for (Map<String, Object> r : rows) {
            assertEquals(MY_CUST, String.valueOf(r.get("_客户编号")),
                    "绑了 customerCode 仍取到别的客户的行 ⇒ 客户谓词没生效：" + r);
            parts.add(String.valueOf(r.get("_销售料号")));
        }
        assertEquals(Set.of(FX + "A", FX + "B"), parts, "取到的应恰是我造给 " + MY_CUST + " 的两个料号");

        System.out.println("[S-2] ✅ 量具自证通过：完整参数下 " + rows.size() + " 行，全部属 " + MY_CUST);
    }

    /**
     * <b>AC-5 正面（两步合一）</b>：不提供 {@code customerCode} ⇒ 抛 <b>400</b>、文案指名参数，
     * 🚫 <b>不是返 0 行</b>。
     *
     * <p><b>第 1 步</b>（量具自证）：同一夹具、同一 JVM、同一调用，先用<b>完整参数</b>打一次，
     * 断言 200 且有行 —— 证明这条路径通、视图有数据。
     * <p><b>第 2 步</b>：<b>只去掉 customerCode</b>，其余一切不变，断言 400。
     * <p>两步的唯一差异就是那个参数 ⇒ 400 只能由它引起。
     *
     * <p>覆盖<b>两种「没提供」的形态</b>（B-2 的判据可能落在任一处，只测一种会漏）：
     * <ol>
     *   <li>{@code RuntimeContext} 里根本没有 quotation 上下文；</li>
     *   <li>有 quotation 上下文但 {@code customerCode} 为 {@code null}。</li>
     * </ol>
     *
     * <p><b>证伪</b>：把 B-2 改回静默 {@code NULL} 字面量 ⇒ {@code caught} 为 null、返 0 行，
     * 本方法在 {@code assertNotNull(caught, ...)} 处变红。
     */
    @Test
    void t02_missingCustomerCode_hardFails400_notSilentZeroRows() {
        // ── 第 1 步：完整参数 ⇒ 必须通且有行（量具自证，🚫 不许跳） ──────────────────
        SqlViewRuntimeContext.set(componentId, null, null, null);
        List<Map<String, Object>> ok;
        try {
            ok = executor.executeAllRows("$" + VIEW_NAME, ctxWithCustomer(MY_CUST), null);
        } finally {
            SqlViewRuntimeContext.clear();
        }
        assertFalse(ok.isEmpty(), "AC-5 第 1 步（量具自证）：完整参数下就返 0 行 ⇒ "
                + "这条路径没跑通，第 2 步的「400」不能归因于缺 customerCode。"
                + "🚫 此时不许把 AC-5 记成通过，也不许记成不通过 —— 这是 harness 故障。");
        System.out.println("[S-2·AC-5 第1步] 完整参数 ⇒ " + ok.size() + " 行（量具已验明正身）");

        // ── 第 2 步：只去掉 customerCode ⇒ 必须 400 ────────────────────────────────
        assertHardFail400("形态①：RuntimeContext 无 quotation 上下文", new RuntimeContext());
        assertHardFail400("形态②：有 quotation 上下文但 customerCode=null", ctxWithCustomer(null));
    }

    /** 第 2 步的公共断言体：执行 → 必须抛 → 状态码 400 → 文案指名 customerCode。 */
    private void assertHardFail400(String shape, RuntimeContext ctx) {
        SqlViewRuntimeContext.set(componentId, null, null, null);
        Throwable caught = null;
        List<Map<String, Object>> silent = null;
        try {
            silent = executor.executeAllRows("$" + VIEW_NAME, ctx, null);
        } catch (Throwable t) {
            caught = t;
        } finally {
            SqlViewRuntimeContext.clear();
        }

        // 🚨 AC-5 的核心：不许静默。这一行就是证伪实验会打红的那一行。
        assertNotNull(caught, "AC-5 【" + shape + "】未抛异常 —— 实际返回 " + (silent == null ? "?" : silent.size())
                + " 行" + (silent != null && silent.isEmpty()
                        ? "（正是 AC-5 要堵的『customer_no = NULL 恒 false ⇒ 整个页签静默 0 行』）"
                        : "（更糟：未绑定的 customerCode 竟没有收窄作用）")
                + "。AC-5 要求：抛 400，🚫 不是返 0 行。");

        int status = resolveHttpStatus(caught);
        String chain = messageChain(caught);
        System.out.println("---- AC-5 【" + shape + "】----");
        System.out.println("  exception = " + caught.getClass().getName());
        System.out.println("  status    = " + status);
        System.out.println("  message   = " + chain);

        assertEquals(400, status, "AC-5 【" + shape + "】必须是 400（客户端错：少给了参数），不是 500/其他。"
                + "实际异常类型=" + caught.getClass().getName() + "（解析出的状态码=" + status
                + (status < 0 ? "，即该异常类型不携带可判定的 HTTP 状态 ⇒ 经 GlobalExceptionMapper 多半落 500" : "")
                + "）message=" + chain);

        assertTrue(chain.contains("customerCode"), "AC-5 【" + shape + "】错误文案必须<b>指明缺哪个参数</b>，"
                + "即出现 customerCode 字样，否则用户拿到 400 也不知道该补什么。实际 message=" + chain);
    }

    private static RuntimeContext ctxWithCustomer(String customerCode) {
        RuntimeContext ctx = new RuntimeContext();
        ctx.quotation = new RuntimeContext.QuotationContext();
        ctx.quotation.customerCode = customerCode;
        return ctx;
    }

    // ═══════════════════════════ 反面：三条路径不被误伤 ═══════════════════════════

    /**
     * <b>AC-5 反面 ②</b>：{@code QuoteViewValidationService} 仍正常
     * （{@code api.md §3} —— 它自己把 {@code :customerCode} 批量替换成 {@code NULL} 字面量，
     * 不经 {@code rewriteNamedParams} 的未绑定分支）。
     *
     * <p>若 B-2 的硬阻断漏到了这条路径上，本轮自建的 {@code $}{@value #VIEW_NAME}
     * （唯一占位符就是 {@code :customerCode}）会出现在失败清单里。
     *
     * <p>🚫 <b>刻意不断言全局 {@code failed == 0}</b>：那是全局计数断言，
     * 共享库上任何一条并发线新建一张有问题的视图都会把我打红，而且<b>红得像业务回归</b>
     * （{@code testing.md §4.5}）。全局数字只 {@code println} 供报告，并在非零时打印明显提示，
     * 交主线按 {@code test.md §5} 做 A/B 同型对比归因。
     */
    @Test
    void t03_quoteViewValidation_doesNotFailOnCustomerCodeView() {
        QuoteViewValidationService.Snapshot s = quoteViewValidation.runValidation();

        // 空跑守卫：校验范围为空时，「我的视图不在失败清单里」恒真通过
        assertTrue(s.total > 0, "AC-5 反面②：校验范围为空（total=0）⇒ 「我的视图未失败」恒真通过，这是空跑不是结论。");

        List<String> mine = new ArrayList<>();
        StringBuilder all = new StringBuilder();
        for (var f : s.failures) {   // 🔑 用 var：不把「失败项」的具体类型名写进用例（那是实现细节）
            all.append("\n    ").append(f.component).append('/').append(f.view).append(": ").append(f.reason);
            if (VIEW_NAME.equalsIgnoreCase(f.view)) mine.add(f.view + ": " + f.reason);
        }
        System.out.println("---- AC-5 反面② QuoteViewValidationService ----");
        System.out.println("  total=" + s.total + " failed=" + s.failed + " (全局数字仅供报告，不作断言)");
        if (s.failed > 0) {
            System.out.println("  ⚠️ 全局存在失败视图，🚫 不要直接归因为本次改动 —— 按 test.md §5 打 A/B 对照："
                    + all);
        }

        assertTrue(mine.isEmpty(), "AC-5 反面②：本轮自建的含 :customerCode 视图 $" + VIEW_NAME
                + " 在 QuoteViewValidationService 里校验失败 ⇒ B-2 的硬阻断误伤了字面量替换路径（api.md §3 ②）。"
                + "失败原因=" + mine);
        System.out.println("[S-2] ✅ 反面② 通过：$" + VIEW_NAME + " 未出现在失败清单中");
    }

    /**
     * <b>AC-5 反面 ③</b>：{@code CostingTreeSqlValidator} 仍正常
     * （{@code api.md §3} —— 它做 {@code .replace(":customerCode", "NULL::varchar")}）。
     *
     * <p>纯文本校验，<b>零写库</b>，不受任何并发影响。
     *
     * <h4>本条自带量具自证</h4>
     * 只断言「含 {@code :customerCode} 的 SQL 通过」是危险的 —— 一个恒返 {@code ok=true} 的校验器
     * 也会让它绿。故同时断言：同一条 SQL <b>去掉必需输出列</b>后<b>必须被拒</b>
     * ⇒ 证明校验器真的在跑，{@code ok=true} 不是常量。
     */
    @Test
    void t04_costingTreeValidator_acceptsCustomerCodePlaceholder() {
        String withCustomerCode =
                "SELECT p AS root_no, p AS material_no, CAST(NULL AS text) AS bom_version, "
              + "CAST(NULL AS text) AS parent_no, p AS node_path "
              + "FROM unnest(:production_part_nos) p "
              + "WHERE :customerCode IS NULL OR p <> :customerCode";

        var ok = treeValidator.validate(withCustomerCode);
        System.out.println("---- AC-5 反面③ CostingTreeSqlValidator ----");
        System.out.println("  含 :customerCode ⇒ ok=" + ok.ok + " message=" + ok.message);
        assertTrue(ok.ok, "AC-5 反面③：含 :customerCode 的核价树 SQL 被拒 ⇒ B-2 的硬阻断误伤了 "
                + "CostingTreeSqlValidator 的字面量替换路径（api.md §3 ③）。message=" + ok.message);

        // 量具自证：校验器不是恒 ok —— 去掉必需列必须被拒
        String missingColumn = "SELECT p AS root_no, p AS material_no "
              + "FROM unnest(:production_part_nos) p WHERE :customerCode IS NULL";
        var bad = treeValidator.validate(missingColumn);
        System.out.println("  量具自证（缺输出列）⇒ ok=" + bad.ok + " message=" + bad.message);
        assertFalse(bad.ok, "量具自证失败：缺必需输出列的 SQL 竟也通过 ⇒ 校验器恒返 ok，"
                + "上面那条 assertTrue 是空验证，不能作为 AC-5 反面③ 的证据。");
        System.out.println("[S-2] ✅ 反面③ 通过（且量具已验明正身：校验器会红）");
    }

    // ═══════════════════════════ 状态码解析器 + 它自己的量具自证 ═══════════════════════════

    /**
     * 🚨 <b>解析器自己的量具自证</b>：证明 {@link #resolveHttpStatus} 既能读出真实状态码、
     * 又<b>不会凭空造出 400</b>。
     *
     * <p>没有这一条，一个「恒返 400」的解析器会让 {@link #t02_missingCustomerCode_hardFails400_notSilentZeroRows}
     * 的 {@code assertEquals(400, status)} 无论实现怎么写都通过 —— 那是最难发现的一类假绿。
     */
    @Test
    void t00_statusResolverIsCalibrated() {
        assertEquals(400, resolveHttpStatus(new WebApplicationException(400)), "应读出 400");
        assertEquals(500, resolveHttpStatus(new WebApplicationException(500)),
                "应读出 500 —— 若这里也返 400，说明解析器是常量，t02 的状态码断言全是空验证");
        assertEquals(400, resolveHttpStatus(new RuntimeException("wrap", new WebApplicationException(400))),
                "被包一层时应沿 cause 链解析");
        assertEquals(-1, resolveHttpStatus(new IllegalStateException("no status here")),
                "不携带状态的异常必须返 -1（⇒ t02 会以『无法判定状态码』红），🚫 不许兜底成 400");
        assertEquals(400, resolveHttpStatus(new CodeCarrier(400)), "应能反射读出 getCode()");
        assertEquals(500, resolveHttpStatus(new CodeCarrier(500)), "getCode() 分支同样不许是常量");
        System.out.println("[S-2] ✅ 状态码解析器已标定");
    }

    /** 模拟项目里 {@code BusinessException} 的形状（{@code getCode()} 即 HTTP 状态，见 GlobalExceptionMapper）。 */
    private static final class CodeCarrier extends RuntimeException {
        private final int code;
        CodeCarrier(int code) { super("code=" + code); this.code = code; }
        @SuppressWarnings("unused") public int getCode() { return code; }
    }

    /**
     * 沿 cause 链解析 HTTP 状态码。
     * <p>覆盖两种本项目在用的形状：{@link WebApplicationException}（{@code getResponse().getStatus()}）
     * 与 {@code BusinessException}（{@code getCode()} 即状态码 —— 由
     * {@code GlobalExceptionMapper#handleBusinessException} 的 {@code Response.status(e.getCode())} 保证，
     * 故断言 {@code getCode()==400} 与打端点得 400 等价）。
     * <p>🚫 解析不出时返 {@code -1}，<b>不兜底成 400</b>：解析不出本身就意味着「多半落 500」，
     * 那是 AC-5 不接受的结果，必须红。
     */
    private static int resolveHttpStatus(Throwable t) {
        for (Throwable cur = t; cur != null; cur = cur.getCause()) {
            if (cur instanceof WebApplicationException w && w.getResponse() != null) {
                return w.getResponse().getStatus();
            }
            for (String getter : List.of("getCode", "getStatus", "getHttpStatus", "getStatusCode")) {
                Integer v = readInt(cur, getter);
                if (v != null && v >= 100 && v <= 599) return v;
            }
            if (cur.getCause() == cur) break;
        }
        return -1;
    }

    private static Integer readInt(Object target, String getter) {
        try {
            Method m = target.getClass().getMethod(getter);
            Object v = m.invoke(target);
            if (v instanceof Integer i) return i;
            if (v instanceof Number n) return n.intValue();
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // 没有这个 getter 很正常，继续试下一个
        }
        return null;
    }

    /** 拼接整条 cause 链的 message —— 文案断言不该因为「被包了一层」而漏判。 */
    private static String messageChain(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (Throwable cur = t; cur != null; cur = cur.getCause()) {
            sb.append(cur.getClass().getSimpleName()).append(": ").append(cur.getMessage()).append(" | ");
            if (cur.getCause() == cur) break;
        }
        return sb.toString();
    }

    private long count(String sql) {
        return ((Number) em.createNativeQuery(sql).getSingleResult()).longValue();
    }
}
