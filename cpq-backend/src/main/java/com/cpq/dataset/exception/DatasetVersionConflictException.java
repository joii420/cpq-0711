package com.cpq.dataset.exception;

import com.cpq.common.exception.BusinessException;

/**
 * api.md §7 保存冲突（AC-41）：{@code baseVersion} 与库中当前版本不一致。
 *
 * <p>🚨 抛出点必须在 {@code @Transactional} 方法内、且在<b>取到该组的 advisory lock 之后</b>
 * 读当前版本再比对 —— 否则「检查」与「使用」之间存在竞态窗口，两个并发保存会双双通过
 * （backtask B-10「校验必须在同一事务内读当前版本」）。
 *
 * <p>响应体由 {@code DatasetMaintenanceResource} 就地构造（{@code data.currentVersion} /
 * {@code data.baseVersion}），<b>不改</b> {@code GlobalExceptionMapper}（闸门 A0 · D-13：现有代码一行不改）。
 */
public class DatasetVersionConflictException extends BusinessException {

    private final Integer currentVersion;
    private final Integer baseVersion;

    public DatasetVersionConflictException(Integer currentVersion, Integer baseVersion) {
        super(409, "数据已被他人更新至 v" + currentVersion + "，请刷新后重试");
        this.currentVersion = currentVersion;
        this.baseVersion = baseVersion;
    }

    public Integer getCurrentVersion() { return currentVersion; }

    public Integer getBaseVersion() { return baseVersion; }
}
