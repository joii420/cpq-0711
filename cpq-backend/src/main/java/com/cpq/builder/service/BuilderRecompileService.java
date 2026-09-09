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
import com.cpq.template.service.TemplateService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

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

    @Inject SemanticGraphLoader loader;
    @Inject SemanticCompiler compiler;
    @Inject ComponentSqlViewRepository sqlViewRepository;
    @Inject ComponentSqlViewService componentSqlViewService;
    @Inject TemplateService templateService;

    /** 一次重编译的账：扫了几个、其中几个 sql_template 真的会变、变的都是谁。 */
    public static final class RecompileOutcome {
        public int views;
        public int changed;
        public final List<String> changedViewNames = new ArrayList<>();
    }

    /**
     * 预览：只编译、只比对，<b>一个字都不写</b>（AC-14）。
     *
     * <p>🚫 不加 {@code @Transactional}：本方法不写库，加了反而会把「预览」和「执行」两条路径的
     * 事务语义搞成一样，将来有人在这里加一行写操作就不会被 review 注意到。
     */
    public RecompileOutcome previewRecompile() {
        return runRecompile(false);
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

        // 🚨 flush 不是保险，是必需：forceRealignSnapshots 用**原生 SQL** 从 component_sql_view
        //    读取 sql_template 再 INSERT ... SELECT 进快照。上面的写入还在持久化上下文里没落地时，
        //    原生查询读到的就是**旧文本** ⇒ 视图新了、快照还是旧的，且全程不报错。
        sqlViewRepository.flush();

        Map<String, Object> realign = templateService.forceRealignSnapshotsWithAudit(templateIds, operatorId);

        Map<String, Object> out = new LinkedHashMap<>(realign);
        out.put("recompileViews", outcome.views);
        out.put("recompileChanged", outcome.changed);
        out.put("recompiledViewNames", outcome.changedViewNames);
        LOG.warnf("[admin-backdoor] recompile+realign 已执行：重编译 %d 个视图（其中 %d 个 sql_template 变化）",
                outcome.views, outcome.changed);
        return out;
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

            boolean textChanged = !Objects.equals(r.sql, v.sqlTemplate);
            if (textChanged) {
                outcome.changed++;
                outcome.changedViewNames.add(v.sqlViewName);
            }
            if (!write) continue;

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
