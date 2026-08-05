package com.cpq.priceadjust.service;

import com.cpq.common.exception.BusinessException;
import com.cpq.customer.entity.Customer;
import com.cpq.priceadjust.dto.*;
import com.cpq.priceadjust.entity.*;
import com.cpq.quotation.entity.Quotation;
import com.cpq.quotation.entity.QuotationLineItem;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.OffsetDateTime;
import java.util.*;

/**
 * task-0729 屏 7 · 报价单侧价格版本（api.md §4.1 / §4.2，裁决 13/14/15/24/30/38）。
 *
 * <p>销售只读可见——本类**不提供任何写入口**，裁决 14 明确「单内切换价格版本 = 只读预览，不落库；
 * 单据金额的唯一变更通道是财务审核通过后的自动更新」。
 *
 * <h3>🔒 料号级价格版本表的四态判定（api.md §4.1 铁律 + 2026-08-04 业务方裁定）</h3>
 * 这张表被文档反复定位为「单内混合价的**可查证据**」（§11.1.1）。它唯一的存在意义是可查证，
 * 因此判定的第一原则是 <b>宁可说"不知道/尚未更新"，也不能显示一个这张单实际不在的版本号</b>。
 *
 * <p>判定顺序（**顺序本身是语义的一部分，不可重排**）：
 * <ol>
 *   <li><b>{@code NOT_PARTICIPATING}</b> —— 该客户 × 料号没有版本指针，压根没进调价体系；</li>
 *   <li><b>{@code NOT_UPDATED}</b> —— 见下"两条来源"；</li>
 *   <li><b>{@code REJECTED}</b> —— 该客户 × 料号最近一条**已决**审核（APPROVED/REJECTED，
 *       跳过 PENDING/VOIDED）是驳回，料号因此停在指针那一版；</li>
 *   <li><b>{@code UPGRADED}</b> —— 兜底：这张单确实在指针指向的那一版上。</li>
 * </ol>
 *
 * <p><b>为什么 NOT_UPDATED 必须压过 REJECTED</b>（2026-08-04 裁定）：前端
 * {@code materialVersionLabel.ts} 对 REJECTED 的文案是 {@code `${currentVersionNo}（未升版）`}
 * —— <b>它同样显示版本号</b>。所以若这张单实际不在 {@code currentVersionNo} 上，REJECTED 一样会
 * 说谎。「这张单在不在那一版」是**单级**事实，「该料号被驳回过」是**客户级**事实，
 * 单级事实必须压过客户级事实。
 *
 * <p><b>{@code NOT_UPDATED} 的两条来源</b>：
 * <ol type="a">
 *   <li><b>锚点 job_item 非 SUCCESS</b>（api.md:509 铁律：「必须读
 *       {@code material_price_update_job_item}，不能只读指针」）。锚点 = 该料号
 *       <b>指针当前指向版本</b>名下的 job_item（§11.6.3.2「判定锚点」，不是任意历史批次）——
 *       指针推进是同步的、改单是异步的，失败时指针**已经**推进了，只读指针必然说谎。
 *       此路径带出 {@code pendingJobItemId} 供诊断。</li>
 *   <li><b>无锚点 job_item 且本单建单早于指针推进</b>。🔒 <b>判据来源 = 2026-08-04 业务方裁定，
 *       非 §11.6.0 原文</b>。成因：{@code SENT}/{@code ACCEPTED}/{@code EXPIRED}/{@code CANCELLED}
 *       的单被 §11.6.0 有意排除在升版范围外（「显式备案：SENT 单会保持旧价」），从来没进过任何
 *       批次，因此一条 job_item 都没有，但它**实际停在旧价**。只读 job_item 无法把这种单与
 *       "指针推进之后才建的新单（通道 A 天然用新价）"区分开，故追加建单时间判据：
 *       {@code quotation.created_at >= material_price_version_ref.updated_at} 成立走
 *       {@code UPGRADED}，不成立走 {@code NOT_UPDATED}。
 *       <p><b>为什么是 NOT_UPDATED 而不是别的</b>：{@code MaterialVersionState} 四态由前端锁死、
 *       无第 5 态可用，而这批单确实"尚未更新"，{@code NOT_UPDATED} 是四态里唯一不说谎的近似。
 *       <p><b>极端误判方向</b>：正常单被标「尚未更新」= <b>虚惊，不是错误信息</b>（它只是不再声称
 *       自己在某一版上）；反过来若不加此判据，SENT 单会显示 {@code UPGRADED} = 直接说谎。
 *       两个方向的代价不对称，故取前者。</li>
 * </ol>
 */
