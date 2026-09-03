package com.cpq.dataset.dto;

import java.util.List;
import java.util.Map;

/**
 * api.md §7 保存请求。
 *
 * <p>{@code rows} 是该轴值该 sheet 的<b>整组全量</b>（不是增量）；删行 = 不出现在数组里。
 * 🚫 前端不传 {@code role=NAME} 的列与 {@code row_fingerprint}（后端计算）；
 * 轴列由 path 的 {@code axisValue} 注入，前端传了也会被服务端覆盖。
 */
public final class DsSaveRowsRequest {

    /**
     * 前端当前展示的版本号，<b>必传</b>，用于乐观锁（AC-41）。
     * 该轴值该 sheet 从未有过数据时传 {@code null}（对应 CREATED）。
     */
    public Integer baseVersion;

    public List<Map<String, Object>> rows;
}
