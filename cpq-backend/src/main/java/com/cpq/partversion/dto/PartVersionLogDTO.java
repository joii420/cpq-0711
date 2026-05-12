package com.cpq.partversion.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/** mat_part_version_log 一行 DTO. diffSummaryJson 是 JSONB 列的原始字符串 (S3 前端按需解析). */
public record PartVersionLogDTO(
        String customerProductNo,
        String hfPartNo,
        int version,
        String contentHash,
        String diffSummaryJson,
        String sourceExcel,
        UUID sourceImportId,
        OffsetDateTime createdAt,
        UUID createdBy
) {}
