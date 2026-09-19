package com.cpq.priceadjust.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * task-0729 更新批次明细：报价单 × 料号。三种非成功态语义见 api.md §3.3：
 * FAILED（数据问题，含 SUBTOTAL_MISMATCH）/ CONFLICT（row_version 不匹配）/
 * STALE（所属版本已被取代，终态不可重试）。
 */
@Entity
@Table(name = "material_price_update_job_item")
public class MaterialPriceUpdateJobItem extends PanacheEntityBase {

    public static final String WAITING = "WAITING";
    public static final String RUNNING = "RUNNING";
    public static final String SUCCESS = "SUCCESS";
    public static final String FAILED = "FAILED";
    public static final String CONFLICT = "CONFLICT";
    public static final String STALE = "STALE";
    /**
     * repair-0807 FR-4：独立终态——该单未被更新，且重试不会有不同结果（无价格承载组件 /
     * 补建冻结结构失败）。🔒 与 STALE 同属"不可重试"，但 {@link #errorCode} 保持 null——
     * SKIPPED 是设计内的"不处理"，不是需要人工介入的异常（api.md §2.2）。
     */
    public static final String SKIPPED = "SKIPPED";

    @Id
    @GeneratedValue
    public UUID id;

    @Column(name = "job_id", nullable = false)
    public UUID jobId;

    @Column(name = "quotation_id", nullable = false)
    public UUID quotationId;

    @Column(name = "material_no", nullable = false, length = 50)
    public String materialNo;

    @Column(name = "line_item_id")
    public UUID lineItemId;

    @Column(name = "status", nullable = false, length = 20)
    public String status = WAITING;

    @Column(name = "error_code", length = 50)
    public String errorCode;

    @Column(name = "error_message", columnDefinition = "TEXT")
    public String errorMessage;

    @Column(name = "diff_value", precision = 26, scale = 12)
    public BigDecimal diffValue;

    /**
     * task-0729 方向3 T2（V381）：L3 口径守卫告警码（如 {@code SUBTOTAL_MISMATCH}）。
     *
     * <p>🔒 <b>刻意不复用 {@link #errorCode}</b>：本类既有语义是「{@code errorCode} 非空 = 非成功态」
     * （见类注释三种非成功态）。告警行的 {@link #status} 仍是 {@code SUCCESS}，若复用就会出现
     * 「{@code status=SUCCESS} 却带 {@code errorCode}」的行，让每个消费点都要重新判断「这是真失败
     * 还是告警」，屏 7 的「可重试」判定会被直接带偏。差异值复用既有 {@link #diffValue}（语义相同）。
     */
    @Column(name = "warn_code", length = 50)
    public String warnCode;

    @Column(name = "warn_message", columnDefinition = "TEXT")
    public String warnMessage;

    @Column(name = "retry_count", nullable = false)
    public Integer retryCount = 0;

    @Column(name = "created_at", nullable = false)
    public OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at", nullable = false)
    public OffsetDateTime updatedAt = OffsetDateTime.now();

    public static List<MaterialPriceUpdateJobItem> listByJob(UUID jobId) {
        return list("jobId", jobId);
    }

    /**
     * repair-260918 B-5：明细开始执行前置 RUNNING（调用方须在独立短事务里调用并提交，外面才看得见）。
     * STALE 是终态，不动。
     */
    public static int markRunning(UUID itemId) {
        return update("status = ?1, updatedAt = ?2 where id = ?3 and status <> ?4",
                RUNNING, OffsetDateTime.now(), itemId, STALE);
    }

    /**
     * repair-260918 B-10 / B-11：把仍为 WAITING / RUNNING 的明细一次性置 FAILED（一条批量 UPDATE）。
     *
     * @param jobIds   批次范围（必填）
     * @param itemIds  非 null 时只动这些明细（执行入口兜底只收自己这次负责的那批，不误伤并发的单条重试）
     */
    public static int failUnfinished(java.util.Collection<UUID> jobIds, java.util.Collection<UUID> itemIds,
                                     String errorCode, String errorMessage) {
        if (jobIds == null || jobIds.isEmpty()) return 0;
        if (itemIds == null) {
            return update("status = ?1, errorCode = ?2, errorMessage = ?3, updatedAt = ?4 " +
                    "where jobId in ?5 and status in (?6, ?7)",
                FAILED, errorCode, errorMessage, OffsetDateTime.now(), jobIds, WAITING, RUNNING);
        }
        if (itemIds.isEmpty()) return 0;
        return update("status = ?1, errorCode = ?2, errorMessage = ?3, updatedAt = ?4 " +
                "where jobId in ?5 and status in (?6, ?7) and id in ?8",
            FAILED, errorCode, errorMessage, OffsetDateTime.now(), jobIds, WAITING, RUNNING, itemIds);
    }

