package com.cpq.costingsummary.service;

import com.cpq.common.exception.BusinessException;
import com.cpq.costingbasic.entity.*;
import com.cpq.costingpart.entity.*;
import com.cpq.costingsummary.entity.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * 核价单 Service —— 创建 / 计算 / 发布 / 归档；含 8 项指标的简化求值引擎。
 *
 * 简化求值约束（Phase C 起步版）：
 *   - 8 个 metric 全部在内部按依赖图算（material_cost → semi_product_material_cost → process_fee → ...）
 *   - 公式硬编码（Q4=B+C 决策：先内部按依赖图算；后续可加可配置 formula 表）
 *   - 用户差量直接覆盖基础数据查到的值（不调路径解析器，从 override 表里命中即用）
 */
@ApplicationScoped
public class CostingSummaryService {

    private static final Logger LOG = Logger.getLogger(CostingSummaryService.class);

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    @Inject
    EntityManager em;

    // ─── 主表 CRUD ────────────────────────────────────────────────────

    public List<CostingSummary> listSummaries(String hfPartNo, String status) {
        StringBuilder hql = new StringBuilder("1=1");
        List<Object> params = new ArrayList<>();
        if (hfPartNo != null && !hfPartNo.isBlank()) {
            hql.append(" AND hfPartNo = ?").append(params.size() + 1);
            params.add(hfPartNo);
        }
        if (status != null && !status.isBlank()) {
            hql.append(" AND status = ?").append(params.size() + 1);
            params.add(status);
        }
        hql.append(" ORDER BY createdAt DESC");
        return CostingSummary.find(hql.toString(), params.toArray()).list();
    }

    public CostingSummary getSummary(UUID id) {
        CostingSummary s = CostingSummary.findById(id);
        if (s == null) throw new BusinessException(404, "Summary not found: " + id);
        return s;
    }

    @Transactional
    public CostingSummary createSummary(CostingSummary req) {
        // V105: 核价单解绑版本号. 新核价单一律走 v_costing_*_price 视图取"当前默认 PUBLISHED 版本".
        // element/material/exchange version_id 不再必填 (已发布历史单保留旧值作审计).
        if (req.hfPartNo == null || req.hfPartNo.isBlank()) {
            throw new BusinessException(400, "hfPartNo 不能为空");
        }

        if (req.summaryNo == null || req.summaryNo.isBlank()) {
            req.summaryNo = generateSummaryNo();
        }
        long dup = CostingSummary.count("summaryNo", req.summaryNo);
        if (dup > 0) throw new BusinessException(400, "summaryNo 已存在: " + req.summaryNo);

        // V105: 显式置 null 表示"用当前默认", 避免前端传入老 version_id 误绑
        req.elementVersionId = null;
        req.materialVersionId = null;
        req.exchangeVersionId = null;
        req.status = CostingSummary.STATUS_DRAFT;
        req.persist();
        LOG.infof("Created costing summary id=%s no=%s part=%s",
                req.id, req.summaryNo, req.hfPartNo);
        return req;
    }

    @Transactional
    public void deleteSummary(UUID id) {
        CostingSummary s = CostingSummary.findById(id);
        if (s == null) throw new BusinessException(404, "Summary not found: " + id);
        if (CostingSummary.STATUS_PUBLISHED.equals(s.status)) {
            throw new BusinessException(400, "已发布的核价单请先归档");
        }
        // CASCADE 自动清 override / result
        s.delete();
    }

    @Transactional
    public CostingSummary archiveSummary(UUID id) {
        CostingSummary s = getSummary(id);
        if (!CostingSummary.STATUS_PUBLISHED.equals(s.status)) {
            throw new BusinessException(400, "仅已发布可归档");
        }
        s.status = CostingSummary.STATUS_ARCHIVED;
        return s;
    }

    // ─── Override CRUD ───────────────────────────────────────────────

    public List<CostingSummaryOverride> listOverrides(UUID summaryId) {
        return CostingSummaryOverride.list(
                "summaryId = ?1 ORDER BY targetKind ASC, targetKey ASC, fieldName ASC", summaryId);
    }

