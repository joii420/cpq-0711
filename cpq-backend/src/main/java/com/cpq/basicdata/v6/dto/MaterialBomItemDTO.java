package com.cpq.basicdata.v6.dto;

import com.cpq.basicdata.v6.entity.MaterialBomItem;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * V6 物料 BOM 子表 DTO（只读，44 字段 1:1 透传 material_bom_item）。
 */
public class MaterialBomItemDTO {

    // ── 主键 / 审计 ──────────────────────────────────────────────────────────
    public UUID id;
    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;
    public UUID createdBy;
    public UUID updatedBy;

    // ── 维度键（系统类型 / 客户 / 料号 / 特征） ────────────────────────────
    /** QUOTE / PRICING / BOTH */
    public String systemType;
    public String customerNo;
    public String materialNo;
    /** 特征码（可为空） */
    public String characteristic;

    // ── 项次 / 工序 ──────────────────────────────────────────────────────────
    public Integer seqNo;
    public String componentNo;
    public String partNo;
    public LocalDateTime effectiveDatetime;
    public LocalDateTime expireDatetime;
    public String operationNo;
    public String operationSeq;
    public Integer itemSeq;

    // ── 用量 ─────────────────────────────────────────────────────────────────
    public String issueUnit;
    public BigDecimal compositionQty;
    public BigDecimal baseQty;
    public String componentUsageType;
    public String featureMgmt;
    public BigDecimal upperLimitPct;
    public BigDecimal lowerLimitPct;

    // ── 损耗 ─────────────────────────────────────────────────────────────────
    public BigDecimal scrapBatch;
    public BigDecimal scrapRate;
    public BigDecimal fixedScrap;
    public String scrapRateType;

    // ── 仓位 ─────────────────────────────────────────────────────────────────
    public String issueLocation;
    public String issueStorage;

    // ── 选项 ─────────────────────────────────────────────────────────────────
    public String fasGroup;
    public String plugPosition;
    public String refRdCenter;
    public Boolean isOptional;
    public String woExpandOption;

    // ── 替代 ─────────────────────────────────────────────────────────────────
    public Boolean isPurchaseReplace;
    public BigDecimal componentLeadTime;
    public String mainSubstitute;
    public String attachedPart;
    public String ecnNo;

    // ── 公式 ─────────────────────────────────────────────────────────────────
    public Boolean useQtyFormula;
    public String qtyFormula;

    // ── 倒冲 / 客供 ──────────────────────────────────────────────────────────
    public Boolean isBackflush;
    public Boolean isCustomerSupply;
    public BigDecimal defectRate;
    public String calcType;

    // ── 回收 ─────────────────────────────────────────────────────────────────
    public BigDecimal recoveryDiscount;
    public String recoveryCurrency;
    public String recoveryUnit;

    public static MaterialBomItemDTO from(MaterialBomItem e) {
        if (e == null) return null;
        MaterialBomItemDTO d = new MaterialBomItemDTO();
        // 主键 / 审计
        d.id = e.id;
        d.createdAt = e.createdAt;
        d.updatedAt = e.updatedAt;
        d.createdBy = e.createdBy;
        d.updatedBy = e.updatedBy;
        // 维度键
        d.systemType = e.systemType;
        d.customerNo = e.customerNo;
        d.materialNo = e.materialNo;
        d.characteristic = e.characteristic;
        // 项次 / 工序
        d.seqNo = e.seqNo;
        d.componentNo = e.componentNo;
        d.partNo = e.partNo;
        d.effectiveDatetime = e.effectiveDatetime;
        d.expireDatetime = e.expireDatetime;
        d.operationNo = e.operationNo;
        d.operationSeq = e.operationSeq;
        d.itemSeq = e.itemSeq;
        // 用量
        d.issueUnit = e.issueUnit;
        d.compositionQty = e.compositionQty;
        d.baseQty = e.baseQty;
        d.componentUsageType = e.componentUsageType;
        d.featureMgmt = e.featureMgmt;
        d.upperLimitPct = e.upperLimitPct;
        d.lowerLimitPct = e.lowerLimitPct;
        // 损耗
        d.scrapBatch = e.scrapBatch;
        d.scrapRate = e.scrapRate;
        d.fixedScrap = e.fixedScrap;
        d.scrapRateType = e.scrapRateType;
        // 仓位
        d.issueLocation = e.issueLocation;
        d.issueStorage = e.issueStorage;
        // 选项
        d.fasGroup = e.fasGroup;
        d.plugPosition = e.plugPosition;
        d.refRdCenter = e.refRdCenter;
        d.isOptional = e.isOptional;
        d.woExpandOption = e.woExpandOption;
        // 替代
        d.isPurchaseReplace = e.isPurchaseReplace;
        d.componentLeadTime = e.componentLeadTime;
        d.mainSubstitute = e.mainSubstitute;
        d.attachedPart = e.attachedPart;
        d.ecnNo = e.ecnNo;
        // 公式
        d.useQtyFormula = e.useQtyFormula;
        d.qtyFormula = e.qtyFormula;
        // 倒冲 / 客供
        d.isBackflush = e.isBackflush;
        d.isCustomerSupply = e.isCustomerSupply;
        d.defectRate = e.defectRate;
        d.calcType = e.calcType;
        // 回收
        d.recoveryDiscount = e.recoveryDiscount;
        d.recoveryCurrency = e.recoveryCurrency;
        d.recoveryUnit = e.recoveryUnit;
        return d;
    }
}
