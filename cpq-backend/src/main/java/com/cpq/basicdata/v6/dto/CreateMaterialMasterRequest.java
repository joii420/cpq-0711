package com.cpq.basicdata.v6.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * 创建 / 更新 V6 料号主数据请求体。
 *
 * <p>所有字段名与 {@link MaterialMasterDTO} 对齐(camelCase)。
 * material_no 是业务唯一键,创建后不能改(UI 编辑模式下置 disabled)。
 */
public class CreateMaterialMasterRequest {

    @NotBlank(message = "料号不能为空")
    @Size(max = 20, message = "料号长度不能超过 20")
    public String materialNo;

    @Size(max = 100, message = "名称长度不能超过 100")
    public String materialName;

    @Size(max = 100)
    public String specification;

    @Size(max = 100)
    public String dimension;

    @Size(max = 50)
    public String oldMaterialNo;

    /** 1.银点类 / 2.非银点类 / 组成件 / 边角料 */
    @Size(max = 50)
    public String materialType;

    /** 1.正常 / 2.回收料 */
    @Size(max = 50)
    public String usageProperty;

    public BigDecimal unitWeight;

    @Size(max = 20)
    public String standardUnit;
}
