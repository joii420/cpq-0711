package com.cpq.priceadjust.dto;

import java.time.LocalDate;
import java.util.UUID;

/** api.md §1.5 元素矩阵 pivot 列头（前端 {@code VersionColumnDTO}）。 */
public class VersionColumnDTO {
    public UUID versionId;
    public String versionNo;
    public String status;
    public LocalDate baseDate;
}
