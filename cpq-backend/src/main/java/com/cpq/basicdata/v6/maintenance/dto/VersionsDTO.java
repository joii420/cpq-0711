package com.cpq.basicdata.v6.maintenance.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.OffsetDateTime;
import java.util.List;

/** §5 版本列表（版本切换下拉 + 操作留痕 C11）。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class VersionsDTO {

    public List<VersionInfo> versions;

    public VersionsDTO(List<VersionInfo> versions) {
        this.versions = versions;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static final class VersionInfo {
        public String version;
        public boolean isCurrent;
        public String source;          // IMPORT / MANUAL
        public String operator;        // updated_by → 用户名；null → "系统导入"
        public OffsetDateTime operatedAt;
    }
}
