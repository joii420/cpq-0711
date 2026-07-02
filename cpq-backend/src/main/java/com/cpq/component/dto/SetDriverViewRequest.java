package com.cpq.component.dto;

/** PUT /components/{id}/driver-view 入参：sqlViewName=null/空 表示清空驱动。 */
public class SetDriverViewRequest {
    /** 本组件 SQL 视图名（不含 $ 前缀）；null 或空串表示取消驱动。 */
    public String sqlViewName;
}
