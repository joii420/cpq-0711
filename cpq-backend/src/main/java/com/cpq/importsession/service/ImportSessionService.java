package com.cpq.importsession.service;

import com.cpq.common.exception.BusinessException;
import com.cpq.importsession.dto.CommitRequest;
import com.cpq.importsession.dto.CommitResult;
import com.cpq.importsession.dto.DecisionUpdateRequest;
import com.cpq.importsession.dto.DiffPayloadDTO;
import com.cpq.importsession.dto.UploadResultDTO;
import com.cpq.importsession.entity.ImportSession;
import com.cpq.importsession.entity.ImportSessionDecision;
import com.cpq.importexcel.parser.ParsedBasicData;
import com.cpq.quotation.dto.CreateQuotationRequest;
import com.cpq.quotation.dto.QuotationDTO;
import com.cpq.quotation.service.QuotationService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.io.InputStream;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * V6 导入会话核心业务服务。
 *
 * <p>三步流程：
 *   1. upload     — 解析 Excel → 写 staging → 检测差异 → 写决策 → 返回 UploadResultDTO
 *   2. updateDecisions — 用户切换 BUMP/NO_BUMP/其他决策时 upsert import_session_decision
 *   3. commit     — 按决策 merge staging → mat_* → 建报价单 → 清 staging → session=COMMITTED
 *
 * <p>事务边界：
 *   upload / updateDecisions / commit 各自标 @Transactional，commit 内部调用
 *   StagingMerger.applyPartVersionDecisions（也标了 @Transactional，参与外层事务）。
 *   cancel 标 @Transactional，仅更新状态（staging 由 CASCADE 清理）。
 */
@ApplicationScoped
public class ImportSessionService {

    private static final Logger LOG = Logger.getLogger(ImportSessionService.class);

    @Inject StagingWriter stagingWriter;
    @Inject DiffDetector diffDetector;
    @Inject StagingMerger stagingMerger;
    @Inject QuotationService quotationService;
    @Inject EntityManager em;

    // ── upload ────────────────────────────────────────────────────────────────

    /**
     * Step 1 Upload：解析 Excel → 写 staging → 检测差异 → 写决策。
     *
     * @param excel       Excel InputStream（调用方负责关闭）
     * @param customerId  关联客户 ID（从当前导入上下文或 multipart 字段取）
     * @param userId      操作人 ID（写 session.user_id）
     * @param fileName    源文件名（写 session.source_excel）
     * @return UploadResultDTO 包含 sessionId + DiffPayloadDTO（前端 Step 2 渲染用）
     */
    @Transactional
    public UploadResultDTO upload(InputStream excel, UUID customerId, UUID userId, String fileName) {

        // 1. 建 import_session（PENDING 状态，24h TTL）
        ImportSession session = new ImportSession();
        session.customerId = customerId;
        session.userId = userId;
        session.status = "PENDING";
        session.sourceExcel = fileName;
        session.persist();
        // 关键：强制 flush 让 INSERT 在当前事务里立即落库
        // 否则 StagingWriter 用独立 JDBC Connection 跑 INSERT 时，session 行尚未 flush
        // → FK violation: mat_part_staging_import_session_id_fkey
        em.flush();
        LOG.infof("V6 upload: created session=%s customer=%s user=%s", session.id, customerId, userId);

        // 2. 解析 Excel + 写 staging（StagingWriter 在当前事务内执行）
        ParsedBasicData data = stagingWriter.parseAndWriteStaging(excel, customerId, session.id);

        // 3. 检测差异（只读，不写库）
        // V6 fix: 传 session.id 启用指纹比对，避免重复导入相同数据时因精度/类型边界误判 BUMP
        var partVersionDecisions = diffDetector.detectPartVersions(data, customerId, session.id);
        var customerConflicts    = diffDetector.detectCustomerConflicts(data, customerId);
        var orphanRows           = diffDetector.detectOrphanRows(data, customerId);

        // 4. 将默认决策写入 import_session_decision 表（前端可后续 PUT 更新）
        for (var item : partVersionDecisions) {
            String valueJson = buildPartVersionDecisionJson(item.action, item.currentVersion,
                    item.suggestedVersion);
            persistDecision(session.id, "PART_VERSION", item.key, valueJson);
        }
        for (var item : customerConflicts) {
            persistDecision(session.id, "CUSTOMER_CONFLICT", item.key,
                    "{\"action\":\"" + item.defaultAction + "\"}");
        }
        for (var item : orphanRows) {
            persistDecision(session.id, "ORPHAN", item.key,
                    "{\"action\":\"" + item.defaultAction + "\"}");
        }

        // 5. 组装返回 DTO
        DiffPayloadDTO diffPayload = new DiffPayloadDTO();
        diffPayload.partVersionDecisions = partVersionDecisions;
        diffPayload.customerConflicts    = customerConflicts;
        diffPayload.orphanRows           = orphanRows;

        // 验证摘要：ParsedBasicData.requiredErrors → List<String>
        List<String> errorMessages = data.requiredErrors.stream()
                .map(e -> "[" + e.bvCode + "] 第" + e.rowNum + "行 " + e.sheetName + ": " + e.message)
                .toList();
        DiffPayloadDTO.ValidationSummary vs = new DiffPayloadDTO.ValidationSummary();
        vs.hasErrors = !errorMessages.isEmpty();
        vs.errors    = errorMessages;
        vs.warnings  = java.util.Collections.emptyList();
        diffPayload.validation = vs;

        LOG.infof("V6 upload: session=%s partVersions=%d conflicts=%d orphans=%d errors=%d",
                session.id, partVersionDecisions.size(), customerConflicts.size(),
                orphanRows.size(), errorMessages.size());

        UploadResultDTO result = new UploadResultDTO();
        result.sessionId   = session.id;
        result.diffPayload = diffPayload;
        return result;
    }

