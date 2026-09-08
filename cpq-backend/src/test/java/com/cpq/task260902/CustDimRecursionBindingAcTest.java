package com.cpq.task260902;

import com.cpq.formula.dataloader.QuotationIdContext;
import com.cpq.quotation.entity.QuotationLineItem;
import com.cpq.quotation.service.BomTreeRenderService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>task-260907 · B-7a 运行时绑定证据（AC-1 的 Java 侧那一半）。</b>
 *
 * <h3>本类补的是哪个缺口</h3>
 * 姊妹类 {@link CustDimRecursionAcTest} 直接把 {@code sql_template} 里的占位符替换成<b>字面量</b>
 * 后执行——它证明的是「模板本身按客户隔离」。但那条路径<b>完全绕开了 Java</b>：
 * 即使 {@code BomTreeRenderService} 根本没把 {@code :customerCode} 纳入
 * {@code TREE_PARAM} 的绑定协议、或者绑成了 null，那个测试照样全绿。
 * ⇒ 那种绿是<b>假绿</b>：模板对了、运行时仍然按「customer_no 最小的那家」展开，
 *   而且不报错（COALESCE 兜底会把它变成一次静默降级）。
 *
 * <p>本类走<b>真实入口</b> {@link BomTreeRenderService#collectTotalMaterialNoUnion}：
 * 由报价行的 {@code quotationId} → {@code quotation.customer_id} → {@code customer.code}
 * 一路解析出客户码再绑进递归，与生产路径逐位相同。
 *
 * <h3>三条断言</h3>
 * <ol>
 *   <li>客户 A 的报价单展开出的闭包<b>含</b> A 的子件、<b>不含</b> B 的；</li>
 *   <li>客户 B 的反之；</li>
 *   <li>无报价单上下文的轻量行（{@code quotationId == null}，取数配置器预览那条路）
 *       <b>逐位退回改造前行为</b>——不是空集。这条是回归护栏：直接把
 *       {@code _cust} 换成 {@code :customerCode} 而不加 COALESCE 时，本条会红。</li>
 * </ol>
 *
 * <p>夹具前缀 {@code T260907C-}（{@link CustDimBase#PREFIX}），
 * 清理一律按本轮自建的<b>完整值</b>精确删，🚫 不用前缀 LIKE。
 */
@QuarkusTest
@DisplayName("task-260907 · B-7a 递归的 :customerCode 在【运行时】真的按本单客户绑上了")
class CustDimRecursionBindingAcTest extends CustDimBase {

    @Inject
    BomTreeRenderService bomTreeRenderService;

    /** 全部 <= 20 字符：material_bom_item.customer_no / material_no 都是 varchar(20)。 */
    private static final String ROOT = "T260907C-BRT";
    private static final String CHILD_A = "T260907C-BA1";
    private static final String CHILD_B = "T260907C-BB1";
    /** 刻意让 A 的客户号字典序小于 B —— 老行为恒选 A，B 侧因此成为真正的证伪点。 */
    private static final String CUST_A = "T260907C-BCA";
    private static final String CUST_B = "T260907C-BCB";

    /** ds_ 侧夹具：与 V6 侧分开的一组料号，专验「递归换读兼容视图后 ds_ 侧也隔离」。 */
    private static final String DS_ROOT = "T260907C-DRT";
    private static final String DS_CHILD_A = "T260907C-DA1";
    private static final String DS_CHILD_B = "T260907C-DB1";

    private UUID custIdA;
    private UUID custIdB;
    private UUID quoIdA;
    private UUID quoIdB;

    private void build() {
        assertEquals(0L, count("SELECT count(*) FROM material_bom_item WHERE material_no='" + ROOT + "'"),
                "夹具自检：根料号 " + ROOT + " 竟已存在，断言会被存量行冒充");
        assertTrue(CUST_A.compareTo(CUST_B) < 0,
                "夹具自检：CUST_A 必须字典序小于 CUST_B，否则老行为恰好选中 B，"
                        + "「B 侧拿到自己的子件」会在【没修】的情况下也成立（假阳性）");

        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("INSERT INTO customer (id,name,code,level,accumulated_amount,status,version,created_at,updated_at)"
                            + " VALUES (gen_random_uuid(),:n,:c,'STANDARD',0,'ACTIVE',0,NOW(),NOW())")
                    .setParameter("n", CUST_A).setParameter("c", CUST_A).executeUpdate();
            em.createNativeQuery("INSERT INTO customer (id,name,code,level,accumulated_amount,status,version,created_at,updated_at)"
                            + " VALUES (gen_random_uuid(),:n,:c,'STANDARD',0,'ACTIVE',0,NOW(),NOW())")
                    .setParameter("n", CUST_B).setParameter("c", CUST_B).executeUpdate();
        });
        custIdA = UUID.fromString(scalar("SELECT id::text FROM customer WHERE code='" + CUST_A + "'"));
        custIdB = UUID.fromString(scalar("SELECT id::text FROM customer WHERE code='" + CUST_B + "'"));

        String salesRepId = scalar("SELECT id::text FROM \"user\" ORDER BY created_at LIMIT 1");
        assertNotNull(salesRepId, "夹具前置：user 表为空，建不出报价单（地基故障，不是 AC 结论）");

        QuarkusTransaction.requiringNew().run(() -> {
            for (String[] pair : new String[][]{{CUST_A, custIdA.toString()}, {CUST_B, custIdB.toString()}}) {
                em.createNativeQuery("INSERT INTO quotation (id,quotation_number,customer_id,name,sales_rep_id,"
                                + "status,tax_rate,tax_amount,bound_global_variables_snapshot,user_data_version,"
                                + "created_at,updated_at) VALUES (gen_random_uuid(),:qn,CAST(:cid AS uuid),:nm,"
                                + "CAST(:sid AS uuid),'DRAFT',0,0,'[]'::jsonb,0,NOW(),NOW())")
                        .setParameter("qn", "T260907C-Q-" + pair[0])
                        .setParameter("cid", pair[1])
                        .setParameter("nm", "T260907C-绑定验证-" + pair[0])
                        .setParameter("sid", salesRepId)
                        .executeUpdate();
            }
            insertV6(CUST_A, ROOT, CHILD_A);
            insertV6(CUST_B, ROOT, CHILD_B);
            // ── ds_ 侧：同一料号挂两个客户、子件不同（扇出 = 2）──────────────────────
            // 🚫 不能指望回填制造出来 —— 回填只能给每条现有行赋一个客户，拆不出第二行。
            //    但【插入】可以：B-2/B-6 之后 ds_quote_material_bom 的轴是 (customer_no, material_no)，
            //    同料号两客户是合法的两行。现网扇出恒为 1，不自己造就只能验到「单客户下等价」= 零证据。
            insertDs(CUST_A, DS_ROOT, DS_CHILD_A);
            insertDs(CUST_B, DS_ROOT, DS_CHILD_B);
            // 兼容视图 ds_ 分支的 EXISTS 过滤要求料号落在 cust_scope 内，否则整支被滤掉、
            // 断言会以「两边都空」的形态恒真通过（空跑）。
            insertCustomerPart(CUST_A, DS_ROOT);
            insertCustomerPart(CUST_B, DS_ROOT);
        });
        quoIdA = UUID.fromString(scalar("SELECT id::text FROM quotation WHERE quotation_number='T260907C-Q-" + CUST_A + "'"));
        quoIdB = UUID.fromString(scalar("SELECT id::text FROM quotation WHERE quotation_number='T260907C-Q-" + CUST_B + "'"));

        assertEquals(2L, count("SELECT count(DISTINCT customer_no) FROM material_bom_item WHERE material_no='" + ROOT + "'"),
                "夹具自检：根料号应分属 2 个不同客户，否则验不到跨客户");
    }

    private void insertDs(String customerNo, String parent, String child) {
        em.createNativeQuery("INSERT INTO ds_quote_material_bom (customer_no,material_no,item_seq,"
                        + "input_material_no,component_qty,version_no,row_fingerprint,source,created_at) "
                        + "VALUES (:cn,:p,1,:c,1,1,:fp,'IMPORT',NOW())")
                .setParameter("cn", customerNo).setParameter("p", parent).setParameter("c", child)
                .setParameter("fp", md5(customerNo + "|" + parent + "|" + child)
                        + md5(child + "|" + parent))
                .executeUpdate();
    }

    private void insertCustomerPart(String customerNo, String materialNo) {
        // ⚠️ ds_quote_customer_part 是【免版本表】：没有 version_no / row_fingerprint 两列
        //    （实查 11 列）。照抄带版本表的 INSERT 会直接报 column does not exist。
        em.createNativeQuery("INSERT INTO ds_quote_customer_part (customer_no,customer_product_no,"
                        + "material_no,source,created_at) VALUES (:cn,:cpn,:m,'IMPORT',NOW())")
                .setParameter("cn", customerNo).setParameter("cpn", customerNo + "-" + materialNo)
                .setParameter("m", materialNo)
                .executeUpdate();
    }

    private void insertV6(String customerNo, String parent, String child) {
        em.createNativeQuery("INSERT INTO material_bom_item (id,system_type,customer_no,material_no,"
                        + "component_no,item_seq,is_current,composition_qty,created_at,updated_at) "
                        + "VALUES (gen_random_uuid(),'QUOTE',:cn,:p,:c,10,true,1,NOW(),NOW())")
                .setParameter("cn", customerNo).setParameter("p", parent).setParameter("c", child)
                .executeUpdate();
    }

    @AfterEach
    void cleanup() {
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("DELETE FROM quotation WHERE quotation_number IN (:a,:b)")
                    .setParameter("a", "T260907C-Q-" + CUST_A).setParameter("b", "T260907C-Q-" + CUST_B)
                    .executeUpdate();
            em.createNativeQuery("DELETE FROM material_bom_item WHERE material_no=:r AND customer_no IN (:a,:b)")
                    .setParameter("r", ROOT).setParameter("a", CUST_A).setParameter("b", CUST_B)
                    .executeUpdate();
            em.createNativeQuery("DELETE FROM ds_quote_material_bom WHERE material_no=:r AND customer_no IN (:a,:b)")
                    .setParameter("r", DS_ROOT).setParameter("a", CUST_A).setParameter("b", CUST_B)
                    .executeUpdate();
            em.createNativeQuery("DELETE FROM ds_quote_customer_part WHERE material_no=:r AND customer_no IN (:a,:b)")
                    .setParameter("r", DS_ROOT).setParameter("a", CUST_A).setParameter("b", CUST_B)
                    .executeUpdate();
            em.createNativeQuery("DELETE FROM customer WHERE code IN (:a,:b)")
                    .setParameter("a", CUST_A).setParameter("b", CUST_B).executeUpdate();
        });
        assertEquals(0L, count("SELECT count(*) FROM material_bom_item WHERE material_no='" + ROOT + "'"),
                "还原自检：V6 夹具仍有残留");
        assertEquals(0L, count("SELECT count(*) FROM customer WHERE code IN ('" + CUST_A + "','" + CUST_B + "')"),
                "还原自检：夹具客户仍有残留");
        assertEquals(0L, count("SELECT count(*) FROM quotation WHERE quotation_number LIKE 'T260907C-Q-T260907C%'"),
                "还原自检：夹具报价单仍有残留");
        assertEquals(0L, count("SELECT count(*) FROM ds_quote_material_bom WHERE material_no='" + DS_ROOT + "'"),
                "还原自检：ds_ 夹具仍有残留");
        assertEquals(0L, count("SELECT count(*) FROM ds_quote_customer_part WHERE material_no='" + DS_ROOT + "'"),
                "还原自检：ds_ 客户料号夹具仍有残留");
    }

    private List<String> closureOf(UUID quotationId) {
        return closureOf(quotationId, ROOT);
    }

    private List<String> closureOf(UUID quotationId, String root) {
        QuotationLineItem lite = new QuotationLineItem();
        lite.quotationId = quotationId;          // null = 无报价单上下文（预览路径）
        lite.productPartNoSnapshot = root;
        BomTreeRenderService.MaterialUnionResult r =
                bomTreeRenderService.collectTotalMaterialNoUnion(List.of(lite), "QUOTE");
        return r.totalMaterialNo;
    }

    /**
     * 复刻报价侧【真实卡片渲染主路径】的调用形态：
     * {@code ConfigureSnapshotService:383-387} 造的 lite 行<b>不带 quotationId</b>，
     * 客户上下文只存在于外层的 {@code QuotationIdContext}（该类 :296 set / :743 clear）。
     */
    private List<String> closureAsProductionPath(UUID quotationId, String root) {
        QuotationLineItem lite = new QuotationLineItem();
        lite.id = UUID.randomUUID();
        lite.productPartNoSnapshot = root;       // 🚫 刻意不设 quotationId —— 主路径就是这样
        UUID prev = QuotationIdContext.get();
        QuotationIdContext.set(quotationId);
        try {
            return bomTreeRenderService.collectTotalMaterialNoUnion(List.of(lite), "QUOTE").totalMaterialNo;
        } finally {
            if (prev != null) QuotationIdContext.set(prev); else QuotationIdContext.clear();
        }
    }

    @Test
    @DisplayName("AC-1 运行时：A 单只展开 A 的子件 / B 单只展开 B 的 / 无客户上下文退回改造前行为")
    void customerCodeIsBoundAtRuntime() {
        build();

        List<String> byA = closureOf(quoIdA);
        List<String> byB = closureOf(quoIdB);
        List<String> noCtx = closureOf(null);
        System.out.println("[B-7a] 客户A(" + CUST_A + ") 闭包 = " + byA);
        System.out.println("[B-7a] 客户B(" + CUST_B + ") 闭包 = " + byB);
        System.out.println("[B-7a] 无报价单上下文 闭包 = " + noCtx);

        // 阳性对照先跑：闭包非空且含根，否则下面「不含对方子件」会恒真空跑
        assertTrue(byA.contains(ROOT) && byB.contains(ROOT),
                "阳性对照：闭包里连根料号都没有，递归没跑通，任何隔离结论都不可信。A=" + byA + " B=" + byB);
        assertTrue(byA.size() > 1 && byB.size() > 1,
                "阳性对照：至少一侧一层都没展开（A=" + byA + " B=" + byB + "），隔离验不到");

        assertTrue(byA.contains(CHILD_A), "AC-1(1)：A 单的闭包里没有 A 自己的子件 " + CHILD_A + "，实际=" + byA);
        assertFalse(byA.contains(CHILD_B), "AC-1(3)：A 单的闭包里出现了 B 的子件 " + CHILD_B + "，实际=" + byA);
        assertTrue(byB.contains(CHILD_B),
                "AC-1(2)：B 单的闭包里没有 B 自己的子件 " + CHILD_B + " —— 这正是改造前的症状："
                        + "运行时没把本单客户绑进 :customerCode，递归退回「customer_no 最小的那家」= "
                        + CUST_A + "。实际=" + byB);
        assertFalse(byB.contains(CHILD_A), "AC-1(3)：B 单的闭包里出现了 A 的子件 " + CHILD_A + "，实际=" + byB);

        // 回归护栏：预览路径（无 quotationId）必须逐位退回改造前行为，不能塌成「只有根」
        assertTrue(noCtx.contains(ROOT) && noCtx.contains(CHILD_A),
                "回归护栏：无报价单上下文时闭包塌了（=" + noCtx + "）。改造前它等于「customer_no 最小的那家」"
                        + "的子树，含 " + CHILD_A + "。塌成只有根 ⇒ 模板里少了 COALESCE 兜底，"
                        + "BuilderService 的预览闭包（task-260819 AC-26）会静默丢掉全部子件行。");
        assertFalse(noCtx.contains(CHILD_B),
                "回归护栏：无上下文时不应出现字典序更大的那家的子件，实际=" + noCtx);
    }

    @Test
    @DisplayName("B-7c：ds_ 侧同料号挂两客户（扇出=2）—— 换读兼容视图后仍逐客户隔离")
    void dsSideIsolationAfterCompatViewSwap() {
        build();

        String tpl = scalar("SELECT sql_template FROM costing_bom_tree_config WHERE usage='QUOTE' AND is_active");
        assertTrue(tpl != null && tpl.contains("v_compat_material_bom_item"),
                "B-7c 前置：QUOTE 递归还没换读 v_compat_material_bom_item（V427 未落）。"
                        + "此时递归只看 V6 裸表，ds_ 侧夹具压根进不了展开，"
                        + "「不含对方子件」会恒真通过 —— 那是空跑，不是通过。");

        // 阳性对照①：夹具真的构成了扇出 = 2（不是「造了但没落库」）
        assertEquals(2L, count("SELECT count(DISTINCT customer_no) FROM ds_quote_material_bom "
                        + "WHERE material_no='" + DS_ROOT + "'"),
                "夹具自检：ds_ 侧根料号应分属 2 个客户。现网扇出恒为 1，不自己造就验不到隔离。");
        // 阳性对照②：兼容视图确实把这两行都吐出来了，且客户号取的是【行自身】的权威列
        assertEquals(2L, count("SELECT count(*) FROM v_compat_material_bom_item "
                        + "WHERE material_no='" + DS_ROOT + "'"),
                "夹具自检：兼容视图应吐出 2 行（每客户各 1）。为 0 说明 cust_scope 的 EXISTS 把它滤掉了；"
                        + "为 4 说明扇出还在（JOIN 没换成 EXISTS）。");
        assertEquals(0L, count("SELECT count(*) FROM v_compat_material_bom_item v "
                        + "WHERE v.material_no='" + DS_ROOT + "' AND v.customer_no NOT IN ('"
                        + CUST_A + "','" + CUST_B + "')"),
                "夹具自检：视图给出的客户号超出了这两行自身的 customer_no —— 客户号仍是合成的，不是权威列");

        List<String> byA = closureOf(quoIdA, DS_ROOT);
        List<String> byB = closureOf(quoIdB, DS_ROOT);
        System.out.println("[B-7c] ds_ 侧 客户A 闭包 = " + byA);
        System.out.println("[B-7c] ds_ 侧 客户B 闭包 = " + byB);

        assertTrue(byA.contains(DS_ROOT) && byB.contains(DS_ROOT),
                "阳性对照：ds_ 闭包里连根都没有，递归没跑通。A=" + byA + " B=" + byB);
        assertTrue(byA.size() > 1 && byB.size() > 1,
                "阳性对照：ds_ 侧至少一边一层都没展开（A=" + byA + " B=" + byB + "），"
                        + "隔离验不到 —— 先确认递归真的读到了兼容视图的 ds_ 分支");
        assertTrue(byA.contains(DS_CHILD_A), "B-7c：A 的 ds_ 闭包缺自己的子件，实际=" + byA);
        assertFalse(byA.contains(DS_CHILD_B), "B-7c：A 的 ds_ 闭包出现了 B 的子件 —— 视图扇出仍在，实际=" + byA);
        assertTrue(byB.contains(DS_CHILD_B), "B-7c：B 的 ds_ 闭包缺自己的子件，实际=" + byB);
        assertFalse(byB.contains(DS_CHILD_A), "B-7c：B 的 ds_ 闭包出现了 A 的子件 —— 视图扇出仍在，实际=" + byB);
    }

    @Test
    @DisplayName("B-7c：报价侧【真实主路径】形态 —— lite 行不带 quotationId，客户只在 QuotationIdContext 里")
    void productionPathBindsCustomerCodeViaThreadLocal() {
        build();

        // 这条用例存在的理由：上面两条都给 lite 行设了 quotationId，而生产代码
        // (ConfigureSnapshotService:383-387) 【不设】。只验带 quotationId 的形态，
        // 主路径静默回落到老行为也照样全绿 —— 那正是 COALESCE 兜底最危险的地方。
        List<String> byA = closureAsProductionPath(quoIdA, ROOT);
        List<String> byB = closureAsProductionPath(quoIdB, ROOT);
        System.out.println("[B-7c 主路径形态] 客户A 闭包 = " + byA);
        System.out.println("[B-7c 主路径形态] 客户B 闭包 = " + byB);

        assertTrue(byA.contains(ROOT) && byB.contains(ROOT),
                "阳性对照：闭包里连根都没有。A=" + byA + " B=" + byB);
        assertTrue(byA.contains(CHILD_A), "主路径：A 的闭包缺自己的子件，实际=" + byA);
        assertTrue(byB.contains(CHILD_B),
                "主路径：B 的闭包里没有 B 自己的子件 —— :customerCode 没能从 QuotationIdContext 兜底解析出来，"
                        + "递归静默回落到「customer_no 最小的那家」= " + CUST_A + "。实际=" + byB);
        assertFalse(byB.contains(CHILD_A), "主路径：B 的闭包出现了 A 的子件，实际=" + byB);
        assertFalse(byA.contains(CHILD_B), "主路径：A 的闭包出现了 B 的子件，实际=" + byA);
    }
}
