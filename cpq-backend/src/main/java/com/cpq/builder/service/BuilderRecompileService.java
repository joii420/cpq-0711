package com.cpq.builder.service;

import com.cpq.builder.compiler.BuilderConfig;
import com.cpq.builder.compiler.CompileDialect;
import com.cpq.builder.compiler.CompileResult;
import com.cpq.builder.compiler.SemanticCompiler;
import com.cpq.builder.exception.BuilderApiException;
import com.cpq.component.dto.CreateComponentSqlViewRequest;
import com.cpq.component.entity.ComponentSqlView;
import com.cpq.component.repository.ComponentSqlViewRepository;
import com.cpq.component.service.ComponentSqlViewService;
import com.cpq.semanticgraph.service.SemanticGraphLoader;
import com.cpq.semanticgraph.service.SemanticGraphSnapshot;
import com.cpq.template.entity.Template;
import com.cpq.template.entity.TemplateComponent;
import com.cpq.template.service.TemplateService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 存量视图重编译（repair-260908 B-6，AC-14 / AC-15）。
 *
 * <p><b>为什么需要它</b>：编译器改了（B-1 / B-1b 补客户谓词）只对**将来保存的**视图生效；
 * 28 个 {@code builder_*} 视图的 {@code sql_template} 是当时编译出来落库的**快照文本**，
 * 不重新生成就永远是旧口径。而下游还有第二层冻结：5 个 {@code PUBLISHED} 模板持有
 * {@code sql_views_snapshot} 副本，实时视图改了它们也不动。⇒ 两层都要推。
 *
 * <p>🚫 <b>三条明确不做</b>（backtask.md §3 硬约束，都是踩过的坑）：
 * <ol>
 *   <li>不手工 {@code UPDATE component_sql_view.sql_template} —— 手改会被下一次配置器 save 覆盖，
 *       而且改的人无法保证与编译器产物逐字一致；</li>
 *   <li>不 {@code new-draft + publish} 升版 —— 升版产生新 {@code template.id}，存量
 *       {@code quotation_line_item.template_id} 仍指旧版，救不了存量单（实证：86 个明细行全部
 *       指向 {@code a2228dae}）。只能**原地**改写冻结快照；</li>
 *   <li>不跳过编译失败的视图 —— 静默跳过会留下「一部分新口径、一部分旧口径」的混合态，
 *       比整体失败更难查（api.md §2 错误码 500）。</li>
 * </ol>
 *
 * <p>🚨 <b>执行属 {@code CLAUDE.md §3.2}「契约销毁」边缘</b>（改写已应用到共享库的 28 个视图 +
 * 5 个模板的冻结快照）：所以 {@code confirm=false} 预览路径**零写入**，先给出
 * {@code recompileChanged} 这个影响面数字，由用户批准后才允许 {@code confirm=true}。
 *
 * <p><b>N+1 说明（不是免责，是把数字摊开）</b>：本类自身在循环体里<b>没有任何 repository 调用</b>，
 * 图快照（{@link SemanticGraphLoader#get()}）与视图清单各查一次，均在循环外。循环体里剩下的
 * 每视图查询有两处，都属于「一个视图 = 一个工作单元」而不是「一行数据一次查询」：
 * ① {@link SemanticCompiler#compile} 内部的 {@code information_schema.columns}（每次 compile 恒 1 条，
 * 与已选列数/图规模无关）；② 写入路径 {@link ComponentSqlViewService#update} 的保存期 dry-run
 * （{@code LIMIT 0} 探针，就是用户在配置器里点保存时跑的那一条）。预览路径**只有 ①**。
 * ⇒ 总条数 = O(视图数)，视图数是本操作的输入规模本身（28），不随任何一张业务表的数据量增长。
 * 这条已在交付回报里向主线点名，若判定需要按 {@code backend.md §1} 走例外申请，由主线裁决。
 */
@ApplicationScoped
public class BuilderRecompileService {

    private static final Logger LOG = Logger.getLogger(BuilderRecompileService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject EntityManager em;
    @Inject SemanticGraphLoader loader;
    @Inject SemanticCompiler compiler;
    @Inject ComponentSqlViewRepository sqlViewRepository;
    @Inject ComponentSqlViewService componentSqlViewService;
    @Inject TemplateService templateService;

    /** 一次重编译的账：扫了几个、其中几个 sql_template 真的会变、变的都是谁 + 第二层快照的账。 */
    public static final class RecompileOutcome {
        // ── 第一层：实时 component_sql_view
        public int views;
        public int changed;
        public final List<String> changedViewNames = new ArrayList<>();
        // ── 第二层：template.sql_views_snapshot（🚨 2026-09-08 返工新增，见 realignSqlViewsSnapshots）
        /** 目标模板数。 */
        public int snapshotTemplates;
        /** 第二层实际写入（执行）/ 当前持有（预览）的快照条目总数。 */
        public int snapshotEntriesRewritten;
        /** 预览专用：与「重编译后应有文本」不一致的快照条目数 —— 这就是第二层要修的东西有多少。 */
        public int snapshotEntriesStale;
        /** 执行专用：**写完之后**自证仍不一致的条目数。🚨 必须为 0，非 0 直接抛异常整体回滚。 */
        public int snapshotMismatchAfterWrite;
        /** 不一致条目的明细（模板 id + 条目 key），最多记 20 条，够定位即可。 */
        public final List<String> snapshotMismatchSamples = new ArrayList<>();
        /**
         * 目标模板里 {@code template_sql_views_snapshot}（<b>另一列</b>，模板自有 SQL 视图的冻结快照）
         * <b>非空</b>的个数。2026-09-08 实测 5/5 全是 {@code {}} ⇒ 本期无事可做；
         * 但它必须**被数出来、报出来**，否则将来某个模板有了自有视图时，同一个洞会换个列名再来一次。
         */
        public int templateOwnedSnapshotNonEmpty;
        /** repair-260908 B-4/B-6：builder_config 里 {@code axisScope} 被新写/改写的视图数。 */
        public int axisScopeWritten;
        /** {@code componentId::sqlViewName} → 本次编译产出的轴范围（预览预测第二层用）。 */
        public final Map<String, String> newAxisScopeByKey = new LinkedHashMap<>();
        /**
         * {@code componentId::sqlViewName} → <b>本次重编译产物</b>。仅预览路径用来预测第二层
         * （🚫 预览不写库 ⇒ 实时表这时还是旧文本，只跟实时表比会漏报）。不进响应体。
         */
        public final Map<String, String> newSqlByKey = new LinkedHashMap<>();
    }

    /**
     * 预览：只编译、只比对，<b>一个字都不写</b>（AC-14）。
     *
     * <p>🚫 不加 {@code @Transactional}：本方法不写库，加了反而会把「预览」和「执行」两条路径的
     * 事务语义搞成一样，将来有人在这里加一行写操作就不会被 review 注意到。
     */
    public RecompileOutcome previewRecompile(List<UUID> templateIds) {
        RecompileOutcome outcome = runRecompile(false);
        // 🔑 2026-09-08 返工新增：预览必须把**第二层**也算出来。
        //    上一版预览只报 recompileChanged（第一层），第二层完全不可见 ——
        //    于是「执行完第二层根本没动」这个故障，预览阶段一点征兆都看不到。
        predictSnapshotStaleness(templateIds, outcome.newSqlByKey, outcome);
        return outcome;
    }

    /**
     * 执行：重编译写回 {@code sql_template} + {@code builder_version}，<b>再</b>把实时视图推进
     * 已发布模板的冻结快照，并写 {@code operation_log} 审计。
     *
     * <p>🔑 <b>三个动作必须同生共死</b>（api.md §2「两个动作的事务边界」）：只重编译不对齐快照，
     * 会留下「实时视图是新口径、在用模板还是旧口径」的中间态；对齐了却没写审计，则是无痕改写
     * 已发布模板。故三者写在**同一个** {@code @Transactional} 方法里。
     *
     * <p>⚠️ 本方法**必须**由外部 bean（{@code ConfigCenterResource}）经注入代理调用 ——
     * Resource 类内部自调用会因 CDI self-invocation 静默跳过拦截器，三个动作各自落进独立事务
     * （既有实现踩过一次，见 {@code TemplateService#forceRealignSnapshotsWithAudit} 注释）。
     */
    @Transactional
    public Map<String, Object> recompileAndRealign(List<UUID> templateIds, UUID operatorId) {
        RecompileOutcome outcome = runRecompile(true);

        // 🚨 flush 不是保险，是必需：下面两步都用**原生 SQL / 另一条服务链**从 component_sql_view
        //    读取 sql_template。上面的写入还在持久化上下文里没落地时，它们读到的就是**旧文本**
        //    ⇒ 视图新了、快照还是旧的，且全程不报错。
        sqlViewRepository.flush();

        // ── 第一层旁支：template_component_snapshot（**组件配置**快照）
        // 🚨 2026-09-08 返工纪要：这一步**不是**「把实时视图推进冻结快照」。
        //    forceRealignSnapshots 的实现是 DELETE + INSERT template_component_snapshot，
        //    **一个字都不碰 template.sql_views_snapshot**。名字里都叫「快照」，推的却是两张表。
        //    上一版把它当成第二层，结果是：实时视图写成功、模板快照原样不动、HTTP 200、
        //    连 template.updated_at 都变了（forceRealign 会动模板行）—— 典型的
        //    「写了、没报错、写的不是那张表」。保留它（组件配置那一层本来也该推），
        //    但它**不能替代**下面的 realignSqlViewsSnapshots。
        Map<String, Object> realign = templateService.forceRealignSnapshotsWithAudit(templateIds, operatorId);

        // ── 第二层：template.sql_views_snapshot（**SQL 视图**冻结快照）—— 缺陷①要救的存量单读的就是它
        realignSqlViewsSnapshots(templateIds, outcome);
        em.flush();

        // ── 🚨 执行后自证：判据落在**内容**上，不是 updated_at、不是 refreshedTemplates
        //    （这次事故里那两个都是"对"的，内容却是旧的）。不一致 ⇒ 抛异常整体回滚，不许返 200。
        assertSnapshotsMatchLive(templateIds, outcome);

        Map<String, Object> out = new LinkedHashMap<>(realign);
        out.put("recompileViews", outcome.views);
        out.put("recompileChanged", outcome.changed);
        out.put("recompiledViewNames", outcome.changedViewNames);
        out.put("snapshotTemplates", outcome.snapshotTemplates);
        out.put("snapshotEntriesRewritten", outcome.snapshotEntriesRewritten);
        out.put("snapshotMismatchAfterWrite", outcome.snapshotMismatchAfterWrite);
        out.put("templateOwnedSnapshotNonEmpty", outcome.templateOwnedSnapshotNonEmpty);
        out.put("axisScopeWritten", outcome.axisScopeWritten);
        LOG.warnf("[admin-backdoor] recompile+realign 已执行：重编译 %d 个视图（%d 个 sql_template 变化）；"
                        + "第二层重写 %d 个模板的 %d 条 sql_views_snapshot 条目，写后自证不一致 %d 条",
                outcome.views, outcome.changed, outcome.snapshotTemplates,
                outcome.snapshotEntriesRewritten, outcome.snapshotMismatchAfterWrite);
        return out;
    }

    /**
     * 第二层：把实时 {@code component_sql_view} 重新冻结进 {@code template.sql_views_snapshot}。
     *
     * <p><b>为什么必须单独做</b>：报价渲染读 SQL 视图的优先级是
     * 「报价单 snapshot &gt; <b>模板 snapshot</b> &gt; 实时 component_sql_view」
     * （{@code Template#sqlViewsSnapshot} 注释）—— 已发布模板恒命中第二档，
     * 实时视图改成什么样它都看不见。⇒ 不推这一层，存量单永远是旧口径，B-6 等于没做。
     *
     * <p><b>怎么推</b>：与 {@code TemplateService#publish()}「阶段 2」**同一条构建路径**
     * （逐模板取其 {@code template_component} 的 componentIds → {@code snapshotForComponents}
     * → 写回 {@code sqlViewsSnapshot}）。🚫 不另写一套序列化：另写一套就会出现
     * 「发布出来的快照」与「重对齐出来的快照」两种形态，而它们的差异要到渲染期才暴露。
     *
     * <p>⚠️ <b>这是整份重建，不是逐条打补丁</b>：模板发布后若组件的视图有增删，
     * 重建结果会跟着变（多/少条目），不只是 sql_template 变。这本就是本端点
     * 「明确破坏不可变性」的语义，但报告时要说清，别让人以为只动了谓词。
     *
     * <p><b>N+1 说明</b>：循环体是「一个模板 = 一个工作单元」，每单元 1 条 template_component 查询
     * + {@code snapshotForComponents}（其内部按 componentId 逐个 {@code listByComponent}，是**既有实现**，
     * publish() 走的也是它）。⇒ 条数为 O(模板数 × 该模板组件数)，与任何业务表数据量无关。
     * 🚫 不在这里另写一版批量的 snapshotForComponents —— 那就回到「两种形态」的老问题上了。
     */
    private void realignSqlViewsSnapshots(List<UUID> templateIds, RecompileOutcome outcome) {
        if (templateIds == null || templateIds.isEmpty()) return;
        for (UUID tid : templateIds) {
            Template t = Template.findById(tid);
            if (t == null) continue;
            List<UUID> componentIds = TemplateComponent.<TemplateComponent>list("templateId", tid)
                    .stream().map(tc -> tc.componentId).distinct().toList();
            Map<String, Map<String, Object>> snap =
                    componentSqlViewService.snapshotForComponents(componentIds);
            try {
                t.sqlViewsSnapshot = MAPPER.writeValueAsString(snap);
            } catch (Exception e) {
                // 🚫 不吞：序列化失败却继续，会留下「一部分模板新口径、一部分旧口径」的混合态
                throw new BuilderApiException(500, "SNAPSHOT_SERIALIZE_FAILED",
                        "模板「" + t.name + "」(" + tid + ") 的 sql_views_snapshot 序列化失败，已整体中止: "
                                + e.getMessage(), Map.of("templateId", String.valueOf(tid)));
            }
            t.persist();
            outcome.snapshotTemplates++;
            outcome.snapshotEntriesRewritten += snap.size();
            // 🚨 另一列的存在感（见 RecompileOutcome#templateOwnedSnapshotNonEmpty）：
            //    template_sql_views_snapshot 冻结的是**模板自有** SQL 视图（template_sql_view，手写），
            //    不由本次重编译产生，故本方法不动它。但非空就必须报出来 ——
            //    「有另一层没人推」这件事只要不可见，就会以另一个列名重演一次。
            if (t.templateSqlViewsSnapshot != null
                    && !t.templateSqlViewsSnapshot.isBlank()
                    && !"{}".equals(t.templateSqlViewsSnapshot.trim())) {
                outcome.templateOwnedSnapshotNonEmpty++;
                LOG.warnf("[admin-backdoor] 模板 %s 的 template_sql_views_snapshot 非空 —— "
                        + "本端点不推该列（它冻结的是模板自有手写视图）。若它也需要对齐，须另立任务。", tid);
            }
        }
    }

    /**
     * 🚨 写后自证：逐条比对「模板快照条目的 {@code sql_template}」与「实时 ACTIVE 视图的
     * {@code sql_template}」的 <b>md5</b>，不一致即抛异常（{@code @Transactional} 整体回滚）。
     *
     * <p><b>判据为什么必须落在内容上</b>（2026-09-08 事故的直接教训）：上一版返 200 时
     * {@code refreshedTemplates=5} 是对的、{@code template.updated_at} 也确实变了，
     * <b>但快照内容是旧的</b>。⇒ 任何以「调用成功 / 行数对 / 时间戳变了」为判据的自检，
     * 对这个故障形态全部无效。只有把两边的文本摘要拿来比，才可能失败。
     *
     * <p>🚫 <b>{@code template.updated_at} 恒为真，永远不许当成功判据</b>：
     * {@code TemplateService#forceRealignSnapshots} 自己那条
     * {@code UPDATE template SET components_snapshot = sub.snap, updated_at = now()} 就会把它刷新
     * —— 它推的是 {@code template_component_snapshot} / {@code components_snapshot}，
     * 与本层 {@code sql_views_snapshot} 写没写对**毫无关系**。
     *
     * <p>🚫 <b>刻意不做文本/正则断言</b>（如「快照里含 {@code customer_no = :customerCode}」）：
     * 那是**假阳性**。客户维度有三态 —— ① 没有（要修的）② 走 {@code :customerCode} 参数
     * ③ 走列对列（安全，见 BL-0229）—— 而 {@code LEFT JOIN ds_quote_customer_part dqcp
     * ON … AND dqcp.customer_no = :customerCode} 属形态②、**本来就该在那儿**，
     * 文本含判会把「主表 WHERE 根本没客户谓词」的页签判成「已修好」而跳过检查
     * （2026-09-08 主线实测：取值测试模板2 的产品页签正是重复行最多的那个，却会被判成已修）。
     * 同日已有两条正则骗过人（并发线的 {@code FROM\s+(ds_quote_\w+)} 匹到 NARROW 桥子查询；
     * 主线的 {@code like '%customer_no = :customerCode%'} 匹到 LEFT JOIN）。
     * <b>md5 相等不需要理解 SQL 语义，因而骗不过去</b>：它只问「写进去的是不是就是刚编译出来的那份」，
     * 而这正是第二层唯一该保证的事。
     *
     * <p><b>为什么从 DB 读回而不是比对内存里的 map</b>：比内存 map 等于自己跟自己比，
     * 恒真。必须 {@code flush()} 之后走原生 SQL 读**落库后的** jsonb。
     *
     * <p>条目在实时表里找不到对应 ACTIVE 视图（{@code v.id IS NULL}）同样算不一致 ——
     * 那说明快照引用了一个已被停用/删除的视图，属于同一类「快照与实时不同源」的问题。
     */
    @SuppressWarnings("unchecked")
    private void assertSnapshotsMatchLive(List<UUID> templateIds, RecompileOutcome outcome) {
        if (templateIds == null || templateIds.isEmpty()) return;
        List<Object[]> bad = em.createNativeQuery(
                "SELECT t.id::text, e.key, md5(e.value->>'sql_template'), md5(v.sql_template), "
                        + "       COALESCE(e.value->>'axis_scope','<缺键>'), "
                        + "       COALESCE(v.builder_config->>'axisScope','<缺键>') "
                        + "FROM template t "
                        + "CROSS JOIN LATERAL jsonb_each(t.sql_views_snapshot) e "
                        + "LEFT JOIN component_sql_view v "
                        + "  ON v.component_id = split_part(e.key,'::',1)::uuid "
                        + " AND v.sql_view_name = split_part(e.key,'::',2) "
                        + " AND v.status = 'ACTIVE' "
                        + "WHERE t.id = ANY(:tids) "
                        + "  AND (v.id IS NULL "
                        + "   OR md5(e.value->>'sql_template') IS DISTINCT FROM md5(v.sql_template) "
                        // repair-260908 B-4（AC-8）：轴范围也必须逐条对得上。
                        // 🚫 不加这一维就会重演 2026-09-08 的同型事故：第一层（builder_config）写对了、
                        //    第二层（快照 axis_scope）没跟上，而只比 sql_template 的自证照样全绿。
                        //    两侧都按「缺键 = CLOSURE」归一，否则「都没有这个键」会被判成不一致。
                        + "   OR COALESCE(e.value->>'axis_scope','CLOSURE') "
                        + "      IS DISTINCT FROM COALESCE(v.builder_config->>'axisScope','CLOSURE'))")
                .setParameter("tids", templateIds.toArray(new UUID[0]))
                .getResultList();
        outcome.snapshotMismatchAfterWrite = bad.size();
        for (Object[] r : bad) {
            if (outcome.snapshotMismatchSamples.size() >= 20) break;
            outcome.snapshotMismatchSamples.add(r[0] + " :: " + r[1]
                    + "（快照 md5=" + r[2] + " / 实时 md5=" + r[3]
                    + "；快照 axis_scope=" + r[4] + " / 实时 axisScope=" + r[5] + "）");
        }
        if (!bad.isEmpty()) {
            throw new BuilderApiException(500, "SNAPSHOT_REALIGN_VERIFY_FAILED",
                    "第二层写后自证失败：" + bad.size() + " 条 template.sql_views_snapshot 条目的 sql_template "
                            + "与实时 component_sql_view 不一致 —— 已整体回滚，🚫 不返 200。"
                            + "（本自证专为 2026-09-08 那种「写了、没报错、写的不是那张表」的形态而设："
                            + "updated_at 与 refreshedTemplates 当时都是对的。）明细="
                            + outcome.snapshotMismatchSamples,
                    Map.of("mismatchCount", bad.size(), "samples", outcome.snapshotMismatchSamples));
        }
    }

    /**
     * 预览专用：预测第二层会改多少条 —— 🔑 <b>没有它，第二层对预览完全不可见</b>，
     * 而这正是 2026-09-08 那次「预览看着挺好、执行完第二层根本没动」能瞒过去的原因。
     *
     * <p>比对口径：快照条目现存的 {@code sql_template} vs <b>重编译后应有的文本</b>
     * （该视图是配置器托管的 ⇒ 用本次编译产物；否则 ⇒ 用实时表现值）。
     * 🚫 不能只跟实时表比：预览路径不写库，实时表这时候还是旧文本，那样比会漏报。
     */
    @SuppressWarnings("unchecked")
    private void predictSnapshotStaleness(List<UUID> templateIds, Map<String, String> newSqlByKey,
                                          RecompileOutcome outcome) {
        if (templateIds == null || templateIds.isEmpty()) return;
        List<Object[]> rows = em.createNativeQuery(
                "SELECT t.id::text, e.key, md5(e.value->>'sql_template'), md5(v.sql_template), "
                        + "       COALESCE(e.value->>'axis_scope','CLOSURE'), "
                        + "       COALESCE(v.builder_config->>'axisScope','CLOSURE') "
                        + "FROM template t "
                        + "CROSS JOIN LATERAL jsonb_each(t.sql_views_snapshot) e "
                        + "LEFT JOIN component_sql_view v "
                        + "  ON v.component_id = split_part(e.key,'::',1)::uuid "
                        + " AND v.sql_view_name = split_part(e.key,'::',2) "
                        + " AND v.status = 'ACTIVE' "
                        + "WHERE t.id = ANY(:tids)")
                .setParameter("tids", templateIds.toArray(new UUID[0]))
                .getResultList();
        outcome.snapshotTemplates = templateIds.size();
        outcome.snapshotEntriesRewritten = rows.size();
        for (Object[] r : rows) {
            String key = String.valueOf(r[1]);
            String snapMd5 = r[2] == null ? null : String.valueOf(r[2]);
            String liveMd5 = r[3] == null ? null : String.valueOf(r[3]);
            String expected = newSqlByKey.get(key);
            String expectedMd5 = expected != null ? md5(expected) : liveMd5;
            // 轴范围同理：配置器托管的用本次编译产物，其余用实时值；两侧缺键都已在 SQL 里归一成 CLOSURE
            String snapScope = String.valueOf(r[4]);
            String expectedScope = outcome.newAxisScopeByKey.getOrDefault(key, String.valueOf(r[5]));
            boolean sqlStale = (expectedMd5 == null || !expectedMd5.equals(snapMd5));
            boolean scopeStale = !expectedScope.equals(snapScope);
            if (sqlStale || scopeStale) {
                outcome.snapshotEntriesStale++;
                if (outcome.snapshotMismatchSamples.size() < 20) {
                    outcome.snapshotMismatchSamples.add(r[0] + " :: " + key
                            + "（" + (sqlStale ? "sql md5 " + snapMd5 + "→" + expectedMd5 + " " : "")
                            + (scopeStale ? "axis_scope " + snapScope + "→" + expectedScope : "") + "）");
                }
            }
        }
    }

    /** {@code builder_config} 里到底**有没有** {@code axisScope} 这个键（与它的值是什么无关）。 */
    private boolean hasAxisScopeKey(ComponentSqlView v) {
        if (v.builderConfig == null || v.builderConfig.isBlank()) return false;
        try {
            com.fasterxml.jackson.databind.JsonNode n = MAPPER.readTree(v.builderConfig);
            return n != null && n.isObject() && n.has("axisScope") && n.get("axisScope").isTextual();
        } catch (Exception e) {
            return false;
        }
    }

    /** 读实时视图 {@code builder_config->>'axisScope'}；缺键/解析失败一律 {@code CLOSURE}（AC-11）。 */
    private String currentAxisScope(ComponentSqlView v) {
        if (v.builderConfig == null || v.builderConfig.isBlank()) return CompileResult.AXIS_SCOPE_CLOSURE;
        try {
            com.fasterxml.jackson.databind.JsonNode n = MAPPER.readTree(v.builderConfig).get("axisScope");
            if (n != null && n.isTextual() && !n.asText().isBlank()) return n.asText();
        } catch (Exception ignored) { /* 落 CLOSURE */ }
        return CompileResult.AXIS_SCOPE_CLOSURE;
    }

    /**
     * 把轴范围声明并进既有 {@code builder_config} jsonb（**加键，不重建**）。
     *
     * <p>🚫 不要整份重写成 {@code MAPPER.valueToTree(cfg)} —— 那会把 {@code builder_config} 里
     * 本轮 {@link BuilderConfig} 类不认识的任何键**悄悄抹掉**（该类带
     * {@code @JsonIgnoreProperties(ignoreUnknown = true)}，读时忽略、写时就丢）。
     * 存量 jsonb 里有什么不是本方法该决定的事。
     */
    private void writeAxisScopeIntoBuilderConfig(ComponentSqlView v, String axisScope) {
        try {
            com.fasterxml.jackson.databind.JsonNode root =
                    (v.builderConfig == null || v.builderConfig.isBlank())
                            ? MAPPER.createObjectNode() : MAPPER.readTree(v.builderConfig);
            if (!root.isObject()) {
                throw new BuilderApiException(500, "RECOMPILE_CONFIG_NOT_OBJECT",
                        "视图「" + v.sqlViewName + "」的 builder_config 不是 JSON 对象，无法并入 axisScope",
                        Map.of("sqlViewName", v.sqlViewName));
            }
            ((com.fasterxml.jackson.databind.node.ObjectNode) root).put("axisScope", axisScope);
            v.builderConfig = MAPPER.writeValueAsString(root);
        } catch (BuilderApiException e) {
            throw e;
        } catch (Exception e) {
            throw new BuilderApiException(500, "RECOMPILE_CONFIG_WRITE_FAILED",
                    "视图「" + v.sqlViewName + "」写 axisScope 失败，已整体中止: " + e.getMessage(),
                    Map.of("sqlViewName", v.sqlViewName));
        }
    }

    /** 与 PG 的 {@code md5(text)} 同口径（UTF-8 字节的 MD5，小写十六进制）。 */
    private static String md5(String s) {
        try {
            byte[] d = MessageDigest.getInstance("MD5").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(32);
            for (byte b : d) sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("MD5 不可用", e);
        }
    }

    /**
     * 重编译内核。{@code write=false} 时只算不写（预览），{@code true} 时写回。
     *
     * <p>「变了没有」的判据是 {@code sql_template} <b>逐字比对</b>，不是「编译成功就算变」——
     * 同一份 {@code builder_config} 编译结果逐字确定（{@link SemanticCompiler} 类注释），
     * 所以文本相同就是真的没变，可以放心不写。
     */
    private RecompileOutcome runRecompile(boolean write) {
        SemanticGraphSnapshot snap = loader.get();                      // 循环外，1 次
        List<ComponentSqlView> views = sqlViewRepository.listBuilderManaged();  // 循环外，1 条 SQL
        RecompileOutcome outcome = new RecompileOutcome();
        outcome.views = views.size();

        for (ComponentSqlView v : views) {
            BuilderConfig cfg;
            try {
                cfg = MAPPER.readValue(v.builderConfig, BuilderConfig.class);
            } catch (Exception e) {
                // api.md §2：🚫 不许跳过该视图继续 —— 混合态比整体失败更难查
                throw new BuilderApiException(500, "RECOMPILE_CONFIG_CORRUPT",
                        "视图「" + v.sqlViewName + "」的 builder_config 无法反序列化，重编译已整体中止"
                                + "（不跳过：跳过会留下一部分新口径、一部分旧口径的混合态）: " + e.getMessage(),
                        Map.of("sqlViewName", v.sqlViewName, "componentId", String.valueOf(v.componentId)));
            }

            CompileResult r;
            try {
                // 🚫 skipNarrowPredicates 恒 false：那是 /preview 专用的放宽，落库的 sql_template
                //    必须带桥，否则渲染期就不收窄了（SemanticCompiler#compile 的四参重载注释）。
                r = compiler.compile(snap, cfg, CompileDialect.parse(cfg.dialect), false);
            } catch (BuilderApiException e) {
                throw e;   // 结构化错误原样上抛（含 400 的列/边找不到），同样不跳过
            } catch (Exception e) {
                throw new BuilderApiException(500, "RECOMPILE_FAILED",
                        "视图「" + v.sqlViewName + "」重编译失败，已整体中止: " + e.getMessage(),
                        Map.of("sqlViewName", v.sqlViewName, "componentId", String.valueOf(v.componentId)));
            }

            String viewKey = v.componentId + "::" + v.sqlViewName;
            outcome.newSqlByKey.put(viewKey, r.sql);
            outcome.newAxisScopeByKey.put(viewKey, r.axisScope);

            // repair-260908 B-4/B-6：轴范围声明也要回写进存量的 builder_config ——
            // 🔑 它与 sql_template **各自独立地**可能过期：本次 28 个存量视图的 SQL 文本一个字没变
            //    （客户谓词那轮已经写进去了），但 axisScope 键**一个都没有** ⇒ 只按 sql_template
            //    判「变没变」会一个都不写，AC-7「每个 builder_config 都含 axisScope」永远不达标。
            // 🚨 判据是「**键缺失** 或 值不同」，不是只看值不同：
            //    currentAxisScope 对缺键返回 CLOSURE（AC-11 的兜底），而绝大多数视图的编译产物
            //    正好也是 CLOSURE ⇒ 只比值会得出「没变」，键**一个都不会被写进去**，
            //    而 AC-7 要的是「**每个** builder_config 都含 axisScope 键」。
            //    这类「兜底默认值把『没有』伪装成『相等』」是本任务已经踩过的同型坑。
            boolean axisScopeChanged = !hasAxisScopeKey(v) || !Objects.equals(r.axisScope, currentAxisScope(v));

            boolean textChanged = !Objects.equals(r.sql, v.sqlTemplate);
            if (textChanged) {
                outcome.changed++;
                outcome.changedViewNames.add(v.sqlViewName);
            }
            if (textChanged || axisScopeChanged) {
                if (!write) { if (axisScopeChanged) outcome.axisScopeWritten++; }
            }
            if (!write) continue;

            if (axisScopeChanged) {
                writeAxisScopeIntoBuilderConfig(v, r.axisScope);
                outcome.axisScopeWritten++;
            }
            if (textChanged) {
                // 走与配置器 save 完全同一条写入路径（ComponentSqlViewService.update）：
                //   ① 保存期 dry-run 会在写共享库**之前**验一遍新 SQL 真的能跑（LIMIT 0 探针）；
                //   ② declared_columns / required_variables 由同一次 dry-run 重算 ⇒ 「重编译后的视图」
                //      与「用户重新点一次保存的视图」逐字同态，不产生第二种形态。
                // 🚫 不要图省事直接 v.sqlTemplate = r.sql —— 那会让这三列彼此漂移，而漂移
                //    正好会被 B-6 第二步冻进模板快照，带着走很远才暴露。
                CreateComponentSqlViewRequest req = new CreateComponentSqlViewRequest();
                req.sqlViewName = v.sqlViewName;   // 同名 ⇒ update 内不走改名分支
                req.sqlTemplate = r.sql;
                req.scope = null;                  // null = 不改（update 只在非 null 时覆盖）
                req.description = null;
                componentSqlViewService.update(v.componentId, v.id, req);
            }
            // 版本号无条件对齐：产物没变也说明它已经是当前编译器口径，留着 builder_version < CURRENT
            // 会让 GET /builder 一直报 isStale=true（自检项「无 builder_version < CURRENT_VERSION 残留」）。
            v.builderVersion = SemanticCompiler.CURRENT_VERSION;
        }
        return outcome;
    }
}
