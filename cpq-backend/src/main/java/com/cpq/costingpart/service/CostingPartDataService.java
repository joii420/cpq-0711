package com.cpq.costingpart.service;

import com.cpq.common.exception.BusinessException;
import com.cpq.costingpart.entity.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.UUID;

/**
 * 料号级核价数据服务 —— 8 类合一。
 *
 * 设计：每类用 4 个标准方法（list / create / update / delete）
 *      list 按 hf_part_no 维度（电镀按 plating_no，元素 BOM 按 input_material_no）。
 */
@ApplicationScoped
public class CostingPartDataService {

    private static final Logger LOG = Logger.getLogger(CostingPartDataService.class);

    // ─── 1. 工序级单价 ────────────────────────────────────────────
    public List<CostingPartProcessCost> listProcessCost(String hfPartNo, String costType) {
        StringBuilder hql = new StringBuilder("hfPartNo = ?1");
        Object[] params;
        if (costType != null && !costType.isBlank()) {
            hql.append(" AND costType = ?2");
            params = new Object[]{hfPartNo, costType};
        } else {
            params = new Object[]{hfPartNo};
        }
        hql.append(" ORDER BY costType ASC, processNo ASC");
        return CostingPartProcessCost.find(hql.toString(), params).list();
    }

    @Transactional
    public CostingPartProcessCost saveProcessCost(CostingPartProcessCost req) {
        if (req.hfPartNo == null || req.processNo == null || req.costType == null
                || req.unitPrice == null) {
            throw new BusinessException(400, "hfPartNo / processNo / costType / unitPrice 不能为空");
        }
        if (!CostingPartProcessCost.VALID_TYPES.contains(req.costType)) {
            throw new BusinessException(400, "Invalid cost_type: " + req.costType);
        }
        if (req.id == null) {
            req.persist();
        } else {
            CostingPartProcessCost db = CostingPartProcessCost.findById(req.id);
            if (db == null) throw new BusinessException(404, "ProcessCost not found: " + req.id);
            apply(db, req);
        }
        return req.id == null ? req : (CostingPartProcessCost) CostingPartProcessCost.findById(req.id);
    }

    @Transactional
    public void deleteProcessCost(UUID id) {
        CostingPartProcessCost.deleteById(id);
    }

    private void apply(CostingPartProcessCost db, CostingPartProcessCost src) {
        db.hfPartNo = src.hfPartNo; db.processNo = src.processNo; db.processName = src.processName;
        db.costType = src.costType; db.unitPrice = src.unitPrice;
        db.currency = src.currency; db.unit = src.unit;
        db.refCalcVersion = src.refCalcVersion; db.notes = src.notes;
        if (src.isActive != null) db.isActive = src.isActive;
    }

    // ─── 2. 模具工装 ─────────────────────────────────────────────
    public List<CostingPartToolingCost> listTooling(String hfPartNo) {
        return CostingPartToolingCost.list("hfPartNo = ?1 ORDER BY processNo ASC, seqNo ASC", hfPartNo);
    }

    @Transactional
    public CostingPartToolingCost saveTooling(CostingPartToolingCost req) {
        if (req.hfPartNo == null || req.processNo == null || req.seqNo == null
                || req.toolingUnitCost == null) {
            throw new BusinessException(400, "hfPartNo / processNo / seqNo / toolingUnitCost 不能为空");
        }
        if (req.id == null) {
            req.persist();
        } else {
            CostingPartToolingCost db = CostingPartToolingCost.findById(req.id);
            if (db == null) throw new BusinessException(404, "Tooling not found: " + req.id);
            db.hfPartNo = req.hfPartNo; db.processNo = req.processNo; db.processName = req.processName;
            db.seqNo = req.seqNo; db.toolingNo = req.toolingNo;
            db.toolingUnitCost = req.toolingUnitCost; db.processCount = req.processCount;
            db.cycleCount = req.cycleCount; db.unitPrice = req.unitPrice;
            db.currency = req.currency; db.unit = req.unit;
            db.notes = req.notes;
            if (req.isActive != null) db.isActive = req.isActive;
        }
        return req.id == null ? req : (CostingPartToolingCost) CostingPartToolingCost.findById(req.id);
    }

    @Transactional
    public void deleteTooling(UUID id) { CostingPartToolingCost.deleteById(id); }

    // ─── 3. 材料 BOM ───────────────────────────────────────────────
    public List<CostingPartMaterialBom> listMaterialBom(String hfPartNo) {
        return CostingPartMaterialBom.list("hfPartNo = ?1 ORDER BY seqNo ASC", hfPartNo);
    }

