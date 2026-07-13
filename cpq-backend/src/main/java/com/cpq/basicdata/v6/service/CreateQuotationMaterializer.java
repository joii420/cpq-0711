package com.cpq.basicdata.v6.service;

import com.cpq.configure.service.ConfigureSnapshotService;
import com.cpq.quotation.entity.Quotation;
import com.cpq.quotation.entity.QuotationLineItem;
import com.cpq.quotation.service.CardSnapshotService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    /** 对已建行、已提交的报价单做整单物化，并回填 result 状态字段。 */
    public void materialize(V6QuotationCommitService.CommitResult r) {
        if (r == null || r.quotationId == null) return;
        UUID qid = r.quotationId;
        try {
            snapshotService.snapshotQuotation(qid);          // ① 展开 driver → UPSERT 自建 componentData 行 + snapshot_rows
            cardSnapshotService.ensureStructure(qid);        // ② 4 份结构快照（幂等）
            cardSnapshotService.ensureCardValues(qid);       // ③ 整单批量算 quote/costing 卡片值（核价树 render 一次批量，无 N+1）
            cardSnapshotService.ensureExcelValues(qid);      // ④ 整单批量算 quote/costing Excel 值
            fillStatus(r);
        } catch (Exception e) {
            r.cardValuesReady = false;
            r.warnings.add("卡片值物化失败: " + e.getMessage());
            LOG.errorf(e, "[create-quotation] 后置物化失败 quotation=%s（不丢单，前端 warm 兜底）", qid);
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