    // ── updateDecisions ───────────────────────────────────────────────────────

    /**
     * Step 2 UpdateDecisions：批量 upsert import_session_decision（幂等）。
     * 前端 debounce 500ms 后发送，可携带多条。
     */
    @Transactional
    public void updateDecisions(UUID sessionId, DecisionUpdateRequest req) {
        requireSession(sessionId, "PENDING");

        if (req.decisions == null || req.decisions.isEmpty()) return;

        for (DecisionUpdateRequest.DecisionEntry entry : req.decisions) {
            if (entry.decisionType == null || entry.decisionKey == null) continue;
            persistDecision(sessionId, entry.decisionType, entry.decisionKey,
                    entry.decisionValueJson);
        }
        LOG.infof("V6 updateDecisions: session=%s updated %d decisions",
                sessionId, req.decisions.size());
    }

    // ── commit ────────────────────────────────────────────────────────────────

    /**
     * Step 3 Commit：按决策 merge staging → mat_* → 建报价单 → 清 staging → session=COMMITTED。
     *
     * @param sessionId  import_session.id
     * @param req        报价单创建参数（name 必填）
     * @param userId     操作人 ID
     * @return CommitResult 含新建的 quotationId + sessionId
     */
    @Transactional
    public CommitResult commit(UUID sessionId, CommitRequest req, UUID userId) {
        ImportSession session = requireSession(sessionId, "PENDING");

        // 1. 加载所有 PART_VERSION 决策
        @SuppressWarnings("unchecked")
        List<ImportSessionDecision> partVersionDecisions = ((List<Object[]>) em.createNativeQuery(
                "SELECT import_session_id, decision_type, decision_key, decision_value::text " +
                "FROM import_session_decision " +
                "WHERE import_session_id = :sid AND decision_type = 'PART_VERSION'")
                .setParameter("sid", sessionId)
                .getResultList())
                .stream()
                .map(r -> {
                    ImportSessionDecision d = new ImportSessionDecision();
                    d.importSessionId = (UUID) r[0];
                    d.decisionType    = (String) r[1];
                    d.decisionKey     = (String) r[2];
                    d.decisionValue   = (String) r[3];
                    return d;
                })
                .toList();

        // 1.5 先创建 ImportRecord 拿到 importRecordId（StagingMerger 写入正式表时需要）
        //     业务用途：CustomerPartCandidateService 在前端 autoPopulate 流程中按
        //     import_record_id 过滤"本次导入涉及的料号"，避免拉客户全部历史 mapping
        //     (参见 AP-23 / CustomerPartCandidateService.java 注释)
        com.cpq.importexcel.entity.ImportRecord importRecord = new com.cpq.importexcel.entity.ImportRecord();
        importRecord.customerId = session.customerId;
        importRecord.importedBy = userId != null ? userId : session.customerId;  // userId 缺省时用 customerId 占位
        importRecord.originalFileName = session.sourceExcel != null ? session.sourceExcel : "v6-import.xlsx";
        importRecord.importStatus = "SUCCESS";
        importRecord.totalRows = partVersionDecisions.size();
        importRecord.matchedRows = 0;  // commit 后续会按真实写入数填充
        importRecord.persist();
        em.flush();  // 让 import_record 行立即落库（StagingMerger 用 JDBC 写 FK 要看到）
        UUID importRecordId = importRecord.id;
        LOG.infof("V6 commit: created import_record=%s for session=%s", importRecordId, sessionId);

        // 2. 合并 staging → mat_*（BUMP/NEW 写正式表，NO_BUMP 跳过）
        Map<String, Integer> appliedVersions = stagingMerger.applyPartVersionDecisions(
                sessionId, partVersionDecisions, userId, session.sourceExcel, importRecordId);

        // 3. 更新每条决策的 appliedVersion（写回 JSONB）
        for (Map.Entry<String, Integer> entry : appliedVersions.entrySet()) {
            // Hibernate native 查询不识别 PG 的 `:v::int` 类型强转（误解为参数名），
            // 改用 SQL 标准 `cast(:v as integer)` 写法。
            em.createNativeQuery(
                    "UPDATE import_session_decision " +
                    "SET decision_value = decision_value || jsonb_build_object('appliedVersion', cast(:v as integer)) " +
                    "WHERE import_session_id = :sid AND decision_type = 'PART_VERSION' AND decision_key = :k")
                    .setParameter("v", entry.getValue())
                    .setParameter("sid", sessionId)
                    .setParameter("k", entry.getKey())
                    .executeUpdate();
        }

        // 4. 建报价单
        CreateQuotationRequest createReq = new CreateQuotationRequest();
        createReq.customerId         = session.customerId;
        createReq.name               = req.name;
        createReq.customerTemplateId = req.customerTemplateId;
        createReq.costingTemplateId  = req.costingTemplateId;

        QuotationDTO quotationDTO = quotationService.create(createReq, userId);
        UUID quotationId = quotationDTO.id;
        LOG.infof("V6 commit: session=%s → quotation=%s", sessionId, quotationId);

        // 把 quotation_id 回写到 import_record 建立双向追溯
        importRecord.quotationId = quotationId;
        importRecord.matchedRows = appliedVersions.size();
        importRecord.persist();

        // 5. 清 staging（主动清除，session 仍保留作审计）
        stagingMerger.clearStaging(sessionId);

        // 6. session 状态 → COMMITTED
        em.createNativeQuery(
                "UPDATE import_session SET status = 'COMMITTED', committed_at = now() WHERE id = :sid")
                .setParameter("sid", sessionId)
                .executeUpdate();

        return new CommitResult(quotationId, sessionId, importRecordId);
    }

