package com.cpq.datasource.sqlview;

import java.util.UUID;

/**
 * 当前线程「正在渲染哪个模板」的可见域（task-0806 B17-a）。
 *
 * <p><b>背景</b>：{@link com.cpq.component.service.ComponentDriverService} 的两处
 * {@code SqlViewRuntimeContext.setNested(componentId, templateId, quotationId, quotationStatus)}
 * 调用点（{@code expand()} 私有实现 / {@code expandMulti()}）长期把第二参 {@code templateId}
 * 硬编码传 {@code null}。这导致 {@link com.cpq.component.service.ComponentSqlViewService
 * #lookupForResolver} 方案 §5.3 三层 fallback 的「② 模板已发布 snapshot 优先」
 * （{@code ctx.templateId != null} 才会尝试读 {@code template.sql_views_snapshot}）与
 * 「③ 已发布模板未命中快照即报错」（同样要求 {@code ctx.templateId != null}）**永远不会触发**，
 * 全部静默落到「④ 兜底实时读 {@code component_sql_view} 活表」—— 与 {@code template_component_snapshot}
 * 冻结的其它 17 个字段相比，SQL 视图定义这一路径从未真正冻住。
 *
 * <p><b>本类的职责</b>：由「知道 templateId 的渲染编排入口」（{@code CardSnapshotService}
 * 的 {@code expandFlatDriverBaseRows} / {@code precomputeCostingDriverUnion}、
 * {@code ConfigureSnapshotService#snapshotLines}、{@code BomTreeRenderService#render}、
 * {@code CostingVersionService#switchVersion} 等）在触发 driver 展开前 {@link #open}，
 * {@code ComponentDriverService} 在 expand 入口读 {@link #currentTemplateId()} 填入
 * {@code SqlViewRuntimeContext.setNested} 的第二参，finally 中 {@link #restore}。
 *
 * <p><b>与 {@link QuotePendingScope} 的关键差异</b>：本类<b>不做冻结态门槛判定</b> ——
 * DRAFT / PUBLISHED / ARCHIVED 模板的 templateId 一律原样透传，「模板是否已冻结」的判定完全
 * 下放给 {@code ComponentSqlViewService.lookupForResolver} 自己做：DRAFT 模板即使
 * templateId 非空，{@code lookupFromTemplateSnapshot} 天然查不到 {@code sql_views_snapshot}
 * （草稿期不写），随后 {@code isTemplateFrozen()=false} 落到④活表读 —— 与「未设置 templateId」
 * 时的落地行为完全一致，本类无需重复判断一次，保持简单。
 *
 * <p><b>调用约定</b>：{@link #open} 必须与 {@link #restore} 成对出现在 try/finally 中，且
 * finally 必须无条件调用（含异常路径），否则 ThreadLocal 会在线程池复用场景泄漏到下一个请求。
 * 支持嵌套（{@link #open} 返回旧值供 {@link #restore} 还原）。
 */
public final class TemplateRenderScope {

    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();

    private TemplateRenderScope() {}

    /**
     * 打开（或维持关闭）当前线程的模板渲染域。
     *
     * @param templateId 当前渲染编排入口已知的模板 ID（QUOTATION 侧用
     *                   {@code quotation.customer_template_id}，COSTING 侧用
     *                   {@code quotation.costing_card_template_id}）；{@code null} 等同不打开
     * @return 调用前的旧值（供 {@link #restore} 还原，支持嵌套 open）
     */
    public static UUID open(UUID templateId) {
        UUID prev = CURRENT.get();
        if (templateId == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(templateId);
        }
        return prev;
    }

    /** 恢复 {@link #open} 返回的旧值。调用方 finally 必须调用（含异常路径）。 */
    public static void restore(UUID prev) {
        if (prev == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(prev);
        }
    }

    /** 当前线程已知的 templateId；未 {@link #open} 过（或已 {@link #restore} 关闭）时为 {@code null}。 */
    public static UUID currentTemplateId() {
        return CURRENT.get();
    }
}
