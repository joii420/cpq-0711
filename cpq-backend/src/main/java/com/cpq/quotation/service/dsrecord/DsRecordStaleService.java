package com.cpq.quotation.service.dsrecord;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 「{@code _record} 快照过期」标记（task-260907 第二段 · D-35，用户 2026-09-07 裁决）。
 *
 * <h3>要解决的是哪种静默</h3>
 * {@code saveDraft} 里写 {@code _record} 的那段包了 try/catch —— 这一条是对的，
 * {@code _record} 是<b>派生数据</b>，它写失败不该让用户的草稿保存整单回滚。
 * <b>但「吞掉」走过头了</b>，后果链是：
 * <pre>
 *   保存成功 → 快照没更新（只有一条没人看的 WARN）→ 提交 → 核价通过
 *   → 预览从 _record 读，读到**过期或缺失**的数据 → 财务照着确认
 *   → **按错的数据回填主表**
 * </pre>
 * 🚨 这与 {@code AP-60} 判据四是同一种形态：<b>预览在撒谎，而且撒得很有说服力</b>。
 *
 * <h3>做法：让「过期」可见，而不是让保存失败</h3>
 * <ol>
 *   <li>写失败 → {@link #markStale} 留一条可查的标记（🚫 <b>仍然不回滚草稿保存</b>）；</li>
 *   <li>下一次 {@code _record} 写成功 → {@link #clearStale} 置 {@code cleared_at}（不删行，留痕）；</li>
 *   <li>预览读到未清除的标记 → 在 {@code dsBackfill} 里<b>显式报出来</b>，
 *       复用 {@code nonParticipating} 那套「不许静默」的口径。</li>
 * </ol>
 *
 * <h3>🚨 两个不可变通的实现细节</h3>
 * <ul>
 *   <li>{@link #markStale} 必须 {@code REQUIRES_NEW}：它是在 {@code catch} 块里跑的，
 *       此刻当前 JTA 事务很可能已因那个异常被标记 rollback-only
 *       ⇒ 挂在原事务上写标记 <b>必然一起没</b>，等于白加；</li>
 *   <li>{@link #markStale} 自身<b>吞掉一切异常</b>：它是「让静默可见」的补救措施，
 *       🚫 绝不能因为它自己失败而把用户的草稿保存也带崩 —— 那就本末倒置了。</li>
 * </ul>
 *
 * <h3>迁移未落库时的降级</h3>
 * 标记表随 {@code V___task260907_ds_quote_record_stale.sql} 落库。表还不存在时，
 * 本类三个方法一律安全降级成 no-op（探测结果进程级缓存），
 * <b>不让预览 500</b> —— 代码可以先于 DDL 合入。
 */
@ApplicationScoped
public class DsRecordStaleService {

    private static final Logger LOG = Logger.getLogger(DsRecordStaleService.class);

    static final String TABLE = "ds_quote_record_stale";

    /** 写 {@code _record} 时抛异常。⚠️ 受 {@code reason varchar(32)} 约束，12 字符。 */
    public static final String REASON_WRITE_FAILED = "WRITE_FAILED";

    /** {@code detail} 截断长度 —— 只为排障，不进契约文案，别把整个堆栈灌进去。 */
    private static final int DETAIL_MAX = 500;

    @Inject EntityManager em;

    /** 标记表是否已落库。进程级缓存；DDL 变更需重启（与本项目既有缓存同约定）。 */
    private final AtomicReference<Boolean> tableReady = new AtomicReference<>();

    /** 一条未清除的过期标记。 */
    public record Stale(String reason, String detail, OffsetDateTime detectedAt) {}

    private boolean ready() {
        Boolean v = tableReady.get();
        if (v != null) return v;
        boolean exists;
        try {
            Object n = em.createNativeQuery(
                            "SELECT count(*) FROM information_schema.tables " +
                            "WHERE table_schema = 'public' AND table_name = :t")
                    .setParameter("t", TABLE).getSingleResult();
            exists = n != null && ((Number) n).intValue() > 0;
        } catch (RuntimeException e) {
            exists = false;
        }
        if (!exists) {
            LOG.warnf("[ds-record] 标记表 %s 尚未落库 ⇒ D-35「_record 写失败可见」暂不生效（写失败仍只有 WARN）。"
                    + "迁移见 db/migration-pending-260907/", TABLE);
        }
        tableReady.set(exists);
        return exists;
    }

    /**
     * 记一条「快照过期」标记。
     *
     * <p>🔒 {@code REQUIRES_NEW}：见类注释 —— 挂在原事务上会跟着一起回滚。
     * <p>🛡️ 自身吞异常：本方法失败只写一条 ERROR，🚫 绝不向上抛。
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void markStale(UUID quotationId, String reason, String detail, String operator) {
        if (quotationId == null || !ready()) return;
        try {
            String d = detail == null ? null
                    : (detail.length() > DETAIL_MAX ? detail.substring(0, DETAIL_MAX) : detail);
            // partial unique(quotation_id) WHERE cleared_at IS NULL ⇒ 已有未清除标记时不重复插，
            // 只把 detail/时间刷新成最近一次（用 ON CONFLICT 需要匹配 partial index 的谓词，
            // 这里改成「先清旧、再插新」两步，语义更直白且不依赖 partial index 的推断）。
            em.createNativeQuery("UPDATE " + TABLE + " SET cleared_at = :now"
                            + " WHERE quotation_id = :qid AND cleared_at IS NULL")
                    .setParameter("now", OffsetDateTime.now())
                    .setParameter("qid", quotationId)
                    .executeUpdate();
            em.createNativeQuery("INSERT INTO " + TABLE
                            + " (quotation_id, reason, detail, detected_by) VALUES (:qid, :r, :d, :by)")
                    .setParameter("qid", quotationId)
                    .setParameter("r", reason == null ? REASON_WRITE_FAILED : reason)
                    .setParameter("d", d)
                    .setParameter("by", operator)
                    .executeUpdate();
            LOG.warnf("[ds-record] quotation=%s 已登记「_record 快照过期」标记 reason=%s"
                    + "（草稿保存不回滚；预览会把它显式报给财务）", quotationId, reason);
        } catch (RuntimeException e) {
            // 🚫 不许向上抛：本方法是补救措施，不能反过来把用户的草稿保存带崩。
            LOG.errorf(e, "[ds-record] quotation=%s 登记过期标记失败 —— 这次的 _record 过期将只剩一条 WARN",
                    quotationId);
        }
    }

    /**
     * 清除该单未清除的标记（下一次 {@code _record} 写成功时调）。
     * <p>🚫 <b>不删行</b>，只置 {@code cleared_at} —— 「这张单曾经写失败过」本身是可追溯价值。
     * <p>随调用方事务（{@code MANDATORY}）：写成功与清标记必须同生共死。
     */
    @Transactional(Transactional.TxType.MANDATORY)
    public void clearStale(UUID quotationId) {
        if (quotationId == null || !ready()) return;
        int n = em.createNativeQuery("UPDATE " + TABLE + " SET cleared_at = :now"
                        + " WHERE quotation_id = :qid AND cleared_at IS NULL")
                .setParameter("now", OffsetDateTime.now())
                .setParameter("qid", quotationId)
                .executeUpdate();
        if (n > 0) {
            LOG.infof("[ds-record] quotation=%s 的「_record 快照过期」标记已清除（本次写入成功）", quotationId);
        }
    }

    /** 读该单未清除的标记；无标记 / 表未落库 → null。<b>1 条 SQL</b>。 */
    @Transactional(Transactional.TxType.SUPPORTS)
    public Stale find(UUID quotationId) {
        if (quotationId == null || !ready()) return null;
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                        "SELECT reason, detail, detected_at FROM " + TABLE
                                + " WHERE quotation_id = :qid AND cleared_at IS NULL"
                                + " ORDER BY detected_at DESC LIMIT 1")
                .setParameter("qid", quotationId).getResultList();
        if (rows.isEmpty()) return null;
        Object[] r = rows.get(0);
        return new Stale(r[0] == null ? null : String.valueOf(r[0]),
                r[1] == null ? null : String.valueOf(r[1]),
                r[2] instanceof OffsetDateTime odt ? odt : null);
    }
}