    @Transactional
    public CostingPartMaterialBom saveMaterialBom(CostingPartMaterialBom req) {
        if (req.hfPartNo == null || req.seqNo == null) {
            throw new BusinessException(400, "hfPartNo / seqNo 不能为空");
        }
        if (req.id == null) {
            req.persist();
        } else {
            CostingPartMaterialBom db = CostingPartMaterialBom.findById(req.id);
            if (db == null) throw new BusinessException(404, "MaterialBom not found: " + req.id);
            db.hfPartNo = req.hfPartNo; db.seqNo = req.seqNo;
            db.inputMaterialNo = req.inputMaterialNo; db.processNo = req.processNo; db.processName = req.processName;
            db.inputQty = req.inputQty; db.inputUnit = req.inputUnit;
            db.outputQty = req.outputQty; db.outputUnit = req.outputUnit;
            db.outputLossRate = req.outputLossRate; db.fixedLossQty = req.fixedLossQty;
            db.lossRate = req.lossRate; db.notes = req.notes;
            if (req.isActive != null) db.isActive = req.isActive;
        }
        return req.id == null ? req : (CostingPartMaterialBom) CostingPartMaterialBom.findById(req.id);
    }

    @Transactional
    public void deleteMaterialBom(UUID id) { CostingPartMaterialBom.deleteById(id); }

    // ─── 4. 元素 BOM ───────────────────────────────────────────────
    public List<CostingPartElementBom> listElementBom(String inputMaterialNo) {
        return CostingPartElementBom.list("inputMaterialNo = ?1 ORDER BY seqNo ASC", inputMaterialNo);
    }

    @Transactional
    public CostingPartElementBom saveElementBom(CostingPartElementBom req) {
        if (req.inputMaterialNo == null || req.seqNo == null
                || req.elementCode == null || req.compositionPct == null) {
            throw new BusinessException(400, "inputMaterialNo / seqNo / elementCode / compositionPct 不能为空");
        }
        if (req.id == null) {
            req.persist();
        } else {
            CostingPartElementBom db = CostingPartElementBom.findById(req.id);
            if (db == null) throw new BusinessException(404, "ElementBom not found: " + req.id);
            db.inputMaterialNo = req.inputMaterialNo; db.seqNo = req.seqNo;
            db.elementCode = req.elementCode; db.compositionPct = req.compositionPct;
            db.lossRate = req.lossRate; db.notes = req.notes;
            if (req.isActive != null) db.isActive = req.isActive;
        }
        return req.id == null ? req : (CostingPartElementBom) CostingPartElementBom.findById(req.id);
    }

    @Transactional
    public void deleteElementBom(UUID id) { CostingPartElementBom.deleteById(id); }

    // ─── 5. 质量检验 ──────────────────────────────────────────────
    public List<CostingPartQualityCheck> listQualityCheck(String hfPartNo, String stage) {
        StringBuilder hql = new StringBuilder("hfPartNo = ?1");
        Object[] params;
        if (stage != null && !stage.isBlank()) {
            hql.append(" AND stage = ?2");
            params = new Object[]{hfPartNo, stage};
        } else {
            params = new Object[]{hfPartNo};
        }
        hql.append(" ORDER BY stage ASC, primarySeqNo ASC, seqNo ASC");
        return CostingPartQualityCheck.find(hql.toString(), params).list();
    }

    @Transactional
    public CostingPartQualityCheck saveQualityCheck(CostingPartQualityCheck req) {
        if (req.hfPartNo == null || req.stage == null || req.seqNo == null) {
            throw new BusinessException(400, "hfPartNo / stage / seqNo 不能为空");
        }
        if (!CostingPartQualityCheck.VALID_STAGES.contains(req.stage)) {
            throw new BusinessException(400, "Invalid stage: " + req.stage);
        }
        if (req.id == null) {
            req.persist();
        } else {
            CostingPartQualityCheck db = CostingPartQualityCheck.findById(req.id);
            if (db == null) throw new BusinessException(404, "QualityCheck not found: " + req.id);
            db.hfPartNo = req.hfPartNo; db.stage = req.stage;
            db.primarySeqNo = req.primarySeqNo; db.seqNo = req.seqNo;
            db.requirementCode = req.requirementCode; db.requirementDesc = req.requirementDesc;
            db.scrapRate = req.scrapRate; db.notes = req.notes;
            if (req.isActive != null) db.isActive = req.isActive;
        }
        return req.id == null ? req : (CostingPartQualityCheck) CostingPartQualityCheck.findById(req.id);
    }

