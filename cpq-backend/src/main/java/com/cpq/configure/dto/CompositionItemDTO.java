package com.cpq.configure.dto;

/**
 * 材质元素组成项（task-260901，api.md §1 CompositionItem）。
 * 矩阵列的权威来源：列 = 元素组成、列顺序 = sortOrder。
 */
public class CompositionItemDTO {
    /** '10001' —— 权威元素链 */
    public String elementNo;
    /** 'Ag' —— 服务端从 element 主表回填的快照 */
    public String elementCode;
    /** '银' */
    public String elementName;
    public int sortOrder;

    public CompositionItemDTO() {}

    public CompositionItemDTO(String elementNo, String elementCode, String elementName, int sortOrder) {
        this.elementNo = elementNo;
        this.elementCode = elementCode;
        this.elementName = elementName;
        this.sortOrder = sortOrder;
    }
}
