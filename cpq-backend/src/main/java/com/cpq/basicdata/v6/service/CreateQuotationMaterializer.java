package com.cpq.basicdata.v6.service;

import com.cpq.configure.service.ConfigureSnapshotService;
import com.cpq.quotation.entity.Quotation;
import com.cpq.quotation.entity.QuotationLineItem;
import com.cpq.quotation.service.CardSnapshotService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
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
     */
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
            QuarkusTransaction.run(QuarkusTransaction.runOptions().timeout(600),
                () -> cardSnapshotService.ensureCardValues(qid));
            long t3 = System.currentTimeMillis();
            cardSnapshotService.ensureExcelValues(qid);      // ④ 整单批量算 quote/costing Excel 值
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

    /** 读库判定 cardValuesReady / costingTreeRows。 */
    private void fillStatus(V6QuotationCommitService.CommitResult r) {
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
