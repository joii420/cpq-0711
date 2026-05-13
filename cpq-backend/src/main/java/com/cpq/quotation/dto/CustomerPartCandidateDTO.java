package com.cpq.quotation.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;

/**
 * 客户报价单可选料号候选项 — Step2 "批量从基础数据导入产品" 抽屉用。
 *
 * <p>来源:
 * <ol>
 *   <li>mat_customer_part_mapping(customer_id = X)— 该客户专属映射</li>
 *   <li>mat_part(part_no IN 上述映射的 hf_part_no 列表)— 取主档信息</li>
 * </ol>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CustomerPartCandidateDTO {

    /** 宏丰料号(part_no) */
    public String partNo;

    /** 料号名称 */
    public String partName;

    /** 单重 */
    public BigDecimal unitWeight;

    /** 重量单位(KG / G / PCS 等) */
    public String weightUnit;

    /** 客户料号映射:客户产品编号(若该客户有专属映射) */
    public String customerProductNo;

    /** 客户料号映射:客户料号名称 */
    public String customerPartName;

    /** 客户料号映射:客户图号 */
    public String customerDrawingNo;

    /** 客户料号映射:基础货币 */
    public String baseCurrency;

    /** 客户料号映射:报价货币 */
    public String quoteCurrency;

    /** 是否客户专属(true=有 mat_customer_part_mapping,false=只是全局料号) */
    public boolean customerSpecific;

    /** mat_customer_part_mapping.current_version — 自动加产品时透传到 line_item.part_version_locked,
     *  避免初次从 import 跳转过来时 partVersion 缺省导致 ImplicitJoinRewriter 不注入版本过滤. */
    public Integer currentVersion;

    /** 「生产料号管理」(internal_material) 视角的料号详情——供产品卡片右侧 popover 用。
     *  数据源：internal_material 按 material_no = part_no 反查；缺失时为 null。
     */
    public HfPartInfo hfPartInfo;

    public static class HfPartInfo {
        public String partNo;
        public String partName;
        public String specification;
        public String sizeInfo;
        public String statusCode;
    }
}
