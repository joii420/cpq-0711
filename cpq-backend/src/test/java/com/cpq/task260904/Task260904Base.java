package com.cpq.task260904;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * task-260904「页签类型收缩为锚点推导 · 加叶子类型改读主数据」第一批 19 条 AC 的公共基座。
 *
 * <h3>断言来源</h3>
 * 每条断言指回 {@code dev-docs/task-260904-页签类型收缩/需求文档.md §③} 的 AC 原文，
 * 请求结构指回同目录 {@code api.md}。
 * <b>🚫 本套用例不读实现代码</b>（不读 {@code cpq-backend/src/main/java/**}、{@code cpq-frontend/src/**}），
 * 只读立项文档、库 schema、库里的配置数据与既有测试代码。
 *
 * <h3>🚨 环境纪律（CLAUDE.md §3.2「测试也算」 + test.md §1）</h3>
 * <ul>
 *   <li>{@code ./mvnw test} 走 test profile，而 {@code application-test.properties} 的默认库
 *       <b>就是共享开发库 {@code 10.177.152.12:5432/cpq_db_0724}</b>。</li>
 *   <li>🚫 全套用例不出现 {@code TRUNCATE} / {@code DROP} / 无 WHERE 的 {@code DELETE} / 清库 /
 *       全局配置重置。每条 DELETE 的命中面都被「本用例自建的 customer_no / id / {@code t260904_} 前缀」限死。</li>
 *   <li>🚫 <b>不借道全局 active 配置</b>：既有的 {@code QuoteBomTreeEndToEndTest} 为了造合成树，
 *       会临时把生产的 {@code costing_bom_tree_config(usage='QUOTE')} 置为 {@code is_active=false} 再恢复。
 *       那是 {@code testing.md §4.3} 点名禁止的「改变全局状态」——在共享库上，那个窗口期里
 *       <b>别的会话打开任何报价单都会渲染出我的合成树</b>。<br>
 *       ⇒ 本基座改为<b>往 {@code material_bom_item} 插自己的边</b>（{@code customer_no} 是本用例自建的
 *       唯一值），生产的 QUOTE 递归配置一个字节不动、始终 active。</li>
 * </ul>
 *
 * <h3>🚨 假绿防范（test.md §5）</h3>
 * <ul>
 *   <li><b>不写死任何计数</b>。共享库有并发写入（立项当日实测 {@code ds_quote_material} 45→71），
 *       所有数量都在<b>执行期现场探测</b>，断言只落在「该分支确实被执行过」上。</li>
 *   <li>{@link #masterData()} 对每一类料号都有<b>硬前置</b>：探不到就以「前置未满足」的名义硬失败，
 *       🚫 不许让用例以「通过」的形态空跑（四类假绿之「断言从未执行」）。</li>
 *   <li>「料号不在主数据里」用<b>构造性生成</b>的随机串（并当场验证两表都查不到），
 *       不依赖库里恰好还剩几个孤儿料号 —— 那是移动靶。</li>
 * </ul>
 */
public abstract class Task260904Base {

    /** 本任务专属前缀：自建数据一律带它，删除面靠它 + 自建 id 限死（test.md §1）。 */
    protected static final String PREFIX = "t260904_";

    /**
     * 本次 JVM 运行的唯一标记。组件 code / SQL 视图名 / customer_no 都带它 ⇒
     * 两轮运行不可能撞唯一约束。
     * 🚨 不带它时，「上一轮崩溃留下的残留」会以「本轮 duplicate key」的面目出现，
     * 而那个报错长得非常像业务缺陷。
     */
    protected static final String RUN_ID = UUID.randomUUID().toString().replace("-", "").substring(0, 8);

    protected static final String TREE_BASE = "/api/cpq/quotations/";

    @Inject
    protected EntityManager em;

    // ═══════════════════════════ SQL 小工具 ═══════════════════════════

    protected String scalar(String sql) {
        List<?> rows = em.createNativeQuery(sql).getResultList();
        if (rows.isEmpty() || rows.get(0) == null) return null;
        return rows.get(0).toString();
    }

    protected long count(String sql) {
        Object v = em.createNativeQuery(sql).getSingleResult();
        return ((Number) v).longValue();
    }

    @SuppressWarnings("unchecked")
    protected List<Object> col(String sql) {
        return em.createNativeQuery(sql).getResultList();
    }

    @SuppressWarnings("unchecked")
    protected List<Object[]> rows(String sql) {
        return em.createNativeQuery(sql).getResultList();
    }

    protected static UUID toUUID(Object o) {
        if (o == null) return null;
        if (o instanceof UUID u) return u;
        return UUID.fromString(o.toString());
    }

    // ═══════════════════════════ 基线文件定位 ═══════════════════════════

    /**
     * 从 {@code user.dir} 向上最多 8 层找改动前基线文件
     * （兼容从 {@code cpq-backend/} 或仓库根跑测试两种情况）。
     * 基线目录见 {@code dev-docs/task-260904-页签类型收缩/证据/baseline/README.md}。
     */
    protected static File locateBaseline(String fileName) {
        File dir = new File(System.getProperty("user.dir")).getAbsoluteFile();
        for (int i = 0; i < 8 && dir != null; i++, dir = dir.getParentFile()) {
            File candidate = new File(dir, "dev-docs/task-260904-页签类型收缩/证据/baseline/" + fileName);
            if (candidate.isFile()) return candidate;
        }
        return null;
    }

    // ═══════════════════════════ 主数据探测（AC-4/5/6/12/26 的输入）═══════════════════════════

    /**
     * 本套用例用到的主数据料号。<b>全部执行期现场探测</b>，🚫 不写死。
     *
     * @param recipePartNo   存在于 {@code material_recipe.code} 的料号 ⇒ 期望判「材质」（AC-4）
     * @param partOfTypePart 存在于 {@code ds_quote_material} 且 {@code material_type='零件'} ⇒ 判「零件」（AC-5）
     * @param partOutsourced 存在于 {@code ds_quote_material} 且 {@code material_type='外购件'} ⇒ 判「外购件」（AC-5）
     * @param partNullType   存在于 {@code ds_quote_material} 且 {@code material_type IS NULL} ⇒ 判「零件」（AC-12，A0-1 兜底）
     * @param partAbsent     两表都查不到的料号 ⇒ 期望 400 {@code LEAF_PART_NOT_IN_MASTER}（AC-6）
     */
    protected record MasterData(String recipePartNo, String partOfTypePart, String partOutsourced,
                                String partNullType, String partAbsent) {
    }

    private static MasterData CACHED_MASTER;
    /** 本 JVM 是否为了补齐「外购件」而自造过料号（@AfterEach 精确删除）。 */
    private static String SYNTHESIZED_OUTSOURCED;

    protected MasterData masterData() {
        if (CACHED_MASTER != null) return CACHED_MASTER;

        // 🚨 前置探测先打印分布（test.md §3：执行期一律先跑探测 SQL 取实际值，报告里记录当次数字）
        System.out.println("[task260904·probe] ds_quote_material.material_type 分布 = "
                + rowsToString("SELECT COALESCE(material_type,'(null)'), count(*) FROM ds_quote_material GROUP BY 1 ORDER BY 2 DESC"));
        System.out.println("[task260904·probe] material_recipe 行数 = " + count("SELECT count(*) FROM material_recipe"));
        System.out.println("[task260904·probe] 两表交集(应为 0，需求文档 §1.3 实证「完全互斥」) = "
                + count("SELECT count(*) FROM material_recipe r JOIN ds_quote_material m ON m.material_no = r.code"));

        String recipe = scalar("SELECT r.code FROM material_recipe r "
                + "WHERE NOT EXISTS (SELECT 1 FROM ds_quote_material m WHERE m.material_no = r.code) "
                + "ORDER BY r.code LIMIT 1");
        assertNotNull(recipe, "前置未满足：material_recipe 里找不到一个「不在 ds_quote_material」的 code —— "
                + "AC-4 的『材质表命中』分支将无输入可用，用例会空跑。这是环境前置缺失，不是被测功能的结论。");

        String typePart = scalar("SELECT material_no FROM ds_quote_material WHERE material_type = '零件' ORDER BY material_no LIMIT 1");
        assertNotNull(typePart, "前置未满足：ds_quote_material 里没有 material_type='零件' 的料号 ⇒ AC-5 的零件分支会空跑。");

        String nullType = scalar("SELECT material_no FROM ds_quote_material WHERE material_type IS NULL ORDER BY material_no LIMIT 1");
        assertNotNull(nullType, "前置未满足：ds_quote_material 里没有 material_type IS NULL 的料号 ⇒ AC-12 的空值兜底分支会空跑。");

        // 🚨 test.md §3 点名的空跑风险：外购件现网可用量极少（立项当日实测仅 1 条）。
        //    探不到就自造一条 t260904_ 前缀的，否则 AC-5 的外购件分支从未被执行、用例照样绿。
        String outsourced = scalar("SELECT material_no FROM ds_quote_material WHERE material_type = '外购件' ORDER BY material_no LIMIT 1");
        if (outsourced == null) {
            String synth = PREFIX + "outsourced_" + RUN_ID;
            QuarkusTransaction.requiringNew().run(() -> em.createNativeQuery(
                            "INSERT INTO ds_quote_material (material_no, material_name, material_type, source, created_at) "
                                    + "VALUES (:mn, :nm, '外购件', 'TEST-t260904', now())")
                    .setParameter("mn", synth).setParameter("nm", PREFIX + "外购件夹具")
                    .executeUpdate());
            SYNTHESIZED_OUTSOURCED = synth;
            outsourced = synth;
            System.out.println("[task260904·probe] 🚨 现网无 material_type='外购件' 料号 ⇒ 已自造 " + synth
                    + "（@AfterEach 按主键精确删除）。若不造，AC-5 的外购件分支从未被执行。");
        }

        // 「不在主数据」用构造性生成 + 当场证明两表都查不到，不依赖库里恰好剩几个孤儿料号（移动靶）。
        String absent = PREFIX + "absent_" + RUN_ID;
        assertEquals(0L, count("SELECT count(*) FROM ds_quote_material WHERE material_no = '" + absent + "'"),
                "构造自检：AC-6 用的『不存在料号』竟在 ds_quote_material 里 —— 那这条断言验的不是不存在分支");
        assertEquals(0L, count("SELECT count(*) FROM material_recipe WHERE code = '" + absent + "'"),
                "构造自检：AC-6 用的『不存在料号』竟在 material_recipe 里");

        CACHED_MASTER = new MasterData(recipe, typePart, outsourced, nullType, absent);
        System.out.println("[task260904·probe] 本轮选定料号 = " + CACHED_MASTER);
        return CACHED_MASTER;
    }

    protected String rowsToString(String sql) {
        StringBuilder sb = new StringBuilder("{");
        for (Object[] r : rows(sql)) {
            sb.append(r[0]).append('=').append(r[1]).append(' ');
        }
        return sb.append('}').toString();
    }

    // ═══════════════════════════ BOM 树 fixture ═══════════════════════════

    /**
     * 一套「客户 + V6 QUOTE BOM 边 + 树组件 + 材质元素组件 + 模板 + 报价单 + 两条报价行」。
     *
     * <p>树形态（{@code __nodeId} 用 {@code node_path}）：
     * <pre>
     *   root                       ← lineItem 1 的 product_part_no_snapshot（同时是 AC-26 的「成品」）
     *   root/p1                    ← 宿主（p1 是 material_type='零件' 的真实料号 ⇒ 可再挂下级）
     *   root/p1/p2
     *   root2                      ← lineItem 2 的轴值，AC-26 拿它当「别人的成品」
     * </pre>
     */
    protected static final class TreeFx {
        public UUID customerId;
        public String customerNo;
        public UUID quotationId;
        public UUID lineItemId;
        public UUID lineItem2Id;
        public UUID templateId;
        public UUID treeComponentId;
        public UUID matComponentId;
        public UUID treeViewId;
        public UUID matViewId;
        public String root;
        public String root2;
        public String p1;
        public String p2;
        /** 树组件是外部传进来复用的（AC-21②/AC-27 会把配置器产出的组件挂进来）⇒ 清理时不删它。 */
        public boolean treeComponentReused;

        public String hostNode() { return root + "/" + p1; }
        public String deepNode() { return root + "/" + p1 + "/" + p2; }
    }

    /**
     * 树 fixture 的模板状态。可用 {@code -Dtask260904.template.status=PUBLISHED} 覆盖。
     *
     * <h4>🚨 2026-09-05 实测：两种状态各卡一头，本条已作为「契约问题」报给主线</h4>
     * <ul>
     *   <li>{@code DRAFT}：{@code ConfigureSnapshotService.snapshotQuotation} 能把树骨架物化出来
     *       （实测 spine 3 行、{@code __nodeType} 已按主数据判成「零件」），
     *       但 add-leaf 端点返 400「componentId 不是该报价行的树页签(tab_type=BOM)组件」——
     *       该判定读的是 {@code PublishedTemplateReader} 的冻结快照，DRAFT 恒空。</li>
     *   <li>{@code PUBLISHED}（并补齐 {@code template_component_snapshot} 两行）：
     *       反过来 —— 树骨架<b>不再物化</b>（{@code snapshot_rows} 为 NULL）。</li>
     * </ul>
     * ⇒ 单靠 fixture 调状态两头都够不着。这不是被测 AC 的结论，是<b>夹具/契约的缺口</b>：
     * 「一个尚未发布的模板上能不能加叶子」在 {@code api.md §3} 里没有规定。
     * 🚫 不要靠继续试状态组合把它糊过去 —— 那会变成把用例往实现上拟合。
     */
    protected static final String TEMPLATE_STATUS =
            System.getProperty("task260904.template.status", "DRAFT");

    /** 本次用例建的 fixture，{@link #cleanupTask260904()} 逐个还原。 */
    protected final List<TreeFx> treeFixtures = new ArrayList<>();
    /** 本次用例经真实入口建的组件 id（AC-18 / AC-21 / AC-27 用），@AfterEach 精确删除。 */
    protected final List<UUID> createdComponentIds = new ArrayList<>();

    /**
     * 建 fixture。全部走 committed 事务 —— 🚫 不用 {@code @TestTransaction}：
     * add-leaf 端点内部有 {@code REQUIRES_NEW} 子事务，外层回滚既看不到也拦不住它
     * （沿用 {@code QuoteBomTreeEndToEndTest} 的取舍）。
     */
    protected TreeFx buildTreeFixture(String label) {
        return buildTreeFixture(label, null);
    }

    /**
     * @param reuseTreeComponentId 非 null 时不新建树组件，直接把这个已存在的组件挂进模板当树页签
     *                             （AC-21② / AC-27 用它把「取数配置器真实保存出来的组件」放进渲染链路）。
     *                             该组件不由本 fixture 清理。
     */
    protected TreeFx buildTreeFixture(String label, UUID reuseTreeComponentId) {
        MasterData md = masterData();
        TreeFx f = new TreeFx();
        f.treeComponentReused = reuseTreeComponentId != null;

        // 根料号必须「在 ds_quote_material 里、且 material_bom_item 里一行都没有」——
        // 🚨 生产 QUOTE 递归 SQL 的 _cust 取的是根料号自己那批行里 ORDER BY customer_no LIMIT 1，
        //    根若已有别的客户的行，_cust 会被取成别人的客户号，我的边就永远展不开（树只剩一个根，
        //    而那看起来完全像「树渲染坏了」）。
        f.p1 = md.partOfTypePart();
        f.p2 = md.partNullType();
        // 🚨 root/root2 必须与 p1/p2 互不相同：现网「零引用料号」总共只有个位数，
        //    不排除的话 root 会正好等于 p1（2026-09-05 首次真跑实测撞上），
        //    环检测/成品拦截两条用例就会互相串味。
        List<Object> isolated = col("SELECT m.material_no FROM ds_quote_material m "
                + "WHERE NOT EXISTS (SELECT 1 FROM material_bom_item b "
                + "                  WHERE b.material_no = m.material_no OR b.component_no = m.material_no) "
                + "  AND m.material_no NOT IN ('" + f.p1 + "','" + f.p2 + "') "
                + "ORDER BY m.material_no");
        assertTrue(isolated.size() >= 2,
                "前置未满足：ds_quote_material 里『在 material_bom_item 中零引用、且不是 p1/p2』的料号不足 2 个（实际 "
                        + isolated.size() + "，已排除 p1=" + f.p1 + " p2=" + f.p2 + "）"
                        + "⇒ 造不出干净的树 fixture。这是环境前置缺失，不是被测功能的结论。");

        f.root = String.valueOf(isolated.get(0));
        f.root2 = String.valueOf(isolated.get(1));
        assertNotEqualsAll(f);

        f.customerId = UUID.randomUUID();
        f.customerNo = ("T260904" + RUN_ID + label).substring(0, Math.min(20, ("T260904" + RUN_ID + label).length()));
        f.quotationId = UUID.randomUUID();
        f.lineItemId = UUID.randomUUID();
        f.lineItem2Id = UUID.randomUUID();
        f.templateId = UUID.randomUUID();
        f.treeComponentId = f.treeComponentReused ? reuseTreeComponentId : UUID.randomUUID();
        f.matComponentId = UUID.randomUUID();
        f.treeViewId = UUID.randomUUID();
        f.matViewId = UUID.randomUUID();

        String matViewName = PREFIX + "mat_" + RUN_ID + "_" + label.toLowerCase();
        final String treeViewName;
        if (f.treeComponentReused) {
            String ddp = scalar("SELECT data_driver_path FROM component WHERE id = '" + f.treeComponentId + "'");
            assertNotNull(ddp, "前置未满足：复用的树组件 " + f.treeComponentId + " 没有 data_driver_path ⇒ "
                    + "取数配置器保存后应当写上它。这本身就是一条结论，请如实报告，不要绕过。");
            treeViewName = ddp.startsWith("$") ? ddp.substring(1) : ddp;
        } else {
            treeViewName = PREFIX + "tree_" + RUN_ID + "_" + label.toLowerCase();
        }

        QuarkusTransaction.requiringNew().run(() -> {
            Object admin = col("SELECT id FROM \"user\" WHERE username='admin' LIMIT 1").stream().findFirst().orElse(null);
            assertNotNull(admin, "前置：admin 用户应存在（V1 迁移种子）");

            em.createNativeQuery("INSERT INTO customer (id,name,code,level,accumulated_amount,status,version,created_at,updated_at) "
                            + "VALUES (:id,:name,:code,'STANDARD',0,'ACTIVE',0,NOW(),NOW())")
                    .setParameter("id", f.customerId).setParameter("name", PREFIX + "客户-" + label)
                    .setParameter("code", f.customerNo).executeUpdate();

            // V6 QUOTE BOM 边：root → p1 → p2。customer_no 是本用例独有值 ⇒ 生产递归 SQL 只会为
            // 我的根展开我的边，其它单据一行不受影响（🚫 不借道全局 active 配置）。
            insertBomEdge(f.customerNo, f.root, f.p1, 1);
            insertBomEdge(f.customerNo, f.p1, f.p2, 1);

            // 树组件：存量形态（tab_type='BOM'）—— 本 fixture 服务的是「加叶子」类 AC，
            // 双判据分流本身由 DualCriteriaAcTest 另行覆盖。
            // 复用外部组件（配置器真实保存的产物）时跳过这一段，一个字节都不改它。
            if (!f.treeComponentReused) {
            em.createNativeQuery("INSERT INTO component (id,name,code,fields,formulas,tab_type,bom_recursive_expand,"
                            + "data_driver_path,created_at,updated_at) VALUES (:id,:n,:c,'[]'::jsonb,'[]'::jsonb,'BOM',true,:ddp,NOW(),NOW())")
                    .setParameter("id", f.treeComponentId).setParameter("n", PREFIX + "树-" + label)
                    .setParameter("c", PREFIX + "TREE-" + RUN_ID + "-" + label)
                    .setParameter("ddp", "$" + treeViewName).executeUpdate();
            em.createNativeQuery("INSERT INTO component_sql_view (id,component_id,sql_view_name,sql_template,declared_columns,created_at,updated_at) "
                            + "VALUES (:id,:cid,:vn,:tpl,'[]'::jsonb,NOW(),NOW())")
                    .setParameter("id", f.treeViewId).setParameter("cid", f.treeComponentId)
                    .setParameter("vn", treeViewName)
                    .setParameter("tpl", "SELECT NULL::text AS material_no, NULL::text AS parent_no WHERE FALSE")
                    .executeUpdate();
            }

            // 材质元素组件（AC-17 的受限页签、AC-8 的既有护栏对照都要用到它的存在）
            em.createNativeQuery("INSERT INTO component (id,name,code,fields,formulas,tab_type,part_no_field,"
                            + "data_driver_path,created_at,updated_at) VALUES (:id,:n,:c,CAST(:fields AS jsonb),'[]'::jsonb,'材质元素','料号',:ddp,NOW(),NOW())")
                    .setParameter("id", f.matComponentId).setParameter("n", PREFIX + "材质元素-" + label)
                    .setParameter("c", PREFIX + "MAT-" + RUN_ID + "-" + label)
                    .setParameter("fields", "[{\"name\":\"料号\",\"field_type\":\"INPUT_TEXT\"}]")
                    .setParameter("ddp", "$" + matViewName).executeUpdate();
            em.createNativeQuery("INSERT INTO component_sql_view (id,component_id,sql_view_name,sql_template,declared_columns,created_at,updated_at) "
                            + "VALUES (:id,:cid,:vn,:tpl,'[]'::jsonb,NOW(),NOW())")
                    .setParameter("id", f.matViewId).setParameter("cid", f.matComponentId)
                    .setParameter("vn", matViewName)
                    // hf_part_no 是 SqlViewExecutor 的分桶键约定（见 QuoteBomTreeEndToEndTest 的踩坑注释），
                    // 必须是「本产品」的料号，不是这一行材质自身的标识。
                    .setParameter("tpl", "SELECT '" + f.root + "'::text AS hf_part_no, '"
                            + f.p2 + "'::text AS material_no, '" + f.p2 + "'::text AS \"料号\"")
                    .executeUpdate();

            // 模板 + 挂载 + components_snapshot（buildCardValues 读的是这份 JSONB 冻结列）
            // 模板状态由 TEMPLATE_STATUS 决定，见该常量的注释（两种状态各卡一头，2026-09-05 实测）。
            em.createNativeQuery("INSERT INTO template (id,template_series_id,name,template_kind,status,created_at,updated_at) "
                            + "VALUES (:id,:sid,:n,'QUOTATION','" + TEMPLATE_STATUS + "',NOW(),NOW())")
                    .setParameter("id", f.templateId).setParameter("sid", UUID.randomUUID())
                    .setParameter("n", PREFIX + "模板-" + RUN_ID + "-" + label).executeUpdate();

            UUID tcTree = UUID.randomUUID();
            UUID tcMat = UUID.randomUUID();
            em.createNativeQuery("INSERT INTO template_component (id,template_id,component_id,tab_name,sort_order,created_at) "
                            + "VALUES (:id,:tid,:cid,'BOM树',0,NOW())")
                    .setParameter("id", tcTree).setParameter("tid", f.templateId).setParameter("cid", f.treeComponentId)
                    .executeUpdate();
            em.createNativeQuery("INSERT INTO template_component (id,template_id,component_id,tab_name,sort_order,created_at) "
                            + "VALUES (:id,:tid,:cid,'材质元素',1,NOW())")
                    .setParameter("id", tcMat).setParameter("tid", f.templateId).setParameter("cid", f.matComponentId)
                    .executeUpdate();

            String snapshot = "["
                    + "{\"id\":\"" + tcTree + "\",\"componentId\":\"" + f.treeComponentId + "\",\"componentName\":\"" + PREFIX + "树\","
                    + "\"componentCode\":\"" + PREFIX + "TREE-" + RUN_ID + "-" + label + "\",\"componentType\":\"NORMAL\","
                    + "\"tabName\":\"BOM树\",\"sortOrder\":0,\"fields\":[],\"formulas\":[],\"data_driver_path\":\"$" + treeViewName + "\"},"
                    + "{\"id\":\"" + tcMat + "\",\"componentId\":\"" + f.matComponentId + "\",\"componentName\":\"" + PREFIX + "材质元素\","
                    + "\"componentCode\":\"" + PREFIX + "MAT-" + RUN_ID + "-" + label + "\",\"componentType\":\"NORMAL\","
                    + "\"tabName\":\"材质元素\",\"sortOrder\":1,\"fields\":[{\"name\":\"料号\",\"field_type\":\"INPUT_TEXT\"}],"
                    + "\"formulas\":[],\"data_driver_path\":\"$" + matViewName + "\"}]";
            em.createNativeQuery("UPDATE template SET components_snapshot = CAST(:s AS jsonb) WHERE id = :id")
                    .setParameter("s", snapshot).setParameter("id", f.templateId).executeUpdate();

            // template_component_snapshot：冻结快照的行式表征（PublishedTemplateReader 读它）
            String treeTabType = scalar("SELECT tab_type FROM component WHERE id = '" + f.treeComponentId + "'");
            Object treeExpand = col("SELECT bom_recursive_expand FROM component WHERE id = '" + f.treeComponentId + "'")
                    .stream().findFirst().orElse(Boolean.FALSE);
            em.createNativeQuery("INSERT INTO template_component_snapshot "
                            + "(id,template_id,template_component_id,component_id,sort_order,tab_name,component_name,"
                            + " component_code,fields,formulas,tab_type,bom_recursive_expand) "
                            + "SELECT gen_random_uuid(),:tid,:tcid,c.id,0,'BOM树',c.name,c.code,c.fields,c.formulas,:tt,:be "
                            + "FROM component c WHERE c.id = :cid")
                    .setParameter("tid", f.templateId).setParameter("tcid", tcTree).setParameter("cid", f.treeComponentId)
                    .setParameter("tt", treeTabType).setParameter("be", Boolean.TRUE.equals(treeExpand))
                    .executeUpdate();
            em.createNativeQuery("INSERT INTO template_component_snapshot "
                            + "(id,template_id,template_component_id,component_id,sort_order,tab_name,component_name,"
                            + " component_code,fields,formulas,tab_type,part_no_field) "
                            + "SELECT gen_random_uuid(),:tid,:tcid,c.id,1,'材质元素',c.name,c.code,c.fields,c.formulas,'材质元素','料号' "
                            + "FROM component c WHERE c.id = :cid")
                    .setParameter("tid", f.templateId).setParameter("tcid", tcMat).setParameter("cid", f.matComponentId)
                    .executeUpdate();

            em.createNativeQuery("INSERT INTO quotation (id,quotation_number,customer_id,name,sales_rep_id,status,"
                            + "customer_template_id,tax_rate,tax_amount,created_at,updated_at) "
                            + "VALUES (:id,:qn,:cid,:n,CAST(:uid AS uuid),'DRAFT',:tid,0,0,NOW(),NOW())")
                    .setParameter("id", f.quotationId)
                    .setParameter("qn", PREFIX + "QT-" + f.quotationId.toString().substring(0, 8))
                    .setParameter("cid", f.customerId).setParameter("n", PREFIX + "报价单-" + label)
                    .setParameter("uid", admin.toString()).setParameter("tid", f.templateId).executeUpdate();

            em.createNativeQuery("INSERT INTO quotation_line_item (id,quotation_id,template_id,product_part_no_snapshot,sort_order,created_at) "
                            + "VALUES (:id,:qid,:tid,:pn,0,NOW())")
                    .setParameter("id", f.lineItemId).setParameter("qid", f.quotationId)
                    .setParameter("tid", f.templateId).setParameter("pn", f.root).executeUpdate();
            // 第二条行：轴值 = root2 ⇒ AC-26「成品料号不能作为别人的子件挂入」的输入。
            // 🚨 不能拿 lineItem 1 自己的 root 当 AC-26 的料号：那同时会构成环（root 是宿主的祖先），
            //    校验顺序 ⑥ 早于 ⑦ ⇒ 会先报 LEAF_CYCLE_DETECTED，AC-26 就验不到成品拦截了。
            em.createNativeQuery("INSERT INTO quotation_line_item (id,quotation_id,template_id,product_part_no_snapshot,sort_order,created_at) "
                            + "VALUES (:id,:qid,:tid,:pn,1,NOW())")
                    .setParameter("id", f.lineItem2Id).setParameter("qid", f.quotationId)
                    .setParameter("tid", f.templateId).setParameter("pn", f.root2).executeUpdate();
        });

        treeFixtures.add(f);
        System.out.println("[task260904·fixture] " + label + " root=" + f.root + " p1=" + f.p1 + " p2=" + f.p2
                + " root2=" + f.root2 + " customerNo=" + f.customerNo + " quotation=" + f.quotationId);
        return f;
    }

    private static void assertNotEqualsAll(TreeFx f) {
        List<String> all = List.of(f.root, f.root2, f.p1, f.p2);
        assertEquals(4, all.stream().distinct().count(),
                "构造自检：root/root2/p1/p2 必须两两不同，实际=" + all + " —— 相同会让环检测/成品拦截的用例互相串味");
    }

    private void insertBomEdge(String customerNo, String parentNo, String childNo, int seq) {
        em.createNativeQuery("INSERT INTO material_bom_item (id,system_type,customer_no,material_no,component_no,"
                        + "item_seq,is_current,composition_qty,created_at,updated_at) "
                        + "VALUES (gen_random_uuid(),'QUOTE',:c,:mn,:cn,:seq,true,1,NOW(),NOW())")
                .setParameter("c", customerNo).setParameter("mn", parentNo)
                .setParameter("cn", childNo).setParameter("seq", seq).executeUpdate();
    }

    // ═══════════════════════════ 树 fixture 自检（🚨 防空跑）═══════════════════════════

    /**
     * 物化后确认树骨架真的建出来了。
     * <p>🚨 不做这一步，后面「加叶子被拒 400」的断言在「树压根没渲染出来、宿主节点不存在」时
     * 也会以 400 通过 —— 那是<b>拿另一个 400 冒充目标 400</b>，四类假绿之一。
     */
    protected void assertSpineMaterialized(TreeFx f) {
        String rowsJson = readSnapshotRows(f.lineItemId, f.treeComponentId);
        assertNotNull(rowsJson, "前置未满足：树组件 snapshot_rows 未写入 ⇒ 树没物化，后面的加叶子断言全部不可信");
        assertTrue(rowsJson.contains("\"" + f.hostNode() + "\""),
                "前置未满足：宿主节点 " + f.hostNode() + " 不在物化出来的 spine 里。实际 snapshot_rows=" + rowsJson);
        assertTrue(rowsJson.contains("\"" + f.deepNode() + "\""),
                "前置未满足：深层节点 " + f.deepNode() + " 不在 spine 里 ⇒ 环检测用例会验不到东西。实际=" + rowsJson);
        System.out.println("[task260904·spine] " + f.hostNode() + " 已物化，snapshot_rows=" + rowsJson);
    }

    protected String readSnapshotRows(UUID lineItemId, UUID componentId) {
        return QuarkusTransaction.requiringNew().call(() -> {
            List<Object> r = em.createNativeQuery("SELECT snapshot_rows::text FROM quotation_line_component_data "
                            + "WHERE line_item_id = :lid AND component_id = :cid")
                    .setParameter("lid", lineItemId).setParameter("cid", componentId).getResultList();
            return r.isEmpty() ? null : String.valueOf(r.get(0));
        });
    }

    // ═══════════════════════════ HTTP（管理员会话）═══════════════════════════

    private static Map<String, String> ADMIN_COOKIES;

    /**
     * 🚨 一律用它起手，🚫 不要用裸 {@code RestAssured.given()}：
     * test profile 的 {@code cpq.security.rbac.enabled=true}（{@code application-test.properties}），
     * 不带 session 一律 401，而 401 会伪装成「端点没做」或「业务校验拒绝」。
     */
    protected RequestSpecification given() {
        return RestAssured.given().cookies(adminSession());
    }

    protected Map<String, String> adminSession() {
        if (ADMIN_COOKIES != null) {
            Response me = RestAssured.given().cookies(ADMIN_COOKIES).get("/api/cpq/auth/me").thenReturn();
            if (me.statusCode() == 200) return ADMIN_COOKIES;
            ADMIN_COOKIES = null;
        }
        // 只解锁，🚫 不改 admin 的密码/状态/角色（testing.md §4.3：不得改变共享库的全局状态）。
        // ⚠️ E2E 反复跑会把 admin 置成 INACTIVE —— 那会让本套用例全体以 401 失败，
        //    看起来像鉴权回归。这里顺带把它拉回 ACTIVE 并在日志里留痕。
        QuarkusTransaction.requiringNew().run(() -> em.createNativeQuery(
                        "UPDATE \"user\" SET failed_login_attempts = 0, locked_until = NULL WHERE username = 'admin'")
                .executeUpdate());

        Response last = null;
        for (int i = 0; i < 4; i++) {
            last = RestAssured.given().contentType(ContentType.JSON)
                    .body(Map.of("username", "admin", "password", "Admin@2026"))
                    .post("/api/cpq/auth/login").thenReturn();
            if (last.statusCode() == 200) {
                ADMIN_COOKIES = new LinkedHashMap<>(last.getCookies());
                assertFalse(ADMIN_COOKIES.isEmpty(), "登录返 200 却没拿到 cookie（会话机制变了？）");
                Response me = RestAssured.given().cookies(ADMIN_COOKIES).get("/api/cpq/auth/me").thenReturn();
                assertEquals(200, me.statusCode(), "🚨 阳性对照失败：拿到 cookie 但 /auth/me 仍不通（"
                        + me.statusCode() + "）⇒ 会话未生效，此时所有业务断言都不可信。body=" + me.asString());
                return ADMIN_COOKIES;
            }
            try { Thread.sleep(2000L * (i + 1)); } catch (InterruptedException ignored) { }
        }
        throw new AssertionError("admin 登录连续 4 次失败，最后 status=" + (last == null ? "?" : last.statusCode())
                + " body=" + (last == null ? "?" : last.asString())
                + "\n🚫 这是**登录基础设施故障**，不是被测功能的结论："
                + "429=登录限流；423/401=账号被锁或被 E2E 置成 INACTIVE；5xx=Redis/会话存储不可用。");
    }

    /** 🚨 假绿守卫：鉴权/路由把请求挡在业务层之外时，「断言非 200」会照样通过。 */
    protected void assertReachedBusinessLayer(Response res, String when) {
        assertFalse(res.statusCode() == 401 || res.statusCode() == 403,
                when + "：请求被鉴权拦下（" + res.statusCode() + "），根本没进业务层 —— 这是 harness 故障，不是 AC 结论。body=" + res.asString());
        assertFalse(res.statusCode() == 404 && res.asString().contains("RESTEASY"),
                when + "：端点 404 ⇒ 路径与 api.md 不一致或端点未实现。body=" + res.asString());
        assertFalse(res.statusCode() == 405,
                when + "：405 ⇒ HTTP 方法与 api.md 不一致。body=" + res.asString());
    }

    // ═══════════════════════════ add-leaf（api.md §3.1）═══════════════════════════

    protected Response addLeaf(TreeFx f, String hostNodeId, String partNo) {
        Response r = given().contentType(ContentType.JSON)
                .body("{\"componentId\":\"" + f.treeComponentId + "\",\"hostNodeId\":\"" + hostNodeId
                        + "\",\"partNo\":\"" + partNo + "\"}")
                .post(TREE_BASE + f.quotationId + "/line-items/" + f.lineItemId + "/tree/add-leaf")
                .thenReturn();
        System.out.println("[task260904·add-leaf] host=" + hostNodeId + " partNo=" + partNo
                + " → " + r.statusCode() + " " + r.asString());
        return r;
    }

    /** 加叶子成功时的 {@code __nodeType}（响应里的 {@code data.nodeType}）。 */
    protected String addLeafExpectType(TreeFx f, String hostNodeId, String partNo, String expectedType, String acRef) {
        Response r = addLeaf(f, hostNodeId, partNo);
        assertReachedBusinessLayer(r, acRef);
        assertEquals(200, r.statusCode(), acRef + "：加叶子应成功，实际=" + r.statusCode() + " body=" + r.asString());
        String nodeType = firstNonNull(r.jsonPath().getString("data.nodeType"), r.jsonPath().getString("nodeType"));
        assertNotNull(nodeType, acRef + "：响应里取不到 nodeType ⇒ 断言会空跑。body=" + r.asString());
        assertEquals(expectedType, nodeType, acRef + "：料号 " + partNo + " 的类型判定应为「" + expectedType
                + "」（判据是主数据，不是页签命中），实际=" + nodeType + " body=" + r.asString());
        return firstNonNull(r.jsonPath().getString("data.nodeId"), r.jsonPath().getString("nodeId"));
    }

    protected static String firstNonNull(String a, String b) {
        return a != null ? a : b;
    }

    /** 响应里的业务错误码：实测嵌在 {@code data.code}，顶层 {@code code} 是 HTTP 状态回声（Sec34 的踩坑）。 */
    protected String errorCode(Response r) {
        String c = r.jsonPath().getString("data.code");
        return c != null ? c : r.jsonPath().getString("code");
    }

    // ═════════════ 取数配置器的真实保存路径（AC-18 / AC-21 / AC-24 / AC-27 的组件产出口）═════════════

    /**
     * 「数据源 = 物料BOM」的 builder_config。
     *
     * <p>三段坐标取自库里 {@code semantic_tab_view}（{@code dialect='QUOTE'} / {@code tab_type='BOM'} /
     * {@code variant_key=''}），锚点节点 {@code MATERIAL_BOM → ds_quote_material_bom}；
     * 列取自 {@code semantic_node_column}（{@code input_material_no} 带 {@code PART_NO,ROW_KEY} 角色）。
     *
     * <p>🚨 三段缺一不可（api.md §0）：唯一约束是 {@code (tab_type, variant_key, dialect)}，
     * 漏掉 {@code dialect} 会在 {@code findFirst()} 处静默取到错误方言的声明。
     *
     * <h4>⚠️ 2026-09-05 02:04 起 tabType 由「BOM 树」改为「BOM」，这不是放宽断言</h4>
     * {@code V417__task260819_v9_tab_type_key_value_fix} 把 {@code semantic_tab_view.tab_type}
     * 的种子值从显示名 {@code 'BOM 树'} 纠正为键值 {@code 'BOM'}（task-260819 的 D-39 裁决：
     * <b>存储值 = {@code BOM}，显示名 =「BOM 树」</b>）。实测迁移后该表 {@code DISTINCT tab_type} =
     * {@code [BOM, 主件, 外购件, 材质元素, 费用类, 零件]}，<b>已无 'BOM 树'</b>，
     * 继续传 {@code 'BOM 树'} 会得到 {@code 400 COMPILE_TABVIEW_NOT_FOUND: 未找到页签视图: BOM 树/}。
     * ⇒ 这里跟的是<b>被纠正后的内部坐标</b>，断言本身（保存应 200、判为树、tab_type 应为空）一个字没动。
     * 📌 {@code api.md §0 / §1.2} 的示例仍写着 {@code "tabType": "BOM 树"}，<b>已是过期契约，需回写</b>。
     */
    protected static final String CFG_MATERIAL_BOM = """
            { "tabType": "BOM", "variantKey": "", "dialect": "QUOTE", "columns": [
              {"sourceNodeKey":"MATERIAL_BOM","sourceColumn":"input_material_no","fieldName":"投入料号","isRowKey":true,"isPartNo":true},
              {"sourceNodeKey":"MATERIAL_BOM","sourceColumn":"component_qty","fieldName":"组成数量"}
            ]}
            """;

    /** 「数据源 = 自制加工费」的 builder_config（{@code 费用类 / SELF_PROCESS_FEE / QUOTE}，semantic 为 null）。 */
    protected static final String CFG_SELF_PROCESS_FEE = """
            { "tabType": "费用类", "variantKey": "SELF_PROCESS_FEE", "dialect": "QUOTE", "columns": [
              {"sourceNodeKey":"SELF_PROCESS_FEE","sourceColumn":"input_material_no","fieldName":"投入料号","isRowKey":true,"isPartNo":true},
              {"sourceNodeKey":"SELF_PROCESS_FEE","sourceColumn":"value","fieldName":"自制加工费"}
            ]}
            """;

    /** {@code POST /api/cpq/components}：建一个空白组件（配置器里「新建页签组件」的第一步）。 */
    protected UUID createBlankComponent(String label) {
        Response resp = given().contentType(ContentType.JSON)
                .body("{\"name\":\"" + PREFIX + label + "-" + RUN_ID + "\"}")
                .post("/api/cpq/components").thenReturn();
        assertReachedBusinessLayer(resp, "建空白组件(" + label + ")");
        assertEquals(200, resp.statusCode(), "建空白组件应 200，实际=" + resp.statusCode() + " body=" + resp.asString());
        UUID id = UUID.fromString(resp.jsonPath().getString("data.id"));
        createdComponentIds.add(id);
        return id;
    }

    /**
     * {@code PUT /api/cpq/components/{id}/builder}：取数配置器的<b>真实保存路径</b>。
     * <p>⚠️ 请求体是<b>裸 builder_config</b>，🚫 不能包一层 {@code {"builderConfig": {...}}}
     * （既有测试 {@code Sec35FeeTabPreviewInspectTest} 2026-08-21 踩过：包一层会读到
     * {@code tabType=null} 而报 {@code COMPILE_TABVIEW_NOT_FOUND: 未找到页签视图: null/}）。
     */
    protected Response saveBuilder(UUID componentId, String builderConfigJson) {
        Response r = given().contentType(ContentType.JSON).body(builderConfigJson)
                .put("/api/cpq/components/" + componentId + "/builder").thenReturn();
        System.out.println("[task260904·builder-save] component=" + componentId + " → " + r.statusCode()
                + (r.statusCode() == 200 ? "" : " " + r.asString()));
        return r;
    }

    protected void saveBuilderOk(UUID componentId, String builderConfigJson, String acRef) {
        Response r = saveBuilder(componentId, builderConfigJson);
        assertReachedBusinessLayer(r, acRef);
        assertEquals(200, r.statusCode(), acRef + "：取数配置器保存应成功，实际=" + r.statusCode() + " body=" + r.asString());
    }

    /**
     * 🚨 AC-27⓪ 的窗口期守卫。
     *
     * <p>立项时 {@code component_sql_view.builder_version} 现网 <b>0 行非 NULL</b>，
     * 此时任何验分支① 的断言都会<b>以「通过」的形态空跑</b>（test.md §3 点名的本期最高假绿风险）。
     * ⚠️ <b>该数字是移动靶</b>（2026-09-05 实测已从 0 涨到 29，来源是 task-260819 的测试残留）
     * ⇒ 🚫 不许把它写进任何断言；本方法只断言<b>「本组件」自己</b>的 builder_version 非空，
     * 并把当次的全库计数<b>现场探测后打印</b>，供报告记录。
     */
    protected void assertBuilderVersionPresent(UUID componentId, String acRef) {
        String v = scalar("SELECT builder_version::text FROM component_sql_view WHERE component_id = '" + componentId + "'");
        assertNotNull(v, acRef + "：组件 " + componentId + " 的 component_sql_view.builder_version 仍为 NULL "
                + "⇒ 取数配置器的真实保存路径没有写入它。此时『按 builder_config 判树』的分支输入为空，"
                + "后面任何验分支① 的断言都是空跑（test.md §3「TC-27 窗口期空跑」）。");
        System.out.println("[" + acRef + "] component=" + componentId + " builder_version=" + v + " ✅ 分支①有输入"
                + "（当次全库 builder_version 非空行数="
                + count("SELECT count(*) FROM component_sql_view WHERE builder_version IS NOT NULL")
                + "，🚫 该数字是移动靶，不作断言）");
    }

    // ═══════════════════════════ 还原 ═══════════════════════════

    /**
     * 还原本套用例写进共享库的一切。
     * <p>🚫 每条 DELETE 都带收敛谓词（自建 id / 自建 customer_no / {@code t260904_} 前缀），
     * 不存在无 WHERE 的删除，不存在 TRUNCATE / DROP。
     * <p>🚨 写在 {@code @AfterEach}（等价 finally）：用例中途崩溃也会执行。
     */
    @AfterEach
    void cleanupTask260904() {
        List<String> errors = new ArrayList<>();
        for (TreeFx f : treeFixtures) {
            try {
                QuarkusTransaction.requiringNew().run(() -> {
                    em.createNativeQuery("DELETE FROM quotation_line_component_data WHERE line_item_id IN "
                                    + "(SELECT id FROM quotation_line_item WHERE quotation_id = :q)")
                            .setParameter("q", f.quotationId).executeUpdate();
                    em.createNativeQuery("DELETE FROM quotation_line_item_snapshot WHERE line_item_id IN "
                                    + "(SELECT id FROM quotation_line_item WHERE quotation_id = :q)")
                            .setParameter("q", f.quotationId).executeUpdate();
                    em.createNativeQuery("DELETE FROM quotation_line_item WHERE quotation_id = :q")
                            .setParameter("q", f.quotationId).executeUpdate();
                    em.createNativeQuery("DELETE FROM quotation WHERE id = :q")
                            .setParameter("q", f.quotationId).executeUpdate();
                    em.createNativeQuery("DELETE FROM template_component_snapshot WHERE template_id = :t")
                            .setParameter("t", f.templateId).executeUpdate();
                    em.createNativeQuery("DELETE FROM template_component WHERE template_id = :t")
                            .setParameter("t", f.templateId).executeUpdate();
                    em.createNativeQuery("DELETE FROM template WHERE id = :t")
                            .setParameter("t", f.templateId).executeUpdate();
                    // 🚫 复用进来的树组件（配置器真实保存的产物）由 createdComponentIds 负责，这里不碰
                    if (!f.treeComponentReused) {
                        em.createNativeQuery("DELETE FROM component_sql_view WHERE component_id = :a")
                                .setParameter("a", f.treeComponentId).executeUpdate();
                        em.createNativeQuery("DELETE FROM component WHERE id = :a")
                                .setParameter("a", f.treeComponentId).executeUpdate();
                    }
                    em.createNativeQuery("DELETE FROM component_sql_view WHERE component_id = :b")
                            .setParameter("b", f.matComponentId).executeUpdate();
                    em.createNativeQuery("DELETE FROM component WHERE id = :b")
                            .setParameter("b", f.matComponentId).executeUpdate();
                    // V6 边：命中面被自建 customer_no 限死
                    em.createNativeQuery("DELETE FROM material_bom_item WHERE customer_no = :c")
                            .setParameter("c", f.customerNo).executeUpdate();
                    em.createNativeQuery("DELETE FROM customer WHERE id = :id")
                            .setParameter("id", f.customerId).executeUpdate();
                });
            } catch (RuntimeException e) {
                errors.add("清理 fixture " + f.quotationId + " 失败: " + e);
            }
        }
        treeFixtures.clear();

        if (!createdComponentIds.isEmpty()) {
            try {
                QuarkusTransaction.requiringNew().run(() -> {
                    for (UUID cid : createdComponentIds) {
                        em.createNativeQuery("DELETE FROM component_sql_view WHERE component_id = :id")
                                .setParameter("id", cid).executeUpdate();
                        em.createNativeQuery("DELETE FROM component WHERE id = :id")
                                .setParameter("id", cid).executeUpdate();
                    }
                });
            } catch (RuntimeException e) {
                errors.add("清理自建组件失败: " + e);
            }
            createdComponentIds.clear();
        }

        if (!errors.isEmpty()) {
            System.out.println("[task260904·cleanup] ⚠️ " + errors);
        }
        assertResidueFree();
    }

    /** 清完立刻自检：脏库必须以「残留」的名义硬失败，🚫 不许伪装成下一轮的业务缺陷。 */
    protected void assertResidueFree() {
        long comps = count("SELECT count(*) FROM component WHERE code LIKE '" + PREFIX + "%" + RUN_ID + "%'");
        assertEquals(0L, comps, "还原自检：本轮自建组件仍有 " + comps + " 条残留（code LIKE '" + PREFIX + "%" + RUN_ID + "%'）");
    }

    /** 只在整个 JVM 结束时才需要删的自造主数据（外购件补位料号）。由使用它的测试类显式调用。 */
    protected void dropSynthesizedOutsourcedIfAny() {
        if (SYNTHESIZED_OUTSOURCED == null) return;
        String mn = SYNTHESIZED_OUTSOURCED;
        QuarkusTransaction.requiringNew().run(() -> em.createNativeQuery(
                        "DELETE FROM ds_quote_material WHERE material_no = :mn").setParameter("mn", mn).executeUpdate());
        SYNTHESIZED_OUTSOURCED = null;
        CACHED_MASTER = null;
    }
}
