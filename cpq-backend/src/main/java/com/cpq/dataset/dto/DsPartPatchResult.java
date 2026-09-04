package com.cpq.dataset.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code PUT /dataset/{dataset}/parts/{axisValue}} 的响应（task-260902 · B-20 · D-31 · AC-65）。
 *
 * <p>{@code updated} 回显<b>实际写进库的那几列</b>（键 = 列名，值 = 落库值）——
 * 前端据此回填单元格，不必再发一次 GET。
 *
 * <p>🚫 <b>没有</b> {@code versionNo}：免版本表本就没有版本号，
 * 端点也<b>不接升版逻辑</b>（AC-65 ⑤ 专门反向验这条）。多给一个字段会诱导前端去做版本比对。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class DsPartPatchResult {

    public String dataset;
    public String axisValue;
    /** 实际更新的列 → 落库值（null 表示被显式置空）。 */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public Map<String, Object> updated = new LinkedHashMap<>();
    /** 本次写入后的 {@code updated_at}。 */
    public OffsetDateTime updatedAt;
    /** 行级来源，<b>保持原值</b>（AC-65 ④：改一列不把 IMPORT 翻成 MANUAL）。 */
    public String source;
}
