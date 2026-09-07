package com.cpq.dataset.quotation;

import com.cpq.basicdata.v6.service.QuotationLineItemMaterializeService;
import com.cpq.common.exception.BusinessException;
import com.cpq.customer.entity.Customer;
import com.cpq.importexcel.entity.ImportRecord;
import com.cpq.quotation.dto.CreateQuotationRequest;
import com.cpq.quotation.dto.CustomerPartCandidateDTO;
import com.cpq.quotation.dto.QuotationDTO;
import com.cpq.quotation.entity.Quotation;
import com.cpq.quotation.entity.QuotationLineItem;
import com.cpq.quotation.service.QuotationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * task-260907 · B-6 / B-7 / B-9：由已成功的 {@code ds_quote_*} 导入批次建报价单。
 *
 * <h3>与 V6 {@code V6QuotationCommitService} 的两处差异（其余逐行对齐，D-15）</h3>
 * <ol>
 *   <li><b>明细行候选来源</b>：V6 靠 {@code material_customer_map} + {@code created_at ±时间窗}
 *       <b>近似</b>框定「本次导入批次」。新链路改为<b>精确清单</b>：Phase 2 成功时把本批次的
 *       {@code (customer_no, customer_product_no)} 写进 {@code import_record.metadata.batchParts}，
 *       建单时按它 + {@code customer_no} 双条件查 {@code ds_quote_customer_part}。
 *       <p>🔄 <b>D-26 更正（2026-09-07）</b>：本条原写「{@code ds_quote_customer_part} 自带
 *       {@code customer_no}，一条 JOIN 就能精确框定，不需要批次维度」—— <b>那句是错的</b>。
 *       客户维度 ≠ 批次维度：该表是<b>主数据表</b>，同一客户的历史料号会长期累积。
 *       实证：一份 <b>3 行</b>的 Excel 建出了 <b>15</b> 行明细（表里另有 12 行历史）。
 *       <p>🚫 但<b>仍然不复刻 V6 的 hfPairs + 时间窗</b>：那是近似（并发导入/补导会互相串），
 *       这里存的是本次解析结果本身。</li>
 *   <li><b>不做 pending 过户</b>：{@code ds_quote_*} 29 张表<b>无一张有 {@code pending_quotation_id} 列</b>
 *       （立项期逐表 {@code information_schema} 实测，全 0）⇒ 没有 pending 归属这个概念，
 *       {@code repointPendingOwnership} 在新链路上无对应物。</li>
 * </ol>
 *
 * <h3>事务边界</h3>
 * {@link #createQuotation} 一个 {@code @Transactional}：建单 + 建行强一致（不丢单）。
 * 卡片值物化在 <b>Resource 层、本事务提交之后</b>转后台（B-8）——
 * 🚫 不能放进本事务：物化要读刚建的明细行，同事务内它们还没提交。
 *
 * <h3>🚫 N+1（AC-20②）</h3>
 * 建单链路的 SQL 条数与料号数<b>无关</b>：
 * 候选查询 <b>1 条</b>（{@link #listCandidates}）· 建行 {@code ceil(行数/200)} 条批量 INSERT
 * （{@link QuotationLineItemMaterializeService} 已治理）· 其余为常数条。
 * 本类的两处循环（{@link #listCandidates} 结果映射、{@link #batchProductNosOf} 批次清单过滤）
 * 均为<b>纯内存</b>，循环体内零查询。
 */
@ApplicationScoped
public class DatasetQuotationCommitService {

    private static final Logger LOG = Logger.getLogger(DatasetQuotationCommitService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * B-7 / api.md §3 的候选查询。
     *
     * <p>🚨 <b>必须 {@code LEFT JOIN}</b>：{@code INNER} 会在 {@code ds_quote_material} 缺行时
     * <b>静默丢明细行</b>（{@code task-260903} 立项期实测过同型：{@code customer} 表 17 行有 3 行
     * JOIN 不到）。品名缺失是可容忍的降级（{@code materializeLinesFromCandidates} 有三级兜底
     * {@code partName → customerPartName → partNo}），丢行不是。
     *
     * <p>🔄 <b>D-26：{@code AND cp.customer_product_no = ANY(:batchProductNos)} 不能省。</b>
     * {@code ds_quote_customer_part} 是<b>主数据表、没有任何批次维度列</b>，只按 {@code customer_no}
     * 查会把该客户的<b>全部历史料号</b>都建成明细行 —— 实证：一份 <b>3 行</b>的 Excel 建出了
     * <b>15</b> 行（表里另有 12 行历史）。批次清单来自
     * {@code import_record.metadata.batchParts}（Phase 2 成功时由本次解析结果精确写入）。
     * <p>🚫 <b>仍然不走 V6 的 {@code hfPairs + created_at ±时间窗}</b>：那是近似，并发导入
     * 与补导会互相串；这里是精确清单。
     */
    private static final String CANDIDATE_SQL =
            "SELECT cp.customer_product_no, cp.customer_part_name, cp.material_no, m.material_name " +
            "  FROM ds_quote_customer_part cp " +
            "  LEFT JOIN ds_quote_material m ON m.material_no = cp.material_no " +
            " WHERE cp.customer_no = :customerCode " +
            "   AND cp.customer_product_no = ANY(:batchProductNos) " +
            " ORDER BY cp.customer_product_no";

    @Inject EntityManager em;
    @Inject QuotationService quotationService;
    @Inject QuotationLineItemMaterializeService materializeService;

    /** 建单结果 —— {@code materializing} 由 Resource 层在派发后台物化时置位。 */
    public record Commit(UUID quotationId, String quotationNumber, UUID importRecordId,
                         int lineItemsCount, boolean reentered) {}

    @Transactional
    public Commit createQuotation(QuotationImportDTOs.CreateQuotationRequest req, UUID userId) {
        // ── ① 导入批次校验（api.md §3 错误表）
        ImportRecord rec = ImportRecord.findById(req.importRecordId);
        if (rec == null) throw new BusinessException(404, "导入记录不存在: " + req.importRecordId);
        if (!QuotationImportRecordWriter.SYSTEM_TYPE.equals(rec.systemType)) {
            throw new BusinessException(400, "该导入记录不是报价数据导入批次（system_type="
                    + rec.systemType + "），无法用于建单");
        }
        // 🚨 防串号：前端可能拿 A 客户的导入批次配 B 客户建单，那会建出一张
        //    「客户是 B、明细行来自 A」的报价单，且全程不报错。
        //
        // ⚠️ 2026-09-07 实测补强：`system_type='DATASET_QUOTE'` <b>不足以</b>识别「本链路的批次」——
        //    【基础资料维护】的 POST /dataset/quote/import 走 DatasetImportService#recordHistory，
        //    写的是<b>同一个</b> system_type（现网已有 36 条 SUCCESS，其中大部分来自维护页签）。
        //    区别在于：维护批次<b>没有选客户</b> ⇒ customer_id 为 NULL。
        //    若这里只写 `rec.customerId != null && !equals(...)`，NULL 会从判据里漏过去，
        //    用一个维护批次也能建单 —— 那条批次压根没有「本次导入属于哪个客户」这个语义。
        //    ⇒ customer_id 为空一律拒收。
        if (rec.customerId == null) {
            throw new BusinessException(400,
                    "该导入记录没有关联客户（可能来自【基础资料维护】的导入），无法用于建单");
        }
        if (!rec.customerId.equals(req.customerId)) {
            throw new BusinessException(400, "customerId 与导入记录不一致：导入批次的客户为 "
                    + rec.customerId + "，本次请求为 " + req.customerId);
        }
        if (!"SUCCESS".equals(rec.importStatus)) {
            throw new BusinessException(400, "导入尚未成功（当前状态 " + rec.importStatus + "），无法建单");
        }

        // ── ② 幂等重入（B-9 / AC-12）：同 importRecordId 已建过单且该单仍在 → 返回既有，不重复建单建行。
        //    放在校验之后：先确认「这个批次配这个客户是合法的」，再谈重入。
        if (rec.quotationId != null) {
            Quotation existing = Quotation.findById(rec.quotationId);
            if (existing != null) {
                int count = (int) QuotationLineItem.count("quotationId", existing.id);
                LOG.infof("[quotation-create] 幂等重入 importRecord=%s → 既有 quotation=%s（%d 行）",
                        req.importRecordId, existing.id, count);
                // D-31：幂等分支同样要带 quotationNumber —— 前端两条路径（新建 / 重复提交）
                // 用的是同一段渲染代码，这里漏给会变成「重复提交后单号突然空了」。
                return new Commit(existing.id, existing.quotationNumber, req.importRecordId, count, true);
            }
            // 单已被删除 → 落到下面重新建（与 V6 同语义）
        }

        // ── ③ 客户
        Customer customer = Customer.findById(req.customerId);
        if (customer == null) throw new BusinessException(404, "客户不存在: " + req.customerId);
        String customerCode = customer.code;
        if (customerCode == null || customerCode.isBlank()) {
            throw new BusinessException(400, "客户未配置 code（业务编号），无法定位客户料号");
        }

        // ── ④ 建单（复用既有 QuotationService.create，透传分类 / 双模板）
        CreateQuotationRequest cq = new CreateQuotationRequest();
        cq.customerId = req.customerId;
        cq.name = req.name;
        cq.categoryId = req.categoryId;
        cq.customerTemplateId = req.customerTemplateId;
        cq.costingTemplateId = req.costingTemplateId;
        QuotationDTO q = quotationService.create(cq, userId);

        // ── ⑤ 建明细行（B-7 + D-26 批次口径）
        String[] batchProductNos = batchProductNosOf(rec, customerCode);
        List<CustomerPartCandidateDTO> candidates = listCandidates(customerCode, batchProductNos);
        List<UUID> lineIds = materializeService.materializeLinesFromCandidates(
                q.id, req.customerTemplateId, candidates);

        // ── ⑥ 回写 import_record.quotation_id
        //    🚫 不写 hfPairs（见类注释）：新链路的候选查询是精确的，不需要那个近似索引。
        rec.quotationId = q.id;
        rec.matchedRows = lineIds.size();

        LOG.infof("[quotation-create] importRecord=%s customer=%s → quotation=%s(%s) 明细行 %d 条（批次料号 %d 个）",
                req.importRecordId, customerCode, q.id, q.quotationNumber, lineIds.size(),
                batchProductNos.length);
        return new Commit(q.id, q.quotationNumber, req.importRecordId, lineIds.size(), false);
    }

    /**
     * 🔄 D-26：从 {@code import_record.metadata.batchParts} 取出本批次、且属于<b>本客户</b>的
     * 客户产品编号清单。
     *
     * <h3>🚨 「键不存在」与「空数组」必须区分开</h3>
     * <ul>
     *   <li><b>键不存在</b> ⇒ 这是 D-26 改动<b>之前</b>建的老批次，它没记过批次清单。
     *       此时若当成空清单，会静默建出一张 <b>0 行</b>的报价单 —— 用户看到的是「导入明明成功了，
     *       建出来却是空单」，而且不报错。⇒ <b>直接 400，让用户重新导一次</b>。</li>
     *   <li><b>空数组</b> ⇒ 本批次的「客户料号」sheet 对该客户确实 0 行，
     *       这是 <b>AC-18 的正常场景</b>：返 200、{@code lineItemsCount=0}。</li>
     * </ul>
     *
     * <p>按 {@code customerNo} 过滤而不是全取：AC-3（跨客户文件整份拒收）归 B-14，
     * 当前跨客户文件仍会放行 ⇒ 清单里可能混着别的客户的料号，
     * 不过滤就会把 A 客户的料号建进 B 客户的单里。
     *
     * <p>🚫 <b>N+1</b>：纯内存解析 + 过滤，无查询。
     */
    private String[] batchProductNosOf(ImportRecord rec, String customerCode) {
        JsonNode parts = null;
        if (rec.metadata != null && !rec.metadata.isBlank()) {
            try {
                parts = MAPPER.readTree(rec.metadata).get("batchParts");
            } catch (Exception e) {
                throw new BusinessException(400,
                        "导入记录的 metadata 无法解析，无法确定本次批次的料号范围，请重新导入");
            }
        }
        if (parts == null || !parts.isArray()) {
            throw new BusinessException(400,
                    "该导入批次没有记录本次导入的客户料号清单（可能是旧版本导入的记录），"
                    + "无法确定明细行范围，请重新导入一次");
        }
        List<String> out = new ArrayList<>(parts.size());
        for (JsonNode n : parts) {                       // 纯内存，无查库
            JsonNode c = n.get("customerNo");
            JsonNode p = n.get("customerProductNo");
            if (p == null || p.isNull()) continue;
            if (c != null && !c.isNull() && !customerCode.equals(c.asText())) continue;
            out.add(p.asText());
        }
        return out.toArray(new String[0]);
    }

    /**
     * B-7：按 {@code customer_no} 精确查候选。<b>一条 SQL</b>，与料号数无关。
     *
     * <p>结果映射是纯内存循环（🚫 循环体内零查询）。
     * {@code currentVersion} 留 null —— {@code materializeLinesFromCandidates} 会兜底成 2000，
     * 与 V6 路径同语义（{@code ds_quote_*} 侧没有「客户料号当前版本」这个概念）。
     */
    private List<CustomerPartCandidateDTO> listCandidates(String customerCode, String[] batchProductNos) {
        // AC-18：批次内该客户 0 个料号 → 不发查询，直接空候选（lineItemsCount=0，返 200）。
        if (batchProductNos.length == 0) return List.of();
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(CANDIDATE_SQL)
                .setParameter("customerCode", customerCode)
                .setParameter("batchProductNos", batchProductNos)
                .getResultList();

        List<CustomerPartCandidateDTO> out = new ArrayList<>(rows.size());
        for (Object[] r : rows) {                    // 纯内存映射，无查库
            CustomerPartCandidateDTO d = new CustomerPartCandidateDTO();
            d.customerProductNo = str(r[0]);
            d.customerPartName  = str(r[1]);
            d.partNo            = str(r[2]);         // 销售料号 = 明细行的 product_part_no_snapshot
            d.partName          = str(r[3]);         // LEFT JOIN 未命中时为 null，由建行兜底
            d.customerSpecific  = true;
            out.add(d);
        }
        return out;
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}
