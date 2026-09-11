package com.cpq.task260910c;

import com.cpq.task260903.Task260903Base;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * task-260910 · <b>分片 S-C（带版本表直写主表 + {@code _record} 投影）</b>公共基座。
 *
 * <h3>🔴 2026-09-10 改写（D-22）—— 本基座原为<b>方案②</b>而写</h3>
 * 原设计（D-7 方案②）是「带版本表<b>只写 {@code _record}</b>、主表 0 行，核价通过后回填升主表」。
 * <b>该方案已被用户整体作废（D-14）</b>：实测 {@code ds_quote_material_bom} 的 source 分布
 * = {@code IMPORT} <b>2734</b> 行 / {@code MANUAL} 18 / {@code QUOTE_BACKFILL} 8
 * ⇒ 导入侧一直是<b>直写主表</b>、一次核价审核都没过 ⇒ 「新料号必须过核价才进主库」
 * 这条规则本来就只约束选配一侧、不成立。
 * <p><b>用户原话</b>：「那选配与导入看齐,也写入相同的主表. 两侧功能保持一致」
 * ⇒ 现行设计 = <b>选配与导入同口径：带版本表直写主表 + 建单末尾投影 {@code _record}</b>。
 * {@code _record} 回归其原始角色 = 报价单对主数据的投影 / 核价回填的数据来源，
 * 🚫 <b>不再是渲染数据源</b>。
 * <p>⇒ 本基座与其 5 个子类的断言方向已按 <b>D-14 / D-19 / D-21</b> 重写（D-22 回流）。
 * 🚫 原「主表零新增」类断言全部作废，🚫 不要再从旧注释推断设计。
 * 认领 AC-10 / AC-11 / AC-13 / AC-14 / AC-16 / AC-18 / AC-19 / AC-20。
 *
 * <h3>断言来源 —— 🚫 不读实现</h3>
 * 每条断言指回 {@code dev-docs/task-260910-选配切ds新表与已有料号绑定/需求文档.md §③} 的 AC 原文，
 * 请求形状指回同目录 {@code api.md}。本套用例<b>一行都不从</b>
 * {@code com.cpq.configure.**} / {@code com.cpq.quotation.**} / {@code com.cpq.component.**} /
 * {@code com.cpq.dataset.**} / {@code cpq-frontend/src/**} 反推 —— 从实现反推的测试与实现
 * 共享同一个理解偏差，于是永远全绿。
 *
 * <h3>🚨 分片纪律（{@code test.md §1 / §2}）</h3>
 * <ul>
 *   <li><b>造数前缀 {@code T260910C-}</b>。所有自造料号 / 客户产品编号 / 单据都带它。</li>
 *   <li>🚫 <b>不读、不改别片数据</b>（{@code T260910A-} / {@code T260910B-} / {@code T260910G-}）。</li>
 *   <li>🚫 <b>本片不做核价通过</b>（那会写 {@code ds_quote_*} <b>主表</b>，属全局写）——
 *       AC-12 / AC-17 归 S-全局 片。本片只验到「{@code _record} 有行 + 卡片渲染正确」为止。</li>
 *   <li>🚫 <b>断言不许用全局计数</b>。{@code _record} 一律按 {@code quotation_id = 本片自造单} 收窄；
 *       主表一律按 {@code material_no = 本片自铸料号} 收窄。共享库上「共 N 行」会被别片打红，
 *       而且<b>红得像业务回归</b>。</li>
 * </ul>
 *
 * <h3>🚨 共享库纪律（{@code CLAUDE.md §3.2}）</h3>
 * 🚫 无 TRUNCATE / DROP / 无 WHERE 的 DELETE，哪怕写在 {@code @BeforeEach} 里。
 * 本类所有 DELETE 的命中面都被「本用例自建的 quotation_id / customer_no + {@code source='TEST'}」限死。
 *
 * <p>⚠️ <b>{@code _record} 表没有 {@code quotation_id} 外键</b>（2026-09-10 只读查证：
 * {@code information_schema.referential_constraints} 对这两张表<b>零行</b>）⇒
 * <b>删报价单不会级联删 {@code _record}</b>。父类 {@code restoreFixtures} 只清 {@code ds_quote_*} 主表，
 * 不认识 {@code _record} ⇒ 本类必须自己收。JUnit 5 里子类 {@code @AfterEach} 先于父类执行，
 * 所以 {@link #cleanupOwnRecords()} 跑在报价单被删之前，{@code quotation_id} 还查得到。
 */
public abstract class Task260910CBase extends Task260903Base {

    /** 🚨 本片专属造数前缀。派工写死，不许改成「更自然」的命名。 */
    public static final String C = "T260910C-";

    /** 本次 JVM 运行的唯一标记 —— 两轮之间永不撞唯一键，「上一轮残留」也不会伪装成「本轮 duplicate」。 */
    protected static final String RUN_C = UUID.randomUUID().toString().substring(0, 5);

    protected static final String MBOM = "ds_quote_material_bom";
    protected static final String EBOM = "ds_quote_element_bom";
    protected static final String MBOM_REC = "ds_quote_material_bom_record";
    protected static final String EBOM_REC = "ds_quote_element_bom_record";

    // ── 夹具基线（需求文档 §③「夹具基线」；2026-09-10 在 cpq_db_test 与 cpq_db_0724 双库实查）──
    /** {@code AgCu90}（symbol {@code AgCu}），配置 {@code AgCu90-01}：<b>Ag=90 / Cu=10</b>。AC-11③ 断的就是它。 */
    protected static final String RECIPE_AGCU = "AgCu90";
    protected static final String CONFIG_AGCU = "AgCu90-01";
    /** {@code 00006 / AgNi10}，配置 {@code 00006-01}：Ag=90 / Ni=10。AC-14 的第二个材质。 */
    protected static final String RECIPE_AGNI = "00006";
    protected static final String CONFIG_AGNI = "00006-01";
    /** {@code Z008 成品清洗}（{@code process_category='加工'}，{@code standard_unit='PCS'}）。 */
    protected static final String PROC_CLEAN = "Z008";

    /** 本轮自造的「已有销售料号」（AC-18 的绑定目标），{@link #cleanupOwnRecords()} 精确删。 */
    private final List<String> seededMaterialNos = new ArrayList<>();
    /** 本轮自造过 {@code _record} 行的报价单 id。 */
    private final List<UUID> recordQuotationIds = new ArrayList<>();

    // ─────────────────────────── 假绿防线 ───────────────────────────

    /**
     * 🚨 阳性对照：证明<b>被测进程真的起来了、鉴权链路也活着</b>。
     *
     * <p>不打这一枪的后果不是「少一步」：Quarkus 起不来时用例会以集体 error / skip 的形态出现，
     * 而读报告的人会把它读成「没问题」或「业务缺陷」。这里用<b>已知语义的端点</b>做对照 ——
     * 带角色约束的业务端点在未登录时必须返 401。拿到 200 反而异常（RBAC 被关了）。
     */
    @BeforeEach
    void processAliveGate() {
        Response r = RestAssured.given().when().get("/api/cpq/components").thenReturn();
        assertEquals(401, r.statusCode(),
                "🚨 进程级前置未满足：未登录访问 /api/cpq/components 期望 401（应用在跑且 RBAC 生效），实际 "
                        + r.statusCode() + "。这是**环境结论**不是业务结论 —— 拿不到 401 就不许解读本类任何结果。"
                        + " body=" + r.asString());
    }

    /**
     * ⛔ 前置硬检查：两张 {@code _record} 表不存在时立刻硬失败并说清这是<b>环境前置</b>。
     * 🚫 刻意不用 {@code Assumptions}：skip 在 surefire 汇总里长得和「通过」几乎一样。宁可红，红是可见的。
     */
    protected void requireRecordLayer() {
        for (String t : List.of(MBOM_REC, EBOM_REC)) {
            if (!tableExists(t)) {
                fail("⛔ 环境前置未满足（**不是被测功能的结论**）：" + t + " 表不存在。"
                        + "本片的 AC-10④ / AC-13 / AC-16 以 _record 投影层为前提"
                        + "（D-14 后 _record 仍在，只是角色从「渲染数据源」回归「主数据投影 / 回填来源」），"
                        + "该表缺失时这些结论全部无效。");
            }
        }
    }

    /**
     * ⛔ 前置硬检查：S-4（直写主表）/ S-7（绑定已有料号）的接口是否已落地。
     * <p>端点返 404 / 请求体字段被忽略时，AC 断言会以「业务失败」的面目出现，
     * 而真实原因是<b>实现尚未合入本 worktree</b>。这一条把两者分开。
     */
    protected void requireImplementationPresent(Response res, String acLabel) {
        assertTrue(res.statusCode() != 404,
                acLabel + "：端点 404 ⇒ 实现尚未落地或路径与 api.md 不一致，"
                        + "这是**交付状态结论**不是 AC 结论。响应=" + res.asString());
        assertTrue(res.statusCode() != 401 && res.statusCode() != 403,
                acLabel + "：请求被鉴权挡在业务层之外（" + res.statusCode() + "）⇒ harness 故障，不是 AC 结论。"
                        + "响应=" + res.asString());
    }

    // ─────────────────────── _record / 主表 计数（一律收窄到本片）───────────────────────

    /** 本单在 {@code ds_quote_material_bom_record} 的行数。🚫 不是全表计数。 */
    protected long recMbom(Fx fx) {
        recordQuotationIds.add(fx.quotationId());
        return count("SELECT count(*) FROM " + MBOM_REC + " WHERE quotation_id='" + fx.quotationId() + "'");
    }

    /** 本单在 {@code ds_quote_element_bom_record} 的行数。🚫 不是全表计数。 */
    protected long recEbom(Fx fx) {
        recordQuotationIds.add(fx.quotationId());
        return count("SELECT count(*) FROM " + EBOM_REC + " WHERE quotation_id='" + fx.quotationId() + "'");
    }

    /** 本单某料号在指定 {@code _record} 表的行数。 */
    protected long recRows(String table, Fx fx, String materialNo) {
        recordQuotationIds.add(fx.quotationId());
        return count("SELECT count(*) FROM " + table + " WHERE quotation_id='" + fx.quotationId()
                + "' AND material_no='" + materialNo + "'");
    }

    /**
     * AC-16 的「同内容重复」粒度列 —— <b>按表取，🚫 不能两张表都用 {@code input_material_no}</b>。
     *
     * <h3>⚠️ 这是 AC 原文的一处不可直译处，已报主线</h3>
     * AC-16 原文：「① {@code ds_quote_material_bom_record} 中
     * {@code (quotation_id, material_no, item_seq, input_material_no)} 无重复；
     * ② {@code ds_quote_element_bom_record} <b>同上</b>」。
     * 🔬 但只读查证（{@code information_schema.columns}，2026-09-10）：
     * {@code ds_quote_element_bom_record} <b>没有 {@code input_material_no} 列</b>
     * （它的粒度列是 {@code element_code}，另有 {@code material_part_no}）。
     * ⇒ ②「同上」字面不可执行。本片按<b>该表实际的行粒度</b>取 {@code element_code}，
     * 🚫 不自行把它解释成别的意思之外还沉默 —— 已作为 AC 可执行性问题上报。
     */
    protected String grainColumn(String recordTable) {
        if (EBOM_REC.equals(recordTable)) return "element_code";
        return "input_material_no";
    }

    /**
     * AC-16 的判据：本单在 {@code table} 里
     * {@code GROUP BY (quotation_id, material_no, item_seq, <粒度列>) HAVING count(*)>1}
     * 的<b>分组个数</b>。
     *
     * <p>⚠️ <b>本方法只按 {@code quotation_id = 本片自造单} 取</b> —— 全库跑同一条 SQL 时
     * 2026-09-10 实测有 <b>3763</b> 个重复分组（存量，两库逐字一致），
     * 那个数字与本次改动无关，拿它当判据会永远红，而且红得像本次引入的回归（{@code test.md §2}）。
     */
    protected long dupGroups(String table, Fx fx) {
        recordQuotationIds.add(fx.quotationId());
        return count("SELECT count(*) FROM ("
                + "SELECT quotation_id, material_no, item_seq, " + grainColumn(table) + " "
                + "FROM " + table + " WHERE quotation_id='" + fx.quotationId() + "' "
                + "GROUP BY 1,2,3,4 HAVING count(*)>1) t");
    }

    /** 重复分组的明细，失败信息里要打出来（{@code testing.md §3}：要求打印实际值）。 */
    protected List<Object[]> dupDetail(String table, Fx fx) {
        return rows("SELECT material_no, coalesce(item_seq::text,'(null)'), "
                + "coalesce(" + grainColumn(table) + ",'(null)'), count(*)::text, count(origin_id)::text "
                + "FROM " + table + " WHERE quotation_id='" + fx.quotationId() + "' "
                + "GROUP BY 1,2,3 HAVING count(*)>1 ORDER BY 1,2");
    }

    // ─────────────────────────── 请求构造（api.md §2.3）───────────────────────────

    /**
     * S-7 绑定路径的请求体（{@code api.md §2.3}）：
     * {@code bindExistingMaterialNo} 非空 ⇒ 不铸新号、不进指纹、不写 BOM/元素；
     * 非空时 {@code parts} 必须为空数组或 null。
     */
    protected Map<String, Object> bindBody(String customerProductNo, String bindMaterialNo) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("productType", "SIMPLE");
        body.put("tempId", UUID.randomUUID().toString());
        body.put("customerProductNo", customerProductNo);
        body.put("customerProductName", C + "绑定产品");
        body.put("bindExistingMaterialNo", bindMaterialNo);
        body.put("parts", List.of());
        body.put("compositeProcesses", null);
        return body;
    }

    // ─────────────────────────── 夹具 ───────────────────────────

    /**
     * 造一个<b>本片独有</b>的「已有销售料号」：{@code ds_quote_material} 1 行主档
     * + {@code ds_quote_material_bom} 2 行（带 {@code material_ratio}）。<b>committed</b>。
     *
     * <h3>🚨 为什么不用现网的 {@code S0004}（AC-18 原文点名的那个）</h3>
     * {@code S0004} 挂在 <b>{@code CUST-0004}</b> 名下 —— 那是<b>共享客户</b>，
     * 三个并行分片和真人都在用。往它名下写 {@code ds_quote_customer_part} 行、
     * 再在 {@code @AfterEach} 里删，等于在别人的作用域里增删（{@code test.md §2}）。
     * ⇒ 改在<b>本轮自建客户</b>名下造一个同形态的料号：AC-18 的<b>实质</b>是
     * 「绑定一个在该客户 {@code ds_quote_material} 里<b>已存在</b>的销售料号」，
     * 而不是「必须叫 S0004」。
     *
     * <p>📌 现网对照已只读查证（2026-09-10，{@code cpq_db_test}）：
     * {@code S0004} 在 {@code CUST-0004} 下 {@code ds_quote_material} 1 行、
     * {@code ds_quote_material_bom} 3 行（{@code item_seq} 1..3，{@code input_material_no}
     * = S0005/S0006/S0007，{@code source='IMPORT'}）、<b>{@code ds_quote_element_bom} 0 行</b>。
     * ⇒ 用 S0004 时 AC-18⑥ 的「既有 BOM 数据非空」只能在 material_bom 一侧成立，
     * 元素侧无从验起 —— 自造夹具反而<b>把元素侧也补上了</b>。
     *
     * @return 自造的销售料号
     */
    protected String seedExistingSalesMaterial(Fx fx, String tag) {
        String mno = C + tag + RUN_C;            // 例：T260910C-BIND + 5 位 ⇒ 短，卡在 varchar 以内
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("INSERT INTO ds_quote_material "
                            + "(customer_no,material_no,material_name,material_type,source,created_at) "
                            + "VALUES (:cn,:mn,:nm,'零件','TEST',now())")
                    .setParameter("cn", fx.customerNo()).setParameter("mn", mno)
                    .setParameter("nm", C + "既有料号").executeUpdate();
            for (int seq = 1; seq <= 2; seq++) {
                em.createNativeQuery("INSERT INTO " + MBOM + " "
                                + "(customer_no,material_no,item_seq,input_material_no,material_ratio,"
                                + " version_no,row_fingerprint,source,created_at) "
                                + "VALUES (:cn,:mn,:seq,:in,CAST(:r AS numeric),1,:fp,'TEST',now())")
                        .setParameter("cn", fx.customerNo()).setParameter("mn", mno)
                        .setParameter("seq", seq)
                        .setParameter("in", seq == 1 ? RECIPE_AGCU : RECIPE_AGNI)
                        .setParameter("r", seq == 1 ? "60" : "40")
                        .setParameter("fp", fp(mno, "m", seq))
                        .executeUpdate();
                em.createNativeQuery("INSERT INTO " + EBOM + " "
                                + "(customer_no,material_no,item_seq,element_code,content_pct,"
                                + " version_no,row_fingerprint,source,created_at) "
                                + "VALUES (:cn,:mn,:seq,:ec,CAST(:p AS numeric),1,:fp,'TEST',now())")
                        .setParameter("cn", fx.customerNo()).setParameter("mn", mno)
                        .setParameter("seq", seq)
                        .setParameter("ec", seq == 1 ? "Ag" : "Cu")
                        .setParameter("p", seq == 1 ? "90" : "10")
                        .setParameter("fp", fp(mno, "e", seq))
                        .executeUpdate();
            }
        });
        seededMaterialNos.add(mno);
        // 🚨 夹具自检：先证明「已有料号」真的已有，否则 AC-18③④ 的「零新增」在夹具没建成时同样成立
        assertEquals(1L, count("SELECT count(*) FROM ds_quote_material WHERE material_no='" + mno
                        + "' AND customer_no='" + fx.customerNo() + "'"),
                "夹具自检：既有销售料号 " + mno + " 应已在 ds_quote_material 落 1 行");
        assertEquals(2L, count("SELECT count(*) FROM " + MBOM + " WHERE material_no='" + mno + "'"),
                "夹具自检：既有销售料号 " + mno + " 应已有 2 行既有 BOM（AC-18⑥ 靠它验「渲染出既有数据」）");
        System.out.println("[夹具] 自造既有销售料号 " + mno + " @ customer=" + fx.customerNo()
                + "（ds_quote_material 1 行 + material_bom 2 行 + element_bom 2 行，source=TEST）");
        return mno;
    }

    /**
     * 参照客户 {@code CUST-0004} 的产品分类 id（🚫 只读它，不改 {@code CUST-0004} 任何字段）。
     *
     * <h3>为什么本片的每个夹具都要挂分类</h3>
     * 🔬 2026-09-10 实测：不挂分类时 {@code quotation_line_component_data} 物化出 <b>0 条</b>，
     * 于是「卡片页签 0 行」既可能是 AC-11/AC-18⑥ 的缺陷，也可能只是模板解析不到
     * —— <b>两者症状完全一样</b>。挂上分类把这个混淆项构造性排除掉。
     */
    protected UUID referenceCategoryId() {
        String id = scalar("SELECT product_category_id::text FROM customer WHERE code='CUST-0004'");
        assertNotNull(id, "夹具前置：参照客户 CUST-0004 应有产品分类（卡片模板经分类解析）。"
                + "取不到 ⇒ 页签 0 行会是夹具问题而不是 AC 结论，本用例给不出可信红绿");
        return UUID.fromString(id);
    }

    /**
     * ⛔ <b>D-31 的那一行前置</b>：在 {@code configure} <b>之前</b>把本单
     * {@code quotation.customer_template_id} 绑好。
     *
     * <h3>它补的是什么（🔬 后端诊断 2026-09-10 单一变量 A/B 实测，两库同型）</h3>
     * 共享基座 {@code task260902/SelConfigAcTestBase#newFixture} 的 {@code INSERT INTO quotation}
     * <b>从来没有写 {@code customer_template_id}</b>（该列在建单接口里是<b>可选</b>字段）。
     * 它为 NULL 时，选配链路上有<b>两个静默点</b>同时命中：
     * <ol>
     *   <li>{@code ConfigureSnapshotService} 的 {@code loadDriverComponents} 以
     *       {@code quotation.customer_template_id} 为<b>唯一入口</b> ⇒ NULL 得 0 个 driver 组件 ⇒
     *       {@code if (comps.isEmpty()) return;} <b>零日志、零异常直接 return</b> ⇒
     *       {@code quotation_line_component_data} 恒 <b>0 条</b>；</li>
     *   <li>{@code CardSnapshotService.buildCardValues} 的守卫子句
     *       {@code if (li == null || li.id == null || templateId == null) return null;}
     *       <b>正常返回 null（不是 catch 吞异常）</b> ⇒ 落失败哨兵 +
     *       打 {@code [cardvalues-sentinel] quote build 失败}。</li>
     * </ol>
     * 🔑 <b>「开到 TRACE 也没堆栈」的原因就在这里 —— 没有堆栈，因为没有异常。</b>
     * 🚫 本片前一轮把它推断成「异常被吞」，<b>推断错了</b>，已由后端插桩实测证伪
     * （{@code DIAG-SWALLOW = 0 次} / {@code DIAG-GUARD = 2 次}）。
     *
     * <h3>为什么只改本基座（D-31 用户选「甲」）</h3>
     * 共享基座 {@code SelConfigAcTestBase} 被 S-A / S-B / S-D 三片同时使用，
     * 改它要重跑全部分片。本方法只 UPDATE <b>本片自造的那一个报价单</b>（{@code WHERE id = 本单}，
     * 命中面 <b>1 行</b>），🚫 不碰任何模板、不碰别的单、不碰共享基座。
     *
     * <h3>🚨 必须在 {@code configure} 之前调</h3>
     * 物化发生在 {@code configure} 请求内部；事后再绑模板<b>不会自愈</b>
     * （失败哨兵一旦落库，{@code ensureCardValues} 的 {@code IS NULL} 谓词永不再重选该行
     * —— 这正是 D-30 要修的缺陷）。⇒ 绑晚了等于没绑。
     *
     * @return 实际绑上的模板 id（已回读自检）
     */
    protected String bindQuotationTemplate(Fx fx) {
        String pick = pickTwoTabTemplate();
        QuarkusTransaction.requiringNew().run(() -> em.createNativeQuery(
                        "UPDATE quotation SET customer_template_id=CAST(:t AS uuid) WHERE id=:q")
                .setParameter("t", pick).setParameter("q", fx.quotationId()).executeUpdate());
        // 🚨 干预生效自检：不回读就不许解读后面任何读数（testing.md §4「先证明干预真的生效」）
        String back = scalar("SELECT coalesce(customer_template_id::text,'(NULL)') FROM quotation WHERE id='"
                + fx.quotationId() + "'");
        assertEquals(pick, back,
                "夹具前置（D-31）：UPDATE quotation SET customer_template_id 后回读应等于 " + pick
                        + "，实际 " + back + " ⇒ 干预未生效，后面的 compData / _record 读数一律作废");
        System.out.println("[夹具 D-31] quotation=" + fx.quotationId()
                + " customer_template_id 已在 configure **之前**绑为 " + back
                + "（共享基座 newFixture 不写该列；它为 NULL 时 compData 恒 0 条）");
        return pick;
    }

    /**
     * 造一个<b>已绑报价模板</b>的夹具 —— {@code newFixture(label, referenceCategoryId())}
     * + {@link #bindQuotationTemplate(Fx)}，顺序固定在 {@code configure} 之前。
     * <p>🚦 两个前置都是<b>构造性排除混淆项</b>，不是放宽断言：产品分类排除「模板解析不到」，
     * 报价模板排除「driver 组件 0 个 ⇒ 静默 return」。
     */
    protected Fx newBoundFixture(String label) {
        UUID catId = referenceCategoryId();
        Fx fx = newFixture(label, catId);
        String tpl = bindQuotationTemplate(fx);
        System.out.println("[夹具] " + label + " customer=" + fx.customerNo() + " quotation=" + fx.quotationId()
                + " category=" + catId + " customer_template_id=" + tpl);
        return fx;
    }

    /** 库里既有的、{@code PUBLISHED} 的、同时含两个目标页签的模板（组件最多的那个）。 */
    private String pickTwoTabTemplate() {
        String pick = scalar("SELECT t.id::text FROM template t "
                + "WHERE t.status='PUBLISHED' "
                + "  AND EXISTS(SELECT 1 FROM template_component tc JOIN component c ON c.id=tc.component_id "
                + "             WHERE tc.template_id=t.id AND c.name='" + TAB_MBOM_COMPONENT + "') "
                + "  AND EXISTS(SELECT 1 FROM template_component tc JOIN component c ON c.id=tc.component_id "
                + "             WHERE tc.template_id=t.id AND c.name='" + TAB_EBOM_COMPONENT + "') "
                + "ORDER BY (SELECT count(*) FROM template_component x WHERE x.template_id=t.id) DESC LIMIT 1");
        assertNotNull(pick, "夹具前置：库里应有一个 PUBLISHED 模板同时含「" + TAB_MBOM_COMPONENT + "」与「"
                + TAB_EBOM_COMPONENT + "」两个页签。取不到 ⇒ AC-11 的两页签断言无从验起（判【未验证】）");
        return pick;
    }

    /**
     * 🩹 把 <b>BL-0202</b>（选配建的 {@code quotation_line_item.template_id} 恒 NULL）这个
     * <b>已证无因果</b>的混淆项，从归因里显式排除掉。
     *
     * <h3>🔴 2026-09-10 更正（D-33 / B-26）—— 本方法原 javadoc 把因果<b>写反了</b></h3>
     * <b>改前原文</b>：「🔬 2026-09-10 实测：{@code template_id} 为 NULL 时本单
     * {@code quotation_line_component_data} 物化 <b>0 条</b> ⇒ 页签 0 行。」
     * <p>⚠️ <b>相关性是真的，因果是反的。</b> 那一轮两列<b>同时</b>为 NULL，本方法只动了<b>行级</b>的
     * {@code quotation_line_item.template_id}，而卡片物化链路读的是<b>报价单级</b>的
     * {@code quotation.customer_template_id}（{@code CardSnapshotService} 的调用处传的就是
     * {@code q.customerTemplateId}）—— 改的根本不是同一列。
     * <p>🔬 <b>后端诊断实测反证</b>：把 {@code quotation_line_item.template_id} <b>留 NULL</b>、
     * 只绑 {@code quotation.customer_template_id}，{@code compData} 照样 <b>14 条</b>
     * ⇒ <b>BL-0202 与「compData 0 条」无因果关系</b>。真正的单一变量见
     * {@link #bindQuotationTemplate(Fx)}。
     * <p>🚨 <b>注释写反比没注释更危险</b>：不改这段，下一个人还会顺着 BL-0202 查一遍
     * （本任务已因过时/错误注释踩过两次坑）。
     *
     * <h3>那本方法现在还留着干什么</h3>
     * 它<b>不再是</b>让卡片物化出来的手段（那件事由 {@link #bindQuotationTemplate(Fx)} 做，
     * 且必须在 {@code configure} <b>之前</b>）。它只剩一个作用：把行级 {@code template_id}
     * 也补上，让「页签读不到模板」这个<b>理论上</b>的混淆项在失败信息里可被排除。
     * ⇒ 🚫 <b>它的返回值不是「卡片能不能物化」的判据</b>，也 🚫 不要再据此推断 BL-0202 的影响面。
     *
     * <p>🚦 <b>命中面</b>：只 UPDATE 本单自己的 {@code quotation_line_item}（{@code quotation_id} 限死），
     * 🚫 不碰任何模板、不碰别的单。
     *
     * @return 实际生效的 {@code template_id}（原本非 NULL 时原样返回）
     */
    protected String ensureLineTemplate(Fx fx) {
        String tpl = scalar("SELECT template_id::text FROM quotation_line_item WHERE quotation_id='"
                + fx.quotationId() + "' AND parent_line_item_id IS NULL LIMIT 1");
        if (tpl != null && !tpl.isBlank()) {
            System.out.println("[夹具] 报价行已带 template_id=" + tpl + "（无需补）");
            return tpl;
        }
        String pick = pickTwoTabTemplate();
        QuarkusTransaction.requiringNew().run(() -> em.createNativeQuery(
                        "UPDATE quotation_line_item SET template_id=CAST(:t AS uuid) WHERE quotation_id=:q")
                .setParameter("t", pick).setParameter("q", fx.quotationId()).executeUpdate());
        System.out.println("[夹具] 🩹 报价行 template_id 原为 NULL（BL-0202，本期明确不做）⇒ 已补成 " + pick
                + "。📌 D-33 更正：这一列**与 compData 0 条无因果**，真正的变量是 quotation.customer_template_id");
        return pick;
    }

    /** AC-11 断言的两个页签对应的组件名（实查库内既有组件）。 */
    protected static final String TAB_MBOM_COMPONENT = "T260907-物料BOM";
    protected static final String TAB_EBOM_COMPONENT = "T260907-物料与元素BOM";

    /** 占位指纹 —— 🚫 不是被测的 {@code row_fingerprint} 算法，只为满足 {@code char(64) NOT NULL}。 */
    private static String fp(String mno, String kind, int seq) {
        return String.format("%064x", (long) ((mno + kind + seq + RUN_C).hashCode() & 0xffffffffL));
    }

    // ───────── 前置闸门：_record 投影的必要条件（D-22 新增，把「未验证」与「缺陷」分开）─────────

    /**
     * ⛔ <b>{@code _record} 投影的必要条件闸门</b> —— 本单是否已物化出 {@code quotation_line_component_data}。
     *
     * <h3>它分开的是什么</h3>
     * {@code syncRecordsForFlow} 的投影<b>以「本单有组件数据」为输入</b>：compData 为空时它
     * <b>早退跳过</b>（实测日志原文：{@code [ds-record] quotation=… 命中 1 个轴值但无组件数据，跳过}）。
     * ⇒ 本单 compData = 0 时，「{@code _record} 0 行」是<b>前置不成立</b>，
     * 🚫 <b>不是</b>「投影坏了」，也 🚫 <b>不是</b>「通过」。
     *
     * <h3>🔬 为什么这道闸门是 D-14 之后才需要的</h3>
     * 方案②（D-7）下 {@code SelDsQuoteWriter} 会<b>直写 {@code _record}</b>
     * （前一轮日志：{@code [ds-record][direct] … 整组覆盖 —— 删 0 行、写 1 行}），
     * 与 compData 无关 ⇒ 那时 {@code _record} 必有行，本限制看不见。
     * D-14 回退掉直写路径后，{@code _record} <b>只剩</b>「建单末尾投影」这一条来源，
     * 于是本片前一轮已登记的 <b>限制A</b>（@QuarkusTest 隔离夹具下三条服务端物化入口全返 200 但
     * {@code quotation_line_component_data} 恒 0 条）<b>第一次暴露到 _record 这一层</b>。
     *
     * <p>🚫 刻意<b>硬失败</b>而不 skip：skip 在 surefire 汇总里长得和「通过」几乎一样。
     */
    protected void requireCardDataMaterialized(Fx fx, String acLabel) {
        long li = count("SELECT count(*) FROM quotation_line_item WHERE quotation_id='"
                + fx.quotationId() + "'");
        long cd = count("SELECT count(*) FROM quotation_line_component_data cd "
                + "JOIN quotation_line_item l ON l.id = cd.line_item_id "
                + "WHERE l.quotation_id='" + fx.quotationId() + "'");
        List<Object> tpls = col("SELECT coalesce(template_id::text,'(NULL)') FROM quotation_line_item "
                + "WHERE quotation_id='" + fx.quotationId() + "'");
        System.out.println("[" + acLabel + " 投影前置] 报价行=" + li + " 行 / template_id=" + tpls
                + " / quotation_line_component_data=" + cd + " 条");
        assertTrue(cd > 0,
                "⛔ " + acLabel + " 【未验证 · 环境前置未满足】（**不是「通过」，也不是被测功能的结论**）："
                        + "本单 quotation_line_component_data = 0 条（报价行 " + li + " 行，template_id=" + tpls + "）。"
                        + "\n  🔑 _record 的**唯一来源**是建单末尾的 syncRecordsForFlow 投影，"
                        + "而它以「本单有组件数据」为输入 —— compData 为空时早退跳过"
                        + "（日志原文：[ds-record] quotation=… 命中 1 个轴值但无组件数据，跳过）"
                        + "\n  ⇒ 此处的「_record 0 行」是**前置不成立**，🚫 不许读成「投影坏了」。"
                        + "\n  📌 已登记的**限制A**（本片前一轮回报）：@QuarkusTest 隔离夹具下三条服务端物化入口"
                        + "（GET /quotations/{id} · PUT /{id}/draft · POST /{id}/ensure-card-values）"
                        + "全返 200 但 compData 恒 0 条；既有测试 CardValuesRecomputeStableTest 也登记过同型现象。"
                        + "\n  ⚠️ 两库基线不同，这一项要一并排查：cpq_db_test 的 component_sql_view 42 段 / "
                        + "template_component 78 行 / v_compat_* 0 张，而 cpq_db_0724 是 99 / 146 / 3。"
                        + "\n  👉 需要主线定夺：这条 AC 改到 **live/dev 环境**或 **E2E** 层验"
                        + "（AC-13 的 live 证据后端已在 D-21 做过 A/B/A 还原实验）。");
    }

    // ────────────── 假绿防线 ②：指纹复用（D-22 新增，主线点名的两类假读数之一）──────────────

    /**
     * 🚨 <b>指纹复用假绿</b>的防线。
     *
     * <p>选配提交返 200 <b>不等于</b>「铸了新料号」：输入指纹命中已有产品时响应是
     * {@code fingerprintMatched=true} + {@code reusedHfPartNos=[…]}，<b>一个新料号都没铸</b>。
     * 此时再去查「主表该料号有行」会被<b>存量行</b>骗过 —— 断言全绿，
     * 而 AC-10 / AC-11 / AC-13 / AC-14 断的其实是「<b>本次</b>新铸料号的那几行」。
     *
     * <p>⇒ 凡是断「新铸」的用例，先把这条钉死。载荷可能在根层也可能在 {@code data} 下
     * （2026-09-10 实测本端点在<b>根层</b>），两条路径都试；<b>取不到也硬失败</b>，
     * 🚫 不许因为「取不到」就当通过。
     */
    protected void assertFreshlyMinted(Response res, String acLabel) {
        Object fp = jsonAny(res, "fingerprintMatched", "data.fingerprintMatched");
        Object reused = jsonAny(res, "reusedHfPartNos", "data.reusedHfPartNos");
        System.out.println("[" + acLabel + " 指纹自检] fingerprintMatched=" + fp
                + " reusedHfPartNos=" + reused);
        assertNotNull(fp, acLabel + " 指纹自检：响应里取不到 fingerprintMatched（根层与 data 下都试过）"
                + " ⇒ 契约变了，判不出「本次是否真的铸了新料号」。"
                + "🚫 不许当通过 —— 这正是主线点名的第二类假读数。响应=" + res.asString());
        assertEquals(Boolean.FALSE, fp,
                acLabel + " 指纹自检：本用例断的是「**本次新铸**料号的落库/渲染」，"
                        + "而响应 fingerprintMatched=" + fp + "（复用了 " + reused + "）"
                        + " ⇒ 本次一个新料号都没铸，后面「主表有行」会被**存量行**骗过 = 假绿。"
                        + "👉 修法是把零件输入改成本轮唯一（见 freshPartName），不是放宽断言。");
    }

    /** 载荷路径不确定时逐个试，返回第一个非 null。 */
    protected static Object jsonAny(Response r, String... paths) {
        for (String path : paths) {
            Object v;
            try { v = r.jsonPath().get(path); } catch (RuntimeException e) { continue; }
            if (v != null) return v;
        }
        return null;
    }

    /**
     * 本轮唯一的零件品名 —— 让指纹 {@code PART=…} 段每轮都不同，
     * 构造性排除「上一轮的料号被复用」这个假绿来源（主线点名）。
     */
    protected String freshPartName(String tag) {
        return C + tag + "-" + RUN_C;
    }

    // ─────────────── 主表读取（AC-10 / AC-11 / AC-13 / AC-14 的可观测面）───────────────

    /**
     * 本料号在 {@code ds_quote_material_bom} <b>主表</b>的行（AC-10① 点名的那几列）。
     * <p>🚦 一律按 {@code material_no = 本片自铸料号} 收窄 —— 🚫 不是全表计数（{@code test.md §2}）。
     */
    protected List<Object[]> mainMbomRows(String materialNo) {
        return rows("SELECT item_seq::text, input_material_no, coalesce(output_material_type,'(NULL)'), "
                + "coalesce(material_ratio::text,'(NULL)'), coalesce(version_no::text,'(NULL)'), "
                + "coalesce(source,'(NULL)'), coalesce(customer_no,'(NULL)'), "
                + "coalesce(row_fingerprint,'(NULL)') "
                + "FROM " + MBOM + " WHERE material_no='" + materialNo + "' ORDER BY item_seq");
    }

    /** 本料号在 {@code ds_quote_element_bom} <b>主表</b>的行（AC-10② 点名的那几列）。 */
    protected List<Object[]> mainEbomRows(String materialNo) {
        return rows("SELECT item_seq::text, element_code, coalesce(content_pct::text,'(NULL)'), "
                + "coalesce(version_no::text,'(NULL)'), coalesce(source,'(NULL)') "
                + "FROM " + EBOM + " WHERE material_no='" + materialNo + "' ORDER BY element_code");
    }

    /** 可读地打印 {@code rows()} 的结果（失败信息里要带实际值，{@code testing.md §3}）。 */
    protected static String dump(List<Object[]> rs) {
        StringBuilder sb = new StringBuilder();
        for (Object[] r : rs) {
            sb.append("\n    ");
            for (Object c : r) sb.append('[').append(c).append(']');
        }
        return sb.length() == 0 ? "(空)" : sb.toString();
    }

    // ─────────────────────────── 还原 ───────────────────────────

    /**
     * 清掉本片自造的 {@code _record} 行与自造的「既有料号」。
     *
     * <p>🚦 <b>命中面</b>：{@code _record} 按 <b>本轮自造的 quotation_id</b>；
     * 自造料号按 <b>料号全名 + {@code source='TEST'}</b>（{@code source} 是白名单口径，
     * 🚫 不写 {@code source <> 'IMPORT'} —— 那语义是「除导入外都能删」，
     * 明天多一条写入来源就会被这段静默删掉）。
     * 删除行数一律打印，写宽了第一次跑就看得见。
     *
     * <p>🚫 无 TRUNCATE / 无 DROP / 无缺 WHERE 的 DELETE。
     * <p>📌 JUnit 5 子类 {@code @AfterEach} 先于父类执行 ⇒ 本方法跑在父类删报价单之前，
     * {@code quotation_id} 还查得到（{@code _record} <b>无外键、不级联</b>，必须自己收）。
     */
    @AfterEach
    void cleanupOwnRecords() {
        List<UUID> qids = List.copyOf(recordQuotationIds);
        List<String> mnos = List.copyOf(seededMaterialNos);
        recordQuotationIds.clear();
        seededMaterialNos.clear();
        if (qids.isEmpty() && mnos.isEmpty()) return;
        try {
            QuarkusTransaction.requiringNew().run(() -> {
                for (UUID qid : qids) {
                    for (String t : List.of(MBOM_REC, EBOM_REC)) {
                        if (!tableExists(t)) continue;
                        int n = em.createNativeQuery("DELETE FROM " + t + " WHERE quotation_id=:q")
                                .setParameter("q", qid).executeUpdate();
                        if (n > 0) System.out.println("[还原] " + t + " 删本单 " + qid + " 的 " + n + " 行");
                    }
                }
                // 🩹 补父类基座的清理缺口 —— 详见 clearSubmitArtifacts 的说明
                for (UUID qid : qids) clearSubmitArtifacts(qid);
                for (String mno : mnos) {
                    int e = em.createNativeQuery("DELETE FROM " + EBOM
                                    + " WHERE material_no=:m AND source='TEST'")
                            .setParameter("m", mno).executeUpdate();
                    int b = em.createNativeQuery("DELETE FROM " + MBOM
                                    + " WHERE material_no=:m AND source='TEST'")
                            .setParameter("m", mno).executeUpdate();
                    int c = em.createNativeQuery("DELETE FROM ds_quote_customer_part "
                                    + " WHERE material_no=:m")
                            .setParameter("m", mno).executeUpdate();
                    int m = em.createNativeQuery("DELETE FROM ds_quote_material "
                                    + " WHERE material_no=:m AND source='TEST'")
                            .setParameter("m", mno).executeUpdate();
                    System.out.println("[还原] 自造料号 " + mno + " 清理：element_bom=" + e
                            + " material_bom=" + b + " customer_part=" + c + " material=" + m);
                }
            });
        } catch (RuntimeException ex) {
            // 🚨 清理失败不能连累父类的还原（父类还要删报价单/客户）
            System.out.println("[还原] S-C 自有清理异常（不掩盖，如实打印）：" + ex);
        }
        // 还原自检：让「残留」以残留的名义硬失败，不许伪装成下一轮的业务缺陷
        for (String mno : mnos) {
            assertEquals(0L, count("SELECT count(*) FROM ds_quote_material WHERE material_no='"
                            + mno + "' AND source='TEST'"),
                    "还原自检：ds_quote_material 仍有本轮自造料号 " + mno + " 的残留");
        }
        for (UUID qid : qids) {
            if (!tableExists(MBOM_REC)) continue;
            assertEquals(0L, count("SELECT count(*) FROM " + MBOM_REC + " WHERE quotation_id='" + qid + "'"),
                    "还原自检：" + MBOM_REC + " 仍有本单 " + qid + " 的残留");
        }
    }

    /**
     * 🩹 <b>补共享基座的一处清理缺口</b>：把本单提交（{@code POST /{id}/submit}）派生出来的
     * 那些「引用报价单」的行先删掉，否则父类 {@code restoreFixtures} 的
     * {@code DELETE FROM quotation} 会撞外键。
     *
     * <h3>🔬 实证（2026-09-10，本片首轮）</h3>
     * {@code ac16_noDuplicateAfterSecondTrigger} 调了 {@code submit} ⇒ 生成一行 {@code costing_order}
     * ⇒ 父类清理抛
     * {@code ConstraintViolationException: … "costing_order_quotation_id_fkey" … [DELETE FROM quotation WHERE id=?]}
     * ⇒ 那个 {@code QuarkusTransaction.requiringNew()} <b>整段回滚</b>
     * ⇒ 连带 {@code sel_part_signature} / {@code customer} 的删除也没生效
     * ⇒ 报出来的却是 <b>「还原自检：sel_part_signature 仍有 … 的残留」</b>。
     *
     * <p>⚠️ <b>那条报错长得完全不像「submit 产生了 costing_order」</b>，
     * 而更像「清理逻辑漏了一张表」或「上一轮脏数据」。
     * 🚨 这个缺口对<b>任何会调 submit 的分片都成立</b> —— <b>S-全局 片（AC-12/AC-17 要 submit + 核价通过）
     * 必然撞它</b>，已在回报里点名给主线。
     *
     * <p>🚦 <b>命中面</b>：全部收窄到「本片自造的那一个 {@code quotation_id}」，
     * 🚫 无 TRUNCATE / DROP / 无 WHERE 的 DELETE，不碰任何别人的单。
     * 📌 只删本片实际会产生的那几张（{@code costing_order} 及其唯一子表、审批、视图结构、SQL 快照）；
     * 🚫 不做「把所有引用 quotation 的表都删一遍」那种宽口径 —— 表清单是<b>只读查
     * {@code information_schema} 得来的</b>，写宽了会误删别人的东西。
     */
    private void clearSubmitArtifacts(UUID quotationId) {
        int child = em.createNativeQuery("DELETE FROM costing_order_version_override WHERE costing_order_id IN "
                        + "(SELECT id FROM costing_order WHERE quotation_id=:q)")
                .setParameter("q", quotationId).executeUpdate();
        int co = em.createNativeQuery("DELETE FROM costing_order WHERE quotation_id=:q")
                .setParameter("q", quotationId).executeUpdate();
        int ap = em.createNativeQuery("DELETE FROM quotation_approval WHERE quotation_id=:q")
                .setParameter("q", quotationId).executeUpdate();
        int vs = em.createNativeQuery("DELETE FROM quotation_view_structure WHERE quotation_id=:q")
                .setParameter("q", quotationId).executeUpdate();
        int sq = em.createNativeQuery("DELETE FROM quotation_component_sql_snapshot WHERE quotation_id=:q")
                .setParameter("q", quotationId).executeUpdate();
        if (child + co + ap + vs + sq > 0) {
            System.out.println("[还原] 🩹 提交派生物清理 单=" + quotationId
                    + " costing_order_version_override=" + child + " costing_order=" + co
                    + " quotation_approval=" + ap + " quotation_view_structure=" + vs
                    + " quotation_component_sql_snapshot=" + sq
                    + "（不删这些，父类 DELETE FROM quotation 会撞外键并整段回滚）");
        }
    }

    /** 让父类的 {@code assertNotNull} 风格断言可复用。 */
    protected void assertNonEmpty(long actual, String what) {
        assertTrue(actual > 0, "🚨 " + what + " 为 0 ⇒ 断言从未真正执行（假绿，testing.md §3）。"
                + "空列表 / 0 行 / 「—」/「加载中…」一律不算通过。");
        assertNotNull(Long.valueOf(actual), what);
    }
}
