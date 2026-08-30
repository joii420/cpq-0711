package com.cpq.basicdata.v6.service;

import jakarta.inject.Qualifier;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * CDI 限定符：标记专供 {@link CreateQuotationMaterializer#materialize} 的 fire-and-forget
 * 派发使用的 {@code ManagedExecutor}（repair-260829 B-2，方案丙）。
 *
 * <p><b>根因</b>：全局默认 {@code ManagedExecutor}（MicroProfile Context Propagation 默认传播
 * {@code ThreadContext.ALL_REMAINING}，含 CDI）在 fire-and-forget 场景下会把发起请求的 CDI
 * request context 一并"传播"到后台线程；若原 HTTP 请求随即返回、该 context 被销毁，后台任务的
 * {@code @ActivateRequestContext} 会误判"传播进来的 context 已激活"而**不新建**——下游
 * {@code @Transactional(SUPPORTS)} 方法（不主动开事务，只借用当前上下文的 EntityManager）因此
 * 拿不到可用会话，抛 {@code Cannot use the EntityManager/Session because neither a transaction
 * nor a CDI request context is active}，被两层既有静默 catch 吞掉 → ① 步写 0 行 → 卡片全空。
 *
 * <p><b>实测依据</b>：主线在共享 8081 真实并发环境用同款 fire-and-forget 背靠背对照
 * （各 4 轮 × 10 发）：本 executor（{@code cleared(ThreadContext.CDI)}）40/40 成功；
 * 默认 executor 仅 3/40（7.5%）。原始数据见
 * `dev-docs/task-260825-大单量导入建单性能/repair-260829-异步物化事务上下文缺失/证据/E6-方案丙决定性验证.txt`。
 *
 * <p>🔒 <b>隔离纪律</b>（backtask.md B-3）：本限定符只用于
 * {@code BasicDataImportV6Resource} 内 {@code materializer.materialize(bg)} 这一次派发。
 * 不替换全局默认 {@code ManagedExecutor} bean —— 该 bean 同时服务 Step 1 导入
 * （{@code BasicDataImportV6Resource:87}）与 {@code priceadjust} 模块的 6 处注入点，全部原样不动。
 */
@Qualifier
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.TYPE})
public @interface MaterializeExecutor {
}
