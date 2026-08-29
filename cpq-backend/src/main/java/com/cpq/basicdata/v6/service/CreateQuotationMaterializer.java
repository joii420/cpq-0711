package com.cpq.basicdata.v6.service;

import com.cpq.configure.service.ConfigureSnapshotService;
import com.cpq.quotation.entity.Quotation;
import com.cpq.quotation.entity.QuotationLineItem;
import com.cpq.quotation.service.CardSnapshotService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.UUID;

/**
 * 建单后置物化编排：建行事务提交后，服务端展开写 snapshot_rows → 建 4 份结构 → 整单批量算
 * 卡片值/Excel 值，并回填 CommitResult 的 cardValuesReady/costingTreeRows/warnings。
 *
 * <p>本类<b>不加 @Transactional</b>：每个下游调用（snapshotQuotation / ensureStructure /
 * ensureCardValues / ensureExcelValues）各自管理事务（内部 REQUIRES_NEW / 独立 @Transactional），
 * 必须在建行事务提交后独立调用（照搬 ConfigureProductResource 范例）。
 * 降级纪律（backtask §5 / api.md §3）：物化失败不回滚整单（报价单+明细行已落=不丢单），
 * 置 cardValuesReady=false + warnings；前端进编辑页由既有 warm 兜底。
 *
 * <p><b>task-260825 B-17（D-5，2026-08-26）</b>：本类原本由请求线程同步调用（132s 全做完才
 * 返回），现改由 {@code BasicDataImportV6Resource#createQuotation} 经
 * {@code managedExecutor.runAsync(...)} 在<b>后台线程</b>调用——前端 axios 全局 30s 超时会在
 * 同步方案下 cancel 请求（即便后端最终算完、数据是好的，用户体感仍是"超时失败"）。
 * 前端改为轮询既有的 {@code POST /quotations/{id}/ensure-card-values} 端点等待完成，
 * 见 {@link #materialize} 方法级 javadoc 的 B-17/B-18 小节。
 */
@ApplicationScoped
public class CreateQuotationMaterializer {