    // ── cancel ────────────────────────────────────────────────────────────────

    /**
     * 取消/放弃当前 session。
     * staging 数据通过 import_session CASCADE DELETE 清除（不必显式清）。
     */
    @Transactional
    public void cancel(UUID sessionId) {
        ImportSession session = ImportSession.findById(sessionId);
        if (session == null) {
            throw new BusinessException(404, "import_session 不存在: " + sessionId);
        }
        if ("COMMITTED".equals(session.status)) {
            throw new BusinessException(400, "已 commit 的 session 不能取消");
        }
        em.createNativeQuery(
                "UPDATE import_session SET status = 'CANCELLED' WHERE id = :sid")
                .setParameter("sid", sessionId)
                .executeUpdate();
        LOG.infof("V6 cancel: session=%s → CANCELLED", sessionId);
    }

    // ── 辅助方法 ──────────────────────────────────────────────────────────────

    /**
     * 加载并校验 session，要求状态为 requiredStatus。
     */
    private ImportSession requireSession(UUID sessionId, String requiredStatus) {
        ImportSession session = ImportSession.findById(sessionId);
        if (session == null) {
            throw new BusinessException(404, "import_session 不存在: " + sessionId);
        }
        if (!requiredStatus.equals(session.status)) {
            throw new BusinessException(400,
                    "import_session 状态非法：期望 " + requiredStatus + "，实际 " + session.status);
        }
        if (session.expiresAt != null && session.expiresAt.isBefore(OffsetDateTime.now())) {
            throw new BusinessException(400, "import_session 已过期（24h TTL），请重新上传");
        }
        return session;
    }

    /**
     * 持久化（upsert）一条 import_session_decision 记录。
     * 采用 INSERT ON CONFLICT DO UPDATE 实现幂等 upsert。
     */
    private void persistDecision(UUID sessionId, String type, String key, String valueJson) {
        em.createNativeQuery(
                "INSERT INTO import_session_decision " +
                "  (import_session_id, decision_type, decision_key, decision_value) " +
                "VALUES (:sid, :t, :k, CAST(:v AS jsonb)) " +
                "ON CONFLICT (import_session_id, decision_type, decision_key) " +
                "DO UPDATE SET decision_value = CAST(EXCLUDED.decision_value AS jsonb)")
                .setParameter("sid", sessionId)
                .setParameter("t", type)
                .setParameter("k", key)
                .setParameter("v", valueJson)
                .executeUpdate();
    }

    /**
     * 构建 PART_VERSION 决策的初始 JSON 字符串。
     */
    private String buildPartVersionDecisionJson(String action, Integer currentVersion,
                                                  Integer suggestedVersion) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"action\":\"").append(action).append("\"");
        if (currentVersion != null) {
            sb.append(",\"currentVersion\":").append(currentVersion);
        }
        if (suggestedVersion != null) {
            sb.append(",\"suggestedVersion\":").append(suggestedVersion);
        }
        sb.append("}");
        return sb.toString();
    }
}