@ApplicationScoped
public class QuotationPriceRevisionService {

    private static final Logger LOG = Logger.getLogger(QuotationPriceRevisionService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 快照里存在、但当前 {@code quotation_line_item} 已删除的行的料号占位（house placeholder）。 */
    private static final String DELETED_LINE_PLACEHOLDER = "—";

    @Inject
    EntityManager em;

    // ══════════════════════════ §4.1 版本轨迹 + 料号级价格版本 ══════════════════════════

    @Transactional
    public PriceRevisionsResponse getPriceRevisions(UUID quotationId) {
        Quotation q = Quotation.findById(quotationId);
        if (q == null) throw new BusinessException(404, "报价单不存在：" + quotationId);

        PriceRevisionsResponse resp = new PriceRevisionsResponse();
        resp.revisions = buildRevisions(quotationId, q);
        resp.materialVersions = buildMaterialVersions(quotationId, q);
        return resp;
    }

    private List<PriceRevisionDTO> buildRevisions(UUID quotationId, Quotation q) {
        List<QuotationPriceRevision> revs = QuotationPriceRevision.list("quotationId", quotationId);
        if (revs.isEmpty()) return List.of();

        // based_version_id → version_no 批量解析（初版留空，D6）
        Set<UUID> versionIds = new HashSet<>();
        for (QuotationPriceRevision r : revs) if (r.basedVersionId != null) versionIds.add(r.basedVersionId);
        Map<UUID, String> versionNoById = loadVersionNos(versionIds);

        List<PriceRevisionDTO> out = new ArrayList<>(revs.size());
        for (QuotationPriceRevision r : revs) {
            PriceRevisionDTO dto = new PriceRevisionDTO();
            dto.revisionId = r.id;
            dto.revisionNo = r.revisionNo;
            dto.isInitial = r.basedVersionId == null;
            dto.basedVersionNo = r.basedVersionId == null ? null : versionNoById.get(r.basedVersionId);
            dto.sealed = Boolean.TRUE.equals(r.sealed);
            dto.firstEffectiveAt = r.firstEffectiveAt;
            dto.lastUpdatedAt = r.lastUpdatedAt;
            dto.upgradedMaterialNos = parseStringArray(r.upgradedMaterialNos);
            // 未定型（sealed=false）时 quote_total_amount 留 NULL（§11.10.6 不物化）→ 取当前值，
            // 与 §4.2 预览「未定型返回当前值」同一口径，前端无感。
            dto.quoteTotalAmount = r.quoteTotalAmount != null ? r.quoteTotalAmount : q.totalAmount;
            out.add(dto);
        }
        // 初版恒排首位，其余按首次生效时间升序（api.md §4.1 示例顺序）
        out.sort(Comparator
                .comparing((PriceRevisionDTO d) -> d.isInitial ? 0 : 1)
                .thenComparing(d -> d.firstEffectiveAt, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(d -> d.revisionNo, Comparator.nullsLast(Comparator.naturalOrder())));
        return out;
    }

    private List<MaterialVersionRowDTO> buildMaterialVersions(UUID quotationId, Quotation q) {
        // ---- 料号清单：本单全部产品行的销售料号（去重、保持行序）
        List<QuotationLineItem> lines =
                QuotationLineItem.list("quotationId = ?1 order by sortOrder, id", quotationId);
        LinkedHashMap<String, String> nameByMaterial = new LinkedHashMap<>();
        for (QuotationLineItem l : lines) {
            if (l.productPartNoSnapshot == null || l.productPartNoSnapshot.isBlank()) continue;
            nameByMaterial.putIfAbsent(l.productPartNoSnapshot, l.productNameSnapshot);
        }
        if (nameByMaterial.isEmpty()) return List.of();
        List<String> materialNos = new ArrayList<>(nameByMaterial.keySet());

        String customerNo = resolveCustomerNo(q);
        if (customerNo == null) {
            // 无客户编号 = 不可能有「客户 × 料号」指针 → 全部未参与调价
            LOG.debugf("[price-revisions] q=%s 无客户编号，全部料号判 NOT_PARTICIPATING", quotationId);
            return allNotParticipating(nameByMaterial);
        }

        // ---- 指针（客户 × 料号级，裁决 40）
        Map<String, MaterialPriceVersionRef> refByMaterial = new HashMap<>();
        for (MaterialPriceVersionRef r : MaterialPriceVersionRef.<MaterialPriceVersionRef>list(
                "customerNo = ?1 and materialNo in ?2", customerNo, materialNos)) {
            refByMaterial.putIfAbsent(r.materialNo, r);
        }
        Set<UUID> ptrVersionIds = new HashSet<>();
        for (MaterialPriceVersionRef r : refByMaterial.values()) if (r.versionId != null) ptrVersionIds.add(r.versionId);
        Map<UUID, String> versionNoById = loadVersionNos(ptrVersionIds);

        // ---- 锚点 job_item：本单 × 各料号，按 updatedAt 倒序（同料号同版本取最近一条）
        List<MaterialPriceUpdateJobItem> items = MaterialPriceUpdateJobItem.list(
                "quotationId = ?1 and materialNo in ?2 order by updatedAt desc", quotationId, materialNos);
        Map<UUID, UUID> versionIdByJobId = loadJobVersionIds(items);

        // ---- 最近一条【已决】审核（跳过 PENDING/VOIDED——未决不构成"被驳回"）
        Map<String, String> latestDecidedReviewStatus = new HashMap<>();
        for (MaterialPriceReview rv : MaterialPriceReview.<MaterialPriceReview>list(
                "customerNo = ?1 and materialNo in ?2 and status in ?3 order by createdAt desc",
                customerNo, materialNos,
                List.of(MaterialPriceReview.STATUS_APPROVED, MaterialPriceReview.STATUS_REJECTED))) {
            latestDecidedReviewStatus.putIfAbsent(rv.materialNo, rv.status);
        }

        List<MaterialVersionRowDTO> out = new ArrayList<>(materialNos.size());
        for (Map.Entry<String, String> e : nameByMaterial.entrySet()) {
            out.add(judge(e.getKey(), e.getValue(), q, refByMaterial.get(e.getKey()), versionNoById,
                    items, versionIdByJobId, latestDecidedReviewStatus.get(e.getKey())));
        }
        return out;
    }

    /**
     * 四态判定。顺序即语义，见类注释——调整顺序前先读那段。
     */
    private MaterialVersionRowDTO judge(String materialNo, String materialName, Quotation q,
                                        MaterialPriceVersionRef ref, Map<UUID, String> versionNoById,
                                        List<MaterialPriceUpdateJobItem> allItems,
                                        Map<UUID, UUID> versionIdByJobId, String latestDecidedReview) {
        MaterialVersionRowDTO row = new MaterialVersionRowDTO();
        row.materialNo = materialNo;
        row.materialName = materialName;

        // ① 无指针 → 未参与调价
        if (ref == null || ref.versionId == null) {
            row.currentVersionNo = null;
            row.state = MaterialVersionRowDTO.NOT_PARTICIPATING;
            return row;
        }
        row.currentVersionNo = versionNoById.get(ref.versionId);

        // ② NOT_UPDATED（两条来源，见类注释）
        //    (a) 锚点 job_item 存在且非 SUCCESS —— api.md:509 铁律的落点
        MaterialPriceUpdateJobItem anchor = findAnchorItem(materialNo, ref.versionId, allItems, versionIdByJobId);
        if (anchor != null) {
            if (!MaterialPriceUpdateJobItem.SUCCESS.equals(anchor.status)) {
                row.state = MaterialVersionRowDTO.NOT_UPDATED;
                row.pendingJobItemId = anchor.id;
                return row;
            }
        } else if (isCreatedBeforePointerAdvance(q, ref)) {
            //  (b) 无锚点 + 建单早于指针推进 → 本单从未被纳入该版批次（SENT/ACCEPTED/… 被 §11.6.0
            //      有意排除），实际停在旧价。2026-08-04 业务方裁定，pendingJobItemId 无值。
            row.state = MaterialVersionRowDTO.NOT_UPDATED;
            return row;
        }

        // ③ 最近一条已决审核是驳回 → 料号停在指针这一版（前端渲染「V…（未升版）」）
        if (MaterialPriceReview.STATUS_REJECTED.equals(latestDecidedReview)) {
            row.state = MaterialVersionRowDTO.REJECTED;
            return row;
        }

        // ④ 兜底：本单确实在指针指向的那一版
        row.state = MaterialVersionRowDTO.UPGRADED;
        return row;
    }

    /**
     * 锚点 = 该料号**指针当前指向版本**名下的 job_item（§11.6.3.2）。
     * 不是"任意历史批次的最近一条"——同一单同一料号可能有多期 job_item，按哪期判会直接决定
     * 这张表说不说谎。{@code allItems} 已按 {@code updatedAt} 倒序，首个命中即最近一条。
     */
    private MaterialPriceUpdateJobItem findAnchorItem(String materialNo, UUID pointerVersionId,
                                                      List<MaterialPriceUpdateJobItem> allItems,
                                                      Map<UUID, UUID> versionIdByJobId) {
        for (MaterialPriceUpdateJobItem it : allItems) {
            if (!materialNo.equals(it.materialNo)) continue;
            if (pointerVersionId.equals(versionIdByJobId.get(it.jobId))) return it;
        }
        return null;
    }

    /**
     * 建单时间早于指针推进时间 = 这张单在指针推进那一刻就已存在，却没有对应批次记录
     * → 它没被纳入本次升版范围（§11.6.0 排除态）。时间戳缺失时保守返回 false（判 UPGRADED，
     * 维持"无证据不主张"）。
     */
    private boolean isCreatedBeforePointerAdvance(Quotation q, MaterialPriceVersionRef ref) {
        OffsetDateTime created = q.createdAt;
        OffsetDateTime advanced = ref.updatedAt;
        if (created == null || advanced == null) return false;
        return created.isBefore(advanced);
    }

    private List<MaterialVersionRowDTO> allNotParticipating(LinkedHashMap<String, String> nameByMaterial) {
        List<MaterialVersionRowDTO> out = new ArrayList<>(nameByMaterial.size());
        for (Map.Entry<String, String> e : nameByMaterial.entrySet()) {
            MaterialVersionRowDTO row = new MaterialVersionRowDTO();
            row.materialNo = e.getKey();
            row.materialName = e.getValue();
            row.currentVersionNo = null;
            row.state = MaterialVersionRowDTO.NOT_PARTICIPATING;
            out.add(row);
        }
        return out;
    }

    // ══════════════════════════ §4.2 切版只读预览 ══════════════════════════

    /**
     * 切版只读预览（裁决 14：不落库）。
     *
     * <p>🚨 <b>双侧都从快照渲染</b>（验收 #55 专防）：报价侧读快照、核价侧读当前值这种混合语义，
     * 在报价侧看着**完全正常**，极难发现。两侧取数在本方法里共用同一个 {@code materialized}
     * 分支判断，从结构上杜绝分岔。
     *
     * <p>🔒 未定型初版（{@code sealed=false}）的 snapshot 为 NULL（§11.10.6 性能纪律：定型前
     * 不物化，saveDraft 零额外开销）→ 直接返回该单**当前值**，前端无感。
     */
    @Transactional
    public RevisionPreviewResponse getPreview(UUID quotationId, UUID revisionId) {
        Quotation q = Quotation.findById(quotationId);
        if (q == null) throw new BusinessException(404, "报价单不存在：" + quotationId);

        QuotationPriceRevision rev = QuotationPriceRevision.findById(revisionId);
        // 归属校验用 404 而不是 403——不泄漏"该版本存在但属于另一张单"
        if (rev == null || !quotationId.equals(rev.quotationId)) {
            throw new BusinessException(404, "价格版本不存在或不属于该报价单：" + revisionId);
        }

        RevisionPreviewResponse resp = new RevisionPreviewResponse();
        resp.revisionNo = rev.revisionNo;
        resp.readonly = true;

        boolean materialized = Boolean.TRUE.equals(rev.sealed) && rev.quoteCardValues != null;
        resp.lineItems = materialized ? buildFromSnapshot(quotationId, rev) : buildFromCurrent(quotationId);
        resp.quoteTotalAmount = materialized && rev.quoteTotalAmount != null ? rev.quoteTotalAmount : q.totalAmount;

        LOG.debugf("[price-revision-preview] q=%s rev=%s sealed=%s 取数=%s 行数=%d",
                quotationId, rev.revisionNo, rev.sealed, materialized ? "快照" : "当前值", resp.lineItems.size());
        return resp;
    }

    /** 已定型：三个 JSONB 列均以 {@code lineItemId → 值} 平铺，原样透传。 */
    private List<RevisionPreviewLineItemDTO> buildFromSnapshot(UUID quotationId, QuotationPriceRevision rev) {
        JsonNode quoteMap = parseObject(rev.quoteCardValues);
        JsonNode costingMap = parseObject(rev.costingCardValues);
        JsonNode rowsMap = parseObject(rev.snapshotRows);

        // 料号从当前行反查（快照不存料号）；已删除的行走占位符，不报错（验收 #56：结构变化不得错位）
        Map<String, String> materialNoByLineId = new LinkedHashMap<>();
        for (QuotationLineItem l : QuotationLineItem.<QuotationLineItem>list(
                "quotationId = ?1 order by sortOrder, id", quotationId)) {
            materialNoByLineId.put(l.id.toString(), l.productPartNoSnapshot);
        }

        // 顺序：先按当前行序输出快照里存在的行，再补快照独有（当时有、现已删）的行
        List<String> ordered = new ArrayList<>();
        for (String lid : materialNoByLineId.keySet()) if (quoteMap.has(lid)) ordered.add(lid);
        for (Iterator<String> it = quoteMap.fieldNames(); it.hasNext(); ) {
            String lid = it.next();
            if (!materialNoByLineId.containsKey(lid)) ordered.add(lid);
        }

        List<RevisionPreviewLineItemDTO> out = new ArrayList<>(ordered.size());
        for (String lid : ordered) {
            RevisionPreviewLineItemDTO dto = new RevisionPreviewLineItemDTO();
            dto.lineItemId = parseUuidOrNull(lid);
            String mno = materialNoByLineId.get(lid);
            dto.materialNo = (mno != null && !mno.isBlank()) ? mno : DELETED_LINE_PLACEHOLDER;
            dto.quoteCardValues = quoteMap.get(lid);
            dto.costingCardValues = costingMap.get(lid);   // 🔒 快照，不是当前值
            dto.snapshotRows = rowsMap.get(lid);
            out.add(dto);
        }
        return out;
    }

    /** 未定型（§11.10.6）：整单当前值，结构与快照分支逐字段同构。 */
    private List<RevisionPreviewLineItemDTO> buildFromCurrent(UUID quotationId) {
        List<QuotationLineItem> lines =
                QuotationLineItem.list("quotationId = ?1 order by sortOrder, id", quotationId);
        if (lines.isEmpty()) return List.of();

        Map<UUID, ObjectNode> rowsByLine = loadCurrentSnapshotRows(lines);

        List<RevisionPreviewLineItemDTO> out = new ArrayList<>(lines.size());
        for (QuotationLineItem l : lines) {
            RevisionPreviewLineItemDTO dto = new RevisionPreviewLineItemDTO();
            dto.lineItemId = l.id;
            dto.materialNo = (l.productPartNoSnapshot != null && !l.productPartNoSnapshot.isBlank())
                    ? l.productPartNoSnapshot : DELETED_LINE_PLACEHOLDER;
            dto.quoteCardValues = parseOrNull(l.quoteCardValues);
            dto.costingCardValues = parseOrNull(l.costingCardValues);
            dto.snapshotRows = rowsByLine.get(l.id);
            out.add(dto);
        }
        return out;
    }

    /**
     * 当前值分支的 {@code snapshotRows}，结构镜像
     * {@code MaterialVersionUpgradeService#buildWholeQuotationSnapshot}：
     * {@code { "<componentId>": <snapshot_rows 原始 JSON> }}。一次查询覆盖整单，避免 N+1。
     */
    @SuppressWarnings("unchecked")
    private Map<UUID, ObjectNode> loadCurrentSnapshotRows(List<QuotationLineItem> lines) {
        List<UUID> lineIds = new ArrayList<>(lines.size());
        for (QuotationLineItem l : lines) lineIds.add(l.id);

        Map<UUID, ObjectNode> byLine = new HashMap<>();
        for (UUID id : lineIds) byLine.put(id, MAPPER.createObjectNode());

        List<Object[]> rows = em.createNativeQuery(
                        "SELECT line_item_id, component_id, snapshot_rows FROM quotation_line_component_data "
                                + "WHERE line_item_id IN (:lids)")
                .setParameter("lids", lineIds)
                .getResultList();
        for (Object[] r : rows) {
            UUID lid = (UUID) r[0];
            ObjectNode target = byLine.get(lid);
            if (target == null) continue;
            String cid = r[1] != null ? r[1].toString() : "null";
            JsonNode parsed = parseOrNull((String) r[2]);
            if (parsed == null) target.putNull(cid); else target.set(cid, parsed);
        }
        return byLine;
    }

    // ══════════════════════════ 小工具 ══════════════════════════

    private Map<UUID, String> loadVersionNos(Set<UUID> versionIds) {
        if (versionIds.isEmpty()) return Map.of();
        Map<UUID, String> out = new HashMap<>();
        for (ElementPriceVersion v : ElementPriceVersion.<ElementPriceVersion>list(
                "id in ?1", new ArrayList<>(versionIds))) {
            out.put(v.id, v.versionNo);
        }
        return out;
    }

    private Map<UUID, UUID> loadJobVersionIds(List<MaterialPriceUpdateJobItem> items) {
        if (items.isEmpty()) return Map.of();
        Set<UUID> jobIds = new HashSet<>();
        for (MaterialPriceUpdateJobItem it : items) if (it.jobId != null) jobIds.add(it.jobId);
        if (jobIds.isEmpty()) return Map.of();
        Map<UUID, UUID> out = new HashMap<>();
        for (MaterialPriceUpdateJob j : MaterialPriceUpdateJob.<MaterialPriceUpdateJob>list(
                "id in ?1", new ArrayList<>(jobIds))) {
            if (j.versionId != null) out.put(j.id, j.versionId);
        }
        return out;
    }

    private String resolveCustomerNo(Quotation q) {
        if (q.customerId == null) return null;
        Customer c = Customer.findById(q.customerId);
        return c != null ? c.code : null;
    }

    private List<String> parseStringArray(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            JsonNode n = MAPPER.readTree(json);
            if (!n.isArray()) return List.of();
            List<String> out = new ArrayList<>(n.size());
            for (JsonNode e : n) if (!e.isNull()) out.add(e.asText());
            return out;
        } catch (Exception e) {
            LOG.warnf("[price-revisions] upgraded_material_nos 解析失败，按空列表处理：%s", e.getMessage());
            return List.of();
        }
    }

    private JsonNode parseOrNull(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }

    /** 解析成对象节点；失败/为空一律返回空对象，让下游 {@code get(key)} 安全返回 null。 */
    private JsonNode parseObject(String json) {
        JsonNode n = parseOrNull(json);
        return (n != null && n.isObject()) ? n : MAPPER.createObjectNode();
    }

    private UUID parseUuidOrNull(String s) {
        try {
            return UUID.fromString(s);
        } catch (Exception e) {
            return null;
        }
    }
}
