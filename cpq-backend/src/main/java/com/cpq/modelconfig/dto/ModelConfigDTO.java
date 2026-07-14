package com.cpq.modelconfig.dto;

import com.cpq.modelconfig.entity.ModelConfig;

import java.time.OffsetDateTime;
import java.util.UUID;

/** 3D 模型配置响应 DTO（api.md §4.1）。 */
public class ModelConfigDTO {

    public UUID id;
    public String subjectType;
    public String subjectKey;
    /** 关联材质库/客户产品得到的中文名，批量查出（禁逐行查，见 ModelConfigService#enrichSubjectLabels）。 */
    public String subjectLabel;
    public Integer version;
    public Boolean isCurrent;
    public String label;
    public String glbUrl;
    public String thumbnailUrl;
    public Integer meshCount;
    public Integer vertices;
    public Integer sizeKb;
    public OffsetDateTime uploadedAt;
    public UUID uploadedBy;

    public static ModelConfigDTO from(ModelConfig m) {
        ModelConfigDTO dto = new ModelConfigDTO();
        dto.id = m.id;
        dto.subjectType = m.subjectType;
        dto.subjectKey = m.subjectKey;
        dto.version = m.version;
        dto.isCurrent = m.isCurrent;
        dto.label = m.label;
        dto.glbUrl = m.glbUrl;
        dto.thumbnailUrl = m.thumbnailUrl;
        dto.meshCount = m.meshCount;
        dto.vertices = m.vertices;
        dto.sizeKb = m.sizeKb;
        dto.uploadedAt = m.uploadedAt;
        dto.uploadedBy = m.uploadedBy;
        return dto;
    }
}
