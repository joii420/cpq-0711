package com.cpq.common.exception;

/**
 * task-260901 B-3b：保存草稿的乐观并发冲突 —— HTTP 409 + {@code reason=STALE_VERSION}。
 *
 * <p>触发条件：请求携带的 {@code baseVersion} ≠ 库中 {@code quotation.user_data_version}，
 * 即「这张单在你打开之后被别人改过」。契约见 {@code api.md §1.4}：
 *
 * <pre>{@code
 * { "code": 409, "message": "这张报价单已被他人修改",
 *   "data": { "reason": "STALE_VERSION", "currentVersion": 45 } }
 * }</pre>
 *
 * <p>用户裁决（2026-09-01）：冲突时<b>强制刷新</b>——前端弹窗只给「刷新页面」一个按钮，不做行级
 * 差异比对与合并 UI（已登记 BACKLOG）。所以这里只需要把当前版本号带回去，不需要差异清单。
 *
 * <p>形态照既有先例 {@link ReconcilePendingException}（同为 409 + reason 码）。
 */
public class StaleVersionException extends BusinessException {

    public static final String REASON = "STALE_VERSION";

    private final int currentVersion;

    public StaleVersionException(int currentVersion) {
        super(409, "这张报价单已被他人修改");
        this.currentVersion = currentVersion;
    }

    public int getCurrentVersion() { return currentVersion; }
}
