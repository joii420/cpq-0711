package com.cpq.dataset.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * api.md §5 行数据。
 *
 * <p>{@code rows} 为动态列 Map（DB 列名 → 值），含 {@code row_fingerprint}
 * 与由后端 JOIN 主数据带出的 {@code role=NAME} 只读列。
 * 数值列以<b>字符串</b>回传（保留库中 scale，避免 JS 精度丢失）。
 *
 * <p>请求历史版本时 {@code isLatest=false} + {@code readOnly=true}（AC-29）；
 * 该轴值从未有过数据时 {@code rows=[]} + {@code versionNo=null}（AC-32，🚫 不抛 404）。
 */
// ⚠️ 刻意用 ALWAYS 而不是 NON_NULL：api.md §5 的空态形状是 {"versionNo": null, "rows": []}。
// NON_NULL 会把 versionNo / source 两个键<b>整个抹掉</b>，前端拿到的是 undefined 而不是 null ——
// 若前端用 `'versionNo' in data` 或对键存在性做判断就会误判（AC-32 的空态渲染直接受影响）。
@JsonInclude(JsonInclude.Include.ALWAYS)
public final class DsRows {

    public Integer versionNo;
    public boolean isLatest;
    public boolean readOnly;
    public String source;
    public List<Map<String, Object>> rows;
}
