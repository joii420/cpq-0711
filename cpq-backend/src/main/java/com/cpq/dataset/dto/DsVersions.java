package com.cpq.dataset.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.OffsetDateTime;
import java.util.List;

/** api.md §6 版本列表（倒序，最新在前）。历史版本读自 {@code <表>_history}。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class DsVersions {

    public List<VersionInfo> versions;

    public DsVersions(List<VersionInfo> versions) {
        this.versions = versions;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static final class VersionInfo {
        public int versionNo;
        public boolean isLatest;
        public int rowCount;
        /** 当前版恒 null；历史版为归档时刻。 */
        public OffsetDateTime archivedAt;
        public OffsetDateTime updatedAt;
        /** 用户名（{@code updated_by} 存的是 user UUID，服务端 JOIN "user" 表换成 username）。 */
        public String updatedBy;
        public String source;
        public String archivedBy;
        public String archiveReason;
    }
}