    @Transactional
    public CostingSummaryOverride saveOverride(CostingSummaryOverride req) {
        CostingSummary s = getSummary(req.summaryId);
        if (CostingSummary.STATUS_PUBLISHED.equals(s.status) || CostingSummary.STATUS_ARCHIVED.equals(s.status)) {
            throw new BusinessException(400, "已发布/归档的核价单不可改差量");
        }
        if (req.id == null) {
            req.persist();
        } else {
            CostingSummaryOverride db = CostingSummaryOverride.findById(req.id);
            if (db == null) throw new BusinessException(404, "Override not found");
            db.targetKind = req.targetKind; db.targetKey = req.targetKey;
            db.fieldName = req.fieldName; db.overrideValue = req.overrideValue;
            db.notes = req.notes;
        }
        // 修改差量后状态退回 DRAFT（COMPUTED 失效）
        if (CostingSummary.STATUS_COMPUTED.equals(s.status)) {
            s.status = CostingSummary.STATUS_DRAFT;
        }
        return req;
    }

    @Transactional
    public void deleteOverride(UUID id) {
        CostingSummaryOverride o = CostingSummaryOverride.findById(id);
        if (o == null) throw new BusinessException(404, "Override not found");
        CostingSummary s = getSummary(o.summaryId);
        if (CostingSummary.STATUS_PUBLISHED.equals(s.status) || CostingSummary.STATUS_ARCHIVED.equals(s.status)) {
            throw new BusinessException(400, "已发布/归档的核价单不可改差量");
        }
        o.delete();
        if (CostingSummary.STATUS_COMPUTED.equals(s.status)) s.status = CostingSummary.STATUS_DRAFT;
    }

    public List<CostingSummaryResult> listResults(UUID summaryId) {
        return CostingSummaryResult.list("summaryId = ?1 ORDER BY sortOrder ASC, metricCode ASC", summaryId);
    }

    // ─── 计算引擎 ────────────────────────────────────────────────────