    private static final Logger LOG = Logger.getLogger(CreateQuotationMaterializer.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String FAIL_MARK = "__cardValueFailed";

    @Inject ConfigureSnapshotService snapshotService;
    @Inject CardSnapshotService cardSnapshotService;

    /**
     * 对已建行、已提交的报价单做整单物化，并回填 result 状态字段。
     *
     * <p>task-260825 B-10：四步各打一条带耗时的日志埋点 —— 这是 AC-9「下一堵墙在哪」的唯一依据，
     * 也是万一 D-1/D-3 修完 AC-1/AC-2 仍红时的定位手段（问题说明.md §④ 已知风险 3）。
     *
     * <p><b>task-260825 B-15（D-4 返修，方案 E 更正版，2026-08-25 用户裁决）</b>：③ 步
     * {@code ensureCardValues} 分批（D-4/B-11~B-14）后，其自身「锁架子」外层事务要横跨全部批次
     * （1845 行 7 批实测 ≈77.5s），超过 Narayana 默认 60s → 被 reaper 杀 → {@code commit} 抛
     * {@code RollbackException} → 报 {@code cardValuesReady=false}，但批次其实早已各自提交、
     * 数据是好的（比"报失败+数据真丢"更危险的说谎状态位）。
     * <p>放宽超时的动作<b>只包在这一次调用</b>（{@link QuarkusTransaction#run(io.quarkus.narayana.jta.RunOptions, Runnable)}，
     * 默认 {@code REQUIRE_NEW} 语义 —— 本方法本身无活跃事务，等价于开一个全新的、超时 600s 的事务），
     * <b>不加在 {@code CardSnapshotService#ensureCardValues} 自身</b>：那个方法有 6 个生产调用点，
     * 其中 {@code QuotationService#submit} 的两处调用发生在 {@code submit} 自身已开启的事务内部——
     * Quarkus 对「已处于外层事务中 + 方法自带 {@code @TransactionConfiguration}」的组合<b>直接抛
     * RuntimeException</b>（{@code TransactionalInterceptorBase.checkConfiguration}，而非静默不生效），
     * 若加在方法自身会让报价单提交直接报错（已被 {@code SqlCountNPlusOneGuardTest} 实测炸出，
     * 已撤销该做法）。把放宽动作收在本调用点，是把「本次例外只用于建单物化路径」从注释承诺变成
     * 结构上的事实——其余 5 个调用点的代码一行未动，天然不受影响。
     *
     * <p><b>task-260825 B-17/B-18（D-5，2026-08-26 用户真机测试后裁决，第三次扩范围）</b>：
     * 后端把 132s 全修好了，但前端 axios 30s 就把请求 cancel 了——用户体感是"依然超时失败"。
     * 裁决：{@code BasicDataImportV6Resource#createQuotation} 拆两段，同步段只做建单+建行立即
     * 返回，本方法改由 {@code managedExecutor.runAsync(...)} 在后台线程调用。
     * <ul>
     *   <li><b>{@code @ActivateRequestContext}</b>：本方法内部（经 snapshotService/cardSnapshotService）
     *       大量使用 request-scoped 的 {@code EntityManager}，后台线程默认没有激活的 CDI 请求上下文
     *       ——不加这个注解会在第一次 {@code em.xxx} 调用时抛 request-scoped 上下文未激活的异常。
     *       这不是新发明的写法：与既有 {@code QuoteImportService#processImport}（Step 1 导入异步化，
     *       同样经 {@code ManagedExecutor.runAsync} 触发）用的是<b>同一个、已在本仓库验证过的模式</b>。</li>
     *   <li><b>本方法自身的降级逻辑不需要改</b>：顶层 try/catch 早已存在（不上抛，失败写
     *       {@code r.warnings}），异步化后这个 catch 依然生效——只是它写的 {@code r} 现在是调用方
     *       在后台专门另建的一份<b>局部对象</b>（不是已经序列化返回给前端的那份），避免两个线程
     *       并发读写同一个 {@code CommitResult}（见 {@code BasicDataImportV6Resource#createQuotation}
     *       的注释）。</li>
     *   <li><b>B-15 的 {@code QuarkusTransaction.run(...)} 包装原样保留在本方法体内部</b>——本方法
     *       整体搬到后台线程执行，方法体一字未改，那层包装自然跟着搬过去，不会丢。Narayana 的
     *       事务管理器是容器级服务（不绑定某一个 HTTP 请求线程），从后台线程调用
     *       {@code QuarkusTransaction.run(...)} 与从请求线程调用行为一致。</li>
     * </ul>
     */
    @ActivateRequestContext
    public void materialize(V6QuotationCommitService.CommitResult r) {
        if (r == null || r.quotationId == null) return;
        UUID qid = r.quotationId;
        long t0 = System.currentTimeMillis();
        try {
            snapshotService.snapshotQuotation(qid);          // ① 展开 driver → UPSERT 自建 componentData 行 + snapshot_rows
            long t1 = System.currentTimeMillis();
            cardSnapshotService.ensureStructure(qid);        // ② 4 份结构快照（幂等）
            long t2 = System.currentTimeMillis();
            // ③ 整单批量算 quote/costing 卡片值（核价树 render 一次批量，无 N+1；D-4 内部按 chunk
            // 分批、每批 REQUIRES_NEW 独立提交）。B-15：外层"锁架子"事务用 QuarkusTransaction.run
            // 显式放宽超时到 600s，只在本调用点生效，见上方方法 javadoc。
            // task-260825 B-28：🔒 B-15 的 QuarkusTransaction.run(...timeout(600)) 包装本身不改
            // （仍是 .run + 同一份 RunOptions），只是把内部调用换成 ensureCardValuesDetailed，
            // 并借一个方法外的 AtomicReference 把返回的 EnsureResult 带出 lambda——避免为了拿
            // 返回值把 .run 改写成 .call 而触碰"不许动 B-15 包装"这条硬约束的字面表述。
            // ensureCardValuesDetailed 取代 ensureCardValues：换取批失败汇总（failedBatches/
            // failedRows）——某一批因拿不到行锁（新加的 10s lock_timeout，见 CardSnapshotService#
            // snapshotNewLinesCardValuesBatch）而失败时，其余批仍照常完成，本调用不再因为一批
            // 被堵就抛异常终止整个 materialize（见 CardSnapshotService#ensureCardValuesDetailed
            // 循环体内 try/catch）。
            var ensureResultRef =
                new java.util.concurrent.atomic.AtomicReference<CardSnapshotService.EnsureResult>();
            QuarkusTransaction.run(QuarkusTransaction.runOptions().timeout(600),
                () -> ensureResultRef.set(cardSnapshotService.ensureCardValuesDetailed(qid, false)));
            CardSnapshotService.EnsureResult ensureResult = ensureResultRef.get();
            if (ensureResult != null && ensureResult.failedBatches > 0) {
                // 硬约束：不许把失败藏起来——显式写清楚"N 批（共 M 行）未完成"，而不是让
                // fillStatus 下面那句笼统的"部分行卡片值未就绪"独自承担全部信息量。
                // cardValuesReady 仍由 fillStatus 按库里实际 NULL/哨兵情况权威判定（不在此处代判），
                // 失败批次的行本就落不下值、天然会被 fillStatus 判为 not-ready，语义自洽。
                r.warnings.add(String.format(
                    "卡片值物化部分未完成：%d 批（共 %d 行）未完成，将在下次打开/轮询时自动补算",
                    ensureResult.failedBatches, ensureResult.failedRows));
                LOG.warnf("[create-quotation] quotation=%s ③ensureCardValues 部分批次失败：%d 批/%d 行，" +
                        "其余批次已正常提交，未完成行靠 IS NULL 谓词自愈",
                    qid, ensureResult.failedBatches, ensureResult.failedRows);
            }
            long t3 = System.currentTimeMillis();
            // task-260825 B-29-7：④ 改调 Detailed 版，与 ③ 对齐——失败不再是哑的，往 r.warnings
            // 追加一条（照抄 ③ 那条 warnf 的措辞风格，两条读起来是一套）。
            // 🔒 不动 r.cardValuesReady：语义是"卡片值"就绪，Excel 值失败是另一回事，混进去会让
            // 前端误判"卡片值也没就绪"；CommitResult 目前没有 Excel 侧独立就绪标志，新增字段是
            // 跨端契约变更，不在本次范围——只写 warnings，不新增字段。
            CardSnapshotService.EnsureResult excelEnsureResult =
                cardSnapshotService.ensureExcelValuesDetailed(qid);   // ④ 整单批量算 quote/costing Excel 值
            if (excelEnsureResult != null && excelEnsureResult.failedBatches > 0) {
                r.warnings.add(String.format(
                    "Excel 值物化部分未完成：%d 批（共 %d 行）未完成，将在下次打开/导出/提交时自动补算",
                    excelEnsureResult.failedBatches, excelEnsureResult.failedRows));
                LOG.warnf("[create-quotation] quotation=%s ④ensureExcelValues 部分批次失败：%d 批/%d 行，" +
                        "其余批次已正常提交，未完成行靠 IS NULL 谓词自愈",
                    qid, excelEnsureResult.failedBatches, excelEnsureResult.failedRows);
            }
            long t4 = System.currentTimeMillis();
            fillStatus(r);
            LOG.infof("[create-quotation-timing] quotation=%s ①snapshotQuotation=%dms ②ensureStructure=%dms " +
                    "③ensureCardValues=%dms ④ensureExcelValues=%dms 总计=%dms",
                qid, (t1 - t0), (t2 - t1), (t3 - t2), (t4 - t3), (t4 - t0));
        } catch (Exception e) {
            long tErr = System.currentTimeMillis();
            r.cardValuesReady = false;
            r.warnings.add("卡片值物化失败: " + e.getMessage());
            LOG.errorf(e, "[create-quotation] 后置物化失败 quotation=%s 耗时=%dms（不丢单，前端 warm 兜底）",
                qid, (tErr - t0));
        }
    }

    /**
     * 读库判定 cardValuesReady / costingTreeRows。
     *
     * <p><b>task-260825 B-21（D-5 返修，2026-08-26 亲验暴露）</b>：本方法原先裸调
     * {@code Quotation.findById(...)}——同步路径下靠请求线程侥幸能拿到 Hibernate 的
     * {@code TransactionScopedSession}，D-5 把 {@link #materialize} 搬到后台线程
     * （{@code managedExecutor.runAsync}）后，{@code @ActivateRequestContext} 只激活了
     * CDI 请求作用域，<b>不激活 JTA 事务</b>——{@code TransactionScopedSession.acquireSession()}
     * 要求有活跃事务，没有就抛异常。实测复现：③ 早已成功补完 1845 行，但本方法在 ③ 之后
     * 裸读 {@code Quotation} 时炸出 {@code Failed to start quarkus}... 不对，是
     * {@code acquireSession} 异常，被 {@link #materialize} 顶层 catch 吞成一条内容与
     * "真失败"完全同形的 {@code ERROR [CreateQuotationMaterializer] 后置物化失败}，
     * 且 B-10 的 {@code [create-quotation-timing]} 埋点因异常提前跳出而丢失——AC-9 的
     * 仪器失灵、AC-14 判断后台失败的依据也失真。
     *
     * <p>修法：给本方法整体包一层 {@link QuarkusTransaction#run(Runnable)}（默认
     * {@code REQUIRE_NEW} 语义、默认 60s 超时——本方法只读两条快查询，不需要 B-15 那种
     * 600s 扩展，且刻意不复用 ③ 那层扩展超时的包装，避免语义混淆）。不改用「把
     * {@code Quotation.findById} 换成原生查询」这条路：本方法下半段的
     * {@code createNativeQuery(...).getResultList()} 是否天然不需要活跃事务<b>未经验证</b>——
     * 异常发生在第一行就直接跳出，从未执行到那一行，不能假设它必然安全；显式包一层事务对
     * Panache 与裸 {@code EntityManager} 两种访问方式一视同仁，不依赖这个未验证的假设。
     */
    private void fillStatus(V6QuotationCommitService.CommitResult r) {
        QuarkusTransaction.run(() -> {
            Quotation q = Quotation.findById(r.quotationId);
            boolean hasCosting = q != null && q.costingCardTemplateId != null;
            @SuppressWarnings("unchecked")
            List<Object[]> rows = QuotationLineItem.getEntityManager().createNativeQuery(
                    "SELECT quote_card_values::text, costing_card_values::text " +
                    "FROM quotation_line_item WHERE quotation_id = :q")
                .setParameter("q", r.quotationId).getResultList();
            boolean ready = !rows.isEmpty();
            int treeRows = 0;
            for (Object[] row : rows) {
                String quote = row[0] == null ? null : row[0].toString();
                String costing = row[1] == null ? null : row[1].toString();
                if (quote == null || quote.contains(FAIL_MARK)) ready = false;
                if (hasCosting && (costing == null || costing.contains(FAIL_MARK))) ready = false;
                if (hasCosting && costing != null && !costing.contains(FAIL_MARK)) treeRows += countTreeRows(costing);
            }
            r.cardValuesReady = ready;
            r.costingTreeRows = treeRows;
            if (!ready) r.warnings.add("部分行卡片值未就绪或渲染失败，详情/核价管理可能显式提示");
        });
    }

    /** best-effort：累加 costing_card_values 各页签 baseRows 行数（解析失败计 0）。 */
    private int countTreeRows(String costingJson) {
        try {
            JsonNode root = MAPPER.readTree(costingJson);
            int n = 0;
            for (JsonNode tab : root.path("tabs")) n += tab.path("baseRows").size();
            return n;
        } catch (Exception e) { return 0; }
    }
}
