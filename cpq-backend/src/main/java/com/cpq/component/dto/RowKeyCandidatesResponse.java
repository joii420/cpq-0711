package com.cpq.component.dto;

import java.util.List;

/** POST /{id}/row-key-candidates 响应体。 */
public class RowKeyCandidatesResponse {

    public List<Candidate> candidates;

    public RowKeyCandidatesResponse() {}

    public RowKeyCandidatesResponse(List<Candidate> candidates) {
        this.candidates = candidates;
    }

    public static class Candidate {
        /** 字段名（前端按此匹配勾选框所属字段）。 */
        public String fieldName;
        /** 字段显示名（当前与 fieldName 同；预留 label 区分）。 */
        public String displayName;
        /** 反查出的 driver 真实列名（leaf）；不可解析时为 null。 */
        public String resolvedColumn;
        /** 是否可作行键（true 才允许勾选）。 */
        public boolean eligible;
        /** 不可勾选原因（eligible=false 时给前端 hover 提示）；可勾选时为 null。 */
        public String reason;
        /** 行键来源："driver"（取自 driver 列） / "input"（取自手填输入字段）；eligible=false 时为 null。 */
        public String source;
    }
}