    /**
     * 计算核价单：聚合 8 项指标并落 result 表。
     * 状态：DRAFT → COMPUTED（再次调用会清旧结果重算）。
     */
    @Transactional
    public List<CostingSummaryResult> compute(UUID summaryId) {
        CostingSummary s = getSummary(summaryId);
        if (CostingSummary.STATUS_PUBLISHED.equals(s.status) || CostingSummary.STATUS_ARCHIVED.equals(s.status)) {
            throw new BusinessException(400, "已发布/归档不可重算");
        }

        // 加载差量到 map：(kind, key, field) → value
        Map<String, BigDecimal> overrides = new HashMap<>();
        listOverrides(summaryId).forEach(o ->
                overrides.put(o.targetKind + ":" + o.targetKey + ":" + o.fieldName, o.overrideValue));

        // V105: 解绑版本号. 新核价单 version_id=NULL 时走 v_costing_*_price 视图 (当前默认 PUBLISHED).
        // 老核价单 (V105 之前已建) 仍按原绑定 version_id 读, 保审计快照不变.
        // 应用差量到值视图.
        Map<String, BigDecimal> elementPrices = loadElementPrices(s.elementVersionId, overrides);
        Map<String, BigDecimal> materialPrices = loadMaterialPrices(s.materialVersionId, overrides);
        Map<String, BigDecimal> rates = loadExchangeRates(s.exchangeVersionId, overrides);
        BigDecimal toRate = pickRate(rates, "CNY", s.quoteCurrency);

        // 加载料号级数据
        List<CostingPartMaterialBom> matBoms = CostingPartMaterialBom.list(
                "hfPartNo = ?1 AND isActive = true ORDER BY seqNo ASC", s.hfPartNo);
        List<CostingPartProcessCost> processCosts = CostingPartProcessCost.list(
                "hfPartNo = ?1 AND isActive = true", s.hfPartNo);
        List<CostingPartToolingCost> toolingCosts = CostingPartToolingCost.list(
                "hfPartNo = ?1 AND isActive = true", s.hfPartNo);
        List<CostingPartDesignCost> designCosts = CostingPartDesignCost.list(
                "hfPartNo = ?1 AND isActive = true", s.hfPartNo);
        CostingPartWeight weight = CostingPartWeight.find("hfPartNo", s.hfPartNo).firstResult();

        // ========== 1. material_cost：材料 BOM 的输入材料按元素组成 × 元素单价 求和 ==========
        BigDecimal materialCost = BigDecimal.ZERO;
        StringBuilder mcFormula = new StringBuilder("Σ(material_bom × element_bom.composition_pct × element_price)");
        for (CostingPartMaterialBom bom : matBoms) {
            if (bom.inputMaterialNo == null) continue;
            // 优先用元素 BOM 拆解（有则按元素含量×元素价格 + 损耗）
            List<CostingPartElementBom> elements = CostingPartElementBom.list(
                    "inputMaterialNo = ?1 AND isActive = true", bom.inputMaterialNo);
            BigDecimal lineMaterialPrice;
            if (!elements.isEmpty()) {
                lineMaterialPrice = BigDecimal.ZERO;
                for (CostingPartElementBom el : elements) {
                    BigDecimal elPrice = elementPrices.getOrDefault(el.elementCode, BigDecimal.ZERO);
                    BigDecimal pct = el.compositionPct != null ? el.compositionPct : BigDecimal.ZERO;
                    BigDecimal elLossRate = el.lossRate != null ? el.lossRate : BigDecimal.ZERO;
                    BigDecimal effPct = pct.multiply(BigDecimal.ONE.add(elLossRate.divide(HUNDRED, 6, RoundingMode.HALF_UP)));
                    lineMaterialPrice = lineMaterialPrice.add(
                            elPrice.multiply(effPct).divide(HUNDRED, 6, RoundingMode.HALF_UP));
                }
            } else {
                // 回退：用材料价格表
                lineMaterialPrice = materialPrices.getOrDefault(bom.inputMaterialNo, BigDecimal.ZERO);
            }
            // 输入数量 × 价格 × 输出损耗率（先简化：直接用 inputQty × price）
            BigDecimal qty = bom.inputQty != null ? bom.inputQty : BigDecimal.ZERO;
            BigDecimal lineLoss = bom.lossRate != null ? bom.lossRate : BigDecimal.ZERO;
            BigDecimal lineCost = qty.multiply(lineMaterialPrice).multiply(
                    BigDecimal.ONE.add(lineLoss.divide(HUNDRED, 6, RoundingMode.HALF_UP)));
            materialCost = materialCost.add(lineCost);
        }

        // ========== 2. process_fee：工序级单价求和（不含 tooling，单独算） ==========
        BigDecimal processFee = BigDecimal.ZERO;
        for (CostingPartProcessCost pc : processCosts) {
            // 简化：所有 cost_type 都算入加工费
            processFee = processFee.add(pc.unitPrice != null ? pc.unitPrice : BigDecimal.ZERO);
        }
        // ========== 3. tooling_fee：模具工装单价求和 ==========
        BigDecimal toolingFee = BigDecimal.ZERO;
        for (CostingPartToolingCost t : toolingCosts) {
            toolingFee = toolingFee.add(t.unitPrice != null ? t.unitPrice : BigDecimal.ZERO);
        }
        // ========== 4. design_cost：设计加工费 + 设计材料费 求和 ==========
        BigDecimal designCost = BigDecimal.ZERO;
        for (CostingPartDesignCost d : designCosts) {
            BigDecimal dProc = d.designProcFee != null ? d.designProcFee : BigDecimal.ZERO;
            BigDecimal dMat = d.designMaterialFee != null ? d.designMaterialFee : BigDecimal.ZERO;
            designCost = designCost.add(dProc).add(dMat);
        }

        // ========== 5. unit_total_cost：以上所有合计 ==========
        BigDecimal unitTotalCnyKg = materialCost.add(processFee).add(toolingFee).add(designCost);

        // 折算到报价货币（CNY → quoteCurrency）。若汇率缺失，保留 CNY。
        BigDecimal unitTotalQuote = toRate != null && toRate.signum() > 0
                ? unitTotalCnyKg.multiply(toRate)
                : unitTotalCnyKg;

        // ========== 6. unit_total_per_pcs：按 g/pcs 转单件 ==========
        BigDecimal perPcs = BigDecimal.ZERO;
        if (weight != null && weight.weightGPerPcs != null && weight.weightGPerPcs.signum() > 0) {
            // weight 是 g/pcs；料号成本的 unit 多为 KG，所以 /1000
            BigDecimal kgPerPcs = weight.weightGPerPcs.divide(new BigDecimal("1000"), 9, RoundingMode.HALF_UP);
            perPcs = unitTotalQuote.multiply(kgPerPcs);
        }

        // 清旧结果
        CostingSummaryResult.delete("summaryId", summaryId);

        // 写新结果
        List<CostingSummaryResult> results = new ArrayList<>();
        results.add(makeResult(s.id, "MATERIAL_COST", "材料成本", materialCost, "CNY/KG",
                "Σ(material_bom × element_bom.composition_pct × element_price)", 1));
        results.add(makeResult(s.id, "PROCESS_FEE", "加工费(工序合计)", processFee, "CNY/KG",
                "Σ unit_price (LABOR/DEPRECIATION/ENERGY/CONSUMABLE/MATERIAL_PROC/SEMI_FINISHED_PROC/POST_PROC)", 2));
        results.add(makeResult(s.id, "TOOLING_FEE", "模具工装费", toolingFee, "CNY/KG",
                "Σ tooling_unit_cost / process_count / cycle_count", 3));
        results.add(makeResult(s.id, "DESIGN_COST", "设计成本", designCost, "CNY",
                "Σ (design_proc_fee + design_material_fee)", 4));
        results.add(makeResult(s.id, "UNIT_TOTAL_COST", "单位总成本", unitTotalCnyKg, "CNY/KG",
                "材料成本 + 加工费 + 模具费 + 设计成本", 5));
        results.add(makeResult(s.id, "UNIT_TOTAL_QUOTE", "单位总成本(报价货币)", unitTotalQuote, s.quoteCurrency,
                "UNIT_TOTAL_COST × 汇率(CNY→" + s.quoteCurrency + ")", 6));
        results.add(makeResult(s.id, "UNIT_PER_PCS", "单件成本", perPcs, s.quoteCurrency,
                "UNIT_TOTAL_QUOTE × weight(g/pcs)/1000", 7));

        for (var r : results) r.persist();

        s.status = CostingSummary.STATUS_COMPUTED;
        s.computedAt = OffsetDateTime.now();
        LOG.infof("Computed summary id=%s, %d metrics", s.id, results.size());
        return results;
    }

