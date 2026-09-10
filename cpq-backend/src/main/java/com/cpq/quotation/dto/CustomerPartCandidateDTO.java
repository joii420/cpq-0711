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

    /** 生产料号详情——供选品候选行的「生产料号」浮层用；缺失（未绑生产料号）时为 null。
     *  task-260910 · B-4 换源：与报价单卡片走**同一个** {@code ProductionPartInfoService}
     *  （ds_quote_material → ds_cost_basic_material ∪ ds_cost_detail_material）。
     *  旧源 internal_material（全表 0 行）已停用。
     */
    public HfPartInfo hfPartInfo;

    /** ⚠️ 契约变更（task-260910）：删 statusCode，加 oldMaterialNo。partNo = 生产料号。 */
    public static class HfPartInfo {
        /** 生产料号 ds_quote_material.production_no */
        public String partNo;
        public String partName;
        public String specification;
        public String sizeInfo;
        public String oldMaterialNo;
    }
}
