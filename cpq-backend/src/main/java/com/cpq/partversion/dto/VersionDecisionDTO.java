package com.cpq.partversion.dto;

import java.util.List;
import java.util.Map;

/**
 * 三路判定结果.
 * <p>action 含义:
 * - NO_CHANGE: 新数据指纹与当前激活版本一致, 无需升版
 * - REVERT_TO_HISTORICAL: 新数据指纹命中某个旧版本, 应切换激活版本到该旧版本 (不升版)
 * - NEW_VERSION: 新数据与所有历史版本均不同, 建议升版到 proposedVersion = currentVersion + 1
 */
public record VersionDecisionDTO(
        Action action,
        int currentVersion,
        int proposedVersion,
        String matchedHash,
        Map<String, DiffSummary> diffByTable,
        List<Integer> allHistoricalVersions
) {
    public enum Action { NO_CHANGE, REVERT_TO_HISTORICAL, NEW_VERSION }
}