    /** repair-260918 B-4⑤：本组已 SUCCESS 的明细因写本期版本记录失败改 FAILED（一条批量 UPDATE）。 */
    public static int markRevisionWriteFailed(java.util.Collection<UUID> itemIds, String errorCode, String errorMessage) {
        if (itemIds == null || itemIds.isEmpty()) return 0;
        return update("status = ?1, errorCode = ?2, errorMessage = ?3, updatedAt = ?4 where id in ?5 and status = ?6",
            FAILED, errorCode, errorMessage, OffsetDateTime.now(), itemIds, SUCCESS);
    }

    /**
     * repair-260918 B-5（AC-9 / AC-16 补充）：各批次按状态实时汇总的明细数，一条 {@code GROUP BY job_id, status}
     * （🚫 逐批次查）。返回 {@code jobId → (status → count)}；没有明细的批次不在 map 里。
     */
    public static java.util.Map<UUID, java.util.Map<String, Integer>> countByStatusForJobs(java.util.Collection<UUID> jobIds) {
        java.util.Map<UUID, java.util.Map<String, Integer>> out = new java.util.HashMap<>();
        if (jobIds == null || jobIds.isEmpty()) return out;
        List<Object[]> rows = getEntityManager().createQuery(
                "select i.jobId, i.status, count(i) from MaterialPriceUpdateJobItem i " +
                "where i.jobId in :ids group by i.jobId, i.status", Object[].class)
            .setParameter("ids", jobIds)
            .getResultList();
        for (Object[] r : rows) { // pure in-memory distribution
            out.computeIfAbsent((UUID) r[0], k -> new java.util.HashMap<>())
               .put((String) r[1], ((Number) r[2]).intValue());
        }
        return out;
    }

    /**
     * job 名下<b>所有非 SUCCESS 项</b>（WAITING/RUNNING/FAILED/CONFLICT）一律置 STALE 终态
     * （§11.6.3.2，版本被新版取代时）。
     *
     * <p>🚨 <b>2026-08-05 修复（验收 #61）</b>：原集合只有 {@code (WAITING, RUNNING)}，漏了
     * {@code FAILED}/{@code CONFLICT} —— 旧批次里真实失败的项在版本被取代后仍是 FAILED，屏 7
     * 上照样可点重试，而 {@code PriceAdjustJobExecutionService#executeItem} 用的是
     * {@code job.versionId}（<b>已作废的旧版</b>）→ 把作废版本的价格写进活单，<b>且绕开新版
     * 待办池的审核</b>。STALE 的三层拦截（{@code PriceAdjustJobResource#retryJobItem} 409 /
     * {@code retryJobItem} 409 / {@code executeItem} 早返）本来就都在，只是这批项从来没被标成
     * STALE，拦截器一次都没触发过 —— 扩集合后三层自动生效，不需要改第二处。
     *
     * <p>🔒 <b>SET 子句只改 {@code status} + {@code updatedAt}，不碰 {@code errorCode} /
     * {@code errorMessage}</b> —— 失败留痕完整保留（STALE 的语义是"已失效"，不是"没失败过"）。
     * <br>🔒 {@code SUCCESS} 不在集合内：已成功的项是既成事实，不回滚（同裁决 27 精神）。
     * jobIds 由调用方（PriceAdjustVersionGenerationService）先按 versionId 查出 MaterialPriceUpdateJob
     * 再传入，避免这里跨实体写子查询。
     *
     * <p>🔒 2026-08-03 修复（回归阻断 #21/#64）：原写法 {@code updatedAt = now()} 用 HQL 函数
     * {@code now()}，Hibernate 6 对该函数返回类型推断为 {@code java.lang.Object}，赋值给
     * {@code OffsetDateTime} 字段时语义校验直接拒绝（{@code SemanticException: Cannot assign
     * expression of type 'java.lang.Object' to target path 'alias_0.updatedAt'}）。此前两次
     * 测试"成功"是因为当时待 supersede 版本名下还没有 job（{@code jobIds} 判空提前 return，
     * 本行 HQL 从未被真正编译执行）——不是本次同步引入的新 bug，是这条从未被真实数据路径触达过
     * 的既有代码首次被执行时暴露的语义错误。改为绑定真实 Java {@link OffsetDateTime#now()} 参数，
     * 不再依赖 HQL 端函数推断类型。
     */
    public static int staleAllUnfinishedByJobIds(List<UUID> jobIds) {
        if (jobIds == null || jobIds.isEmpty()) return 0;
        return update("status = ?1, updatedAt = ?2 where status in (?3, ?4, ?5, ?6) and jobId in ?7",
                STALE, OffsetDateTime.now(), WAITING, RUNNING, FAILED, CONFLICT, jobIds);
    }
}
