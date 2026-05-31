package com.cpq.basicdata.v6.dto;

import java.math.BigDecimal;

/**
 * V6 工序主数据 新建/编辑 请求体。
 *
 * <p>processNo 为业务唯一键: 新建时必填且唯一; 编辑时锁定(服务端忽略该字段的改动)。
 */
public class ProcessMasterUpsertRequest {
    /** 工序编号(业务唯一键)。新建必填; 编辑时被忽略(锁定)。 */
    public String processNo;
    /** 工序名称(必填)。 */
    public String processName;
    /** 工序分类。 */
    public String processCategory;
    /** 是否外协(true=外协, false=自制)。 */
    public Boolean isOutsource;
    /** 标准货币。 */
    public String standardCurrency;
    /** 标准单位。 */
    public String standardUnit;
    /** 默认不良率。 */
    public BigDecimal defaultDefectRate;
}
