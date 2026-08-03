package com.cpq.priceadjust.dto;

import java.util.List;

/** api.md §1.5 参与调价元素矩阵行（前端 {@code ElementRowDTO}）。 */
public class ElementRowDTO {
    public String elementCode;
    public String elementName;
    public String elementNo;
    /** 元素主表是否启用；false 仍照常参与调价，前端只负责标「已停用」，禁止服务端过滤（§11.2.1）。 */
    public boolean elementEnabled;
    public boolean selected;
    /** 与 {@code ElementsMatrixResponse.versionColumns} 逐位对齐，长度相同。 */
    public List<ElementPriceCellDTO> prices;
}