    @Transactional
    public CostingSummary publish(UUID summaryId) {
        CostingSummary s = getSummary(summaryId);
        if (!CostingSummary.STATUS_COMPUTED.equals(s.status)) {
            throw new BusinessException(400, "请先「计算」核价单后再发布");
        }
        s.status = CostingSummary.STATUS_PUBLISHED;
        s.publishedAt = OffsetDateTime.now();
        return s;
    }

    // ─── helpers ─────────────────────────────────────────────────────

    private String generateSummaryNo() {
        // CS-yyyymm-NNNN，本月内序号自增
        var now = OffsetDateTime.now();
        String prefix = String.format("CS-%04d%02d-", now.getYear(), now.getMonthValue());
        long count = CostingSummary.count("summaryNo LIKE ?1", prefix + "%");
        return prefix + String.format("%04d", count + 1);
    }

    /**
     * V105: 加载元素价格. versionId 非 null 时按指定版本读 (历史单审计场景);
     * null 时走 v_costing_element_price 视图 (当前默认 PUBLISHED 版本).
     */
    private Map<String, BigDecimal> loadElementPrices(UUID versionId, Map<String, BigDecimal> overrides) {
        Map<String, BigDecimal> out = new HashMap<>();
        if (versionId != null) {
            List<CostingElementPrice> rows = CostingElementPrice.list("versionId", versionId);
            for (CostingElementPrice e : rows) {
                BigDecimal v = overrides.getOrDefault(
                        "ELEMENT:" + e.elementCode + ":costing_price", e.costingPrice);
                out.put(e.elementCode, v);
            }
        } else {
            @SuppressWarnings("unchecked")
            List<Object[]> rows = em.createNativeQuery(
                    "SELECT element_code, costing_price FROM v_costing_element_price")
                    .getResultList();
            for (Object[] r : rows) {
                String code = (String) r[0];
                BigDecimal price = r[1] instanceof BigDecimal bd ? bd
                        : r[1] != null ? new BigDecimal(r[1].toString()) : null;
                if (price == null) continue;
                BigDecimal v = overrides.getOrDefault(
                        "ELEMENT:" + code + ":costing_price", price);
                out.put(code, v);
            }
        }
        return out;
    }