    @Transactional
    public void deleteQualityCheck(UUID id) { CostingPartQualityCheck.deleteById(id); }

    // ─── 6. 电镀 ──────────────────────────────────────────────────
    public List<CostingPartPlating> listPlating(String platingNo) {
        return CostingPartPlating.list("platingNo = ?1 ORDER BY versionNumber DESC, seqNo ASC", platingNo);
    }

    public List<CostingPartPlating> listAllPlating() {
        return CostingPartPlating.listAll();
    }

    @Transactional
    public CostingPartPlating savePlating(CostingPartPlating req) {
        if (req.platingNo == null || req.versionNumber == null || req.seqNo == null) {
            throw new BusinessException(400, "platingNo / versionNumber / seqNo 不能为空");
        }
        if (req.id == null) {
            req.persist();
        } else {
            CostingPartPlating db = CostingPartPlating.findById(req.id);
            if (db == null) throw new BusinessException(404, "Plating not found: " + req.id);
            db.platingNo = req.platingNo; db.versionNumber = req.versionNumber;
            db.seqNo = req.seqNo; db.elementAttr = req.elementAttr;
            db.platingAreaCm2 = req.platingAreaCm2; db.layerThicknessUm = req.layerThicknessUm;
            db.requirement = req.requirement;
            if (req.isActive != null) db.isActive = req.isActive;
        }
        return req.id == null ? req : (CostingPartPlating) CostingPartPlating.findById(req.id);
    }

    @Transactional
    public void deletePlating(UUID id) { CostingPartPlating.deleteById(id); }

    // ─── 6.b 电镀费用 (核价侧, 按 partNo) ─────────────────────────
    // V125: PlatingFee 实体现在映射到 costing_part_plating_fee (核价侧物理表).
    // 报价侧 mat_plating_fee 由 VersionedWriter 写, 不在此读取范围.
    public List<PlatingFee> listPlatingFee(String hfPartNo) {
        if (hfPartNo == null || hfPartNo.isBlank()) {
            return List.of();
        }
        return PlatingFee.list("hfPartNo = ?1 AND isActive = true ORDER BY platingPlanCode ASC, planVersion DESC", hfPartNo);
    }

    // ─── 7. 设计成本 ──────────────────────────────────────────────
    public List<CostingPartDesignCost> listDesignCost(String hfPartNo) {
        return CostingPartDesignCost.list("hfPartNo = ?1 ORDER BY designDrawingNo ASC, versionNumber DESC", hfPartNo);
    }

    @Transactional
    public CostingPartDesignCost saveDesignCost(CostingPartDesignCost req) {
        if (req.hfPartNo == null) throw new BusinessException(400, "hfPartNo 不能为空");
        if (req.id == null) {
            req.persist();
        } else {
            CostingPartDesignCost db = CostingPartDesignCost.findById(req.id);
            if (db == null) throw new BusinessException(404, "DesignCost not found: " + req.id);
            db.hfPartNo = req.hfPartNo; db.designDrawingNo = req.designDrawingNo;
            db.versionNumber = req.versionNumber;
            db.designProcFee = req.designProcFee; db.designMaterialFee = req.designMaterialFee;
            db.currency = req.currency; db.unit = req.unit;
            db.lossRate = req.lossRate; db.notes = req.notes;
            if (req.isActive != null) db.isActive = req.isActive;
        }
        return req.id == null ? req : (CostingPartDesignCost) CostingPartDesignCost.findById(req.id);
    }

    @Transactional
    public void deleteDesignCost(UUID id) { CostingPartDesignCost.deleteById(id); }

    // ─── 8. 重量 ──────────────────────────────────────────────────
    public CostingPartWeight getWeight(String hfPartNo) {
        return CostingPartWeight.find("hfPartNo", hfPartNo).firstResult();
    }

    @Transactional
    public CostingPartWeight saveWeight(CostingPartWeight req) {
        if (req.hfPartNo == null || req.weightGPerPcs == null) {
            throw new BusinessException(400, "hfPartNo / weightGPerPcs 不能为空");
        }
        // 一料号一行：upsert
        CostingPartWeight db = CostingPartWeight.find("hfPartNo", req.hfPartNo).firstResult();
        if (db == null) {
            req.persist();
            return req;
        }
        db.weightGPerPcs = req.weightGPerPcs;
        db.notes = req.notes;
        if (req.isActive != null) db.isActive = req.isActive;
        return db;
    }

    @Transactional
    public void deleteWeight(UUID id) { CostingPartWeight.deleteById(id); }
}
