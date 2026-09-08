package com.cpq.dataset.quotation;

import jakarta.inject.Qualifier;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * CDI 限定符：专供 {@code QuotationImportResource#startImport} 派发
 * {@link QuotationImportService#processImport} 使用的 {@code ManagedExecutor}
 * （task-260907 · B-1，{@code cleared(ThreadContext.CDI)}）。
 *
 * <h3>为什么不能用全局默认 {@code ManagedExecutor}（本任务开发期实测复现）</h3>
 * 全局默认 executor 传播 {@code ThreadContext.ALL_REMAINING}（含 CDI），fire-and-forget 下会把
 * <b>即将销毁的</b> HTTP request context 一并传播进后台线程；后台方法上的
 * {@code @ActivateRequestContext} 因此<b>误判「已激活」而不新建</b>，下游拿不到可用的
 * {@code EntityManager}。
 *
 * <p><b>实测原始输出</b>（2026-09-07，worktree 临时端口 8098，真实 16 sheet 报价 Excel）：
 * <pre>
 * "status": "FAILED",
 * "message": "导入失败：Cannot use the EntityManager/Session because neither a transaction
 *             nor a CDI request context is active. …"
 * </pre>
 * 换成本限定符标记的 cleared executor 后同一份文件导入成功。
 *
 * <h3>🚨 为什么 V6 侧同款写法没炸，本链路炸了 —— 别照抄 V6 的结论</h3>
 * {@code QuoteImportService#processImport} 的每一次 DB 访问都发生在
 * {@code @Transactional(REQUIRES_NEW)} 方法内部，<b>「有活跃事务」这一条独立地救了它</b>
 * （报错信息里的判据是「事务 <b>或</b> request context 二者皆无」）。
 * 而本链路的 <b>Phase 1 是刻意事务外的</b>（{@code DatasetImportService#parseAndValidate}
 * 上没有 {@code @Transactional} —— 那是 AC-4「16 张表 count(*) 逐表相等」能成立的结构保证），
 * 校验期的主数据 {@code SELECT} 因此既没有事务也没有 request context，第一条 SQL 就炸。
 * ⇒ <b>「V6 那样写没问题」不能推出「这里那样写也没问题」。</b>
 *
 * <h3>🔒 隔离纪律</h3>
 * 本限定符只用于本任务导入链路这一次派发；🚫 不替换全局默认 {@code ManagedExecutor}
 * （它还服务 {@code BasicDataImportV6Resource} 的 Step 1 与 {@code priceadjust} 的 6 处注入点），
 * 也 🚫 不复用 {@code @MaterializeExecutor}（那个限定符的 javadoc 明确写着「只用于
 * {@code materializer.materialize(bg)} 这一次派发」，两条链路各自持有自己的池，
 * 才不会因为一边的排队拖慢另一边）。
 */
@Qualifier
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.TYPE})
public @interface QuotationImportExecutor {
}
