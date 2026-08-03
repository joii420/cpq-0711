package com.cpq.priceadjust.dto;

import java.util.List;

/**
 * api.md §1.5 响应体（前端 {@code ElementsMatrixResponse}）。分页只分元素行，版本列固定
 * （🔒 必须一次 pivot 查完，禁止逐元素查 10 次——性能硬约束 §11.2.4）。
 */
public class ElementsMatrixResponse {
    public List<VersionColumnDTO> versionColumns;
    public List<ElementRowDTO> content;
    public int page;
    public int size;
    public long totalElements;
    public int totalPages;
}