    private Map<String, BigDecimal> loadMaterialPrices(UUID versionId, Map<String, BigDecimal> overrides) {
        Map<String, BigDecimal> out = new HashMap<>();
        if (versionId != null) {
            List<CostingMaterialPrice> rows = CostingMaterialPrice.list("versionId", versionId);
            for (CostingMaterialPrice m : rows) {
                BigDecimal v = overrides.getOrDefault(
                        "MATERIAL:" + m.materialNo + ":costing_price", m.costingPrice);
                out.put(m.materialNo, v);
            }
        } else {
            @SuppressWarnings("unchecked")
            List<Object[]> rows = em.createNativeQuery(
                    "SELECT material_no, costing_price FROM v_costing_material_price")
                    .getResultList();
            for (Object[] r : rows) {
                String no = (String) r[0];
                BigDecimal price = r[1] instanceof BigDecimal bd ? bd
                        : r[1] != null ? new BigDecimal(r[1].toString()) : null;
                if (price == null) continue;
                BigDecimal v = overrides.getOrDefault(
                        "MATERIAL:" + no + ":costing_price", price);
                out.put(no, v);
            }
        }
        return out;
    }

    private Map<String, BigDecimal> loadExchangeRates(UUID versionId, Map<String, BigDecimal> overrides) {
        Map<String, BigDecimal> out = new HashMap<>();
        if (versionId != null) {
            List<CostingExchangeRate> rows = CostingExchangeRate.list("versionId", versionId);
            for (CostingExchangeRate r : rows) {
                String key = r.fromCurrency + "/" + r.toCurrency;
                BigDecimal v = overrides.getOrDefault(
                        "EXCHANGE:" + key + ":costing_rate", r.costingRate);
                out.put(key, v);
            }
        } else {
            @SuppressWarnings("unchecked")
            List<Object[]> rows = em.createNativeQuery(
                    "SELECT from_currency, to_currency, costing_rate FROM v_costing_exchange_rate")
                    .getResultList();
            for (Object[] r : rows) {
                String key = r[0] + "/" + r[1];
                BigDecimal rate = r[2] instanceof BigDecimal bd ? bd
                        : r[2] != null ? new BigDecimal(r[2].toString()) : null;
                if (rate == null) continue;
                BigDecimal v = overrides.getOrDefault(
                        "EXCHANGE:" + key + ":costing_rate", rate);
                out.put(key, v);
            }
        }
        return out;
    }

    private BigDecimal pickRate(Map<String, BigDecimal> rates, String from, String to) {
        if (from.equals(to)) return BigDecimal.ONE;
        BigDecimal direct = rates.get(from + "/" + to);
        if (direct != null) return direct;
        BigDecimal rev = rates.get(to + "/" + from);
        if (rev != null && rev.signum() > 0) return BigDecimal.ONE.divide(rev, 6, RoundingMode.HALF_UP);
        return null; // 无汇率，调用者保留原币种
    }

    private CostingSummaryResult makeResult(UUID summaryId, String code, String label,
                                             BigDecimal value, String currency, String formula, int sortOrder) {
        CostingSummaryResult r = new CostingSummaryResult();
        r.summaryId = summaryId;
        r.metricCode = code;
        r.metricLabel = label;
        r.value = value;
        r.currency = currency;
        r.formulaUsed = formula;
        r.sortOrder = sortOrder;
        return r;
    }
}
