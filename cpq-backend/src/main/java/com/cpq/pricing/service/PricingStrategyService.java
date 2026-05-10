package com.cpq.pricing.service;

import com.cpq.common.dto.PageResult;
import com.cpq.common.exception.BusinessException;
import com.cpq.pricing.dto.CreatePricingStrategyRequest;
import com.cpq.pricing.dto.PricingStrategyDTO;
import com.cpq.pricing.entity.PricingRule;
import com.cpq.pricing.entity.PricingStrategy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.jboss.logging.Logger;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class PricingStrategyService {

    private static final Logger LOG = Logger.getLogger(PricingStrategyService.class);

    public PageResult<PricingStrategyDTO> list(int page, int size, UUID customerId) {
        page = com.cpq.common.dto.Pagination.clampPage(page);
        size = com.cpq.common.dto.Pagination.clampSize(size);
        long total;
        List<PricingStrategyDTO> content;

        if (customerId != null) {
            total = PricingStrategy.count("customerId = ?1", customerId);
            content = PricingStrategy
                    .find("customerId = ?1 ORDER BY priority ASC, createdAt DESC", customerId)
                    .page(page, size)
                    .<PricingStrategy>list()
                    .stream()
                    .map(s -> {
                        List<PricingRule> rules = PricingRule.list("strategy.id = ?1 ORDER BY sortOrder ASC", s.id);
                        return PricingStrategyDTO.from(s, rules);
                    })
                    .collect(Collectors.toList());
        } else {
            total = PricingStrategy.count();
            content = PricingStrategy
                    .find("ORDER BY priority ASC, createdAt DESC")
                    .page(page, size)
                    .<PricingStrategy>list()
                    .stream()
                    .map(s -> {
                        List<PricingRule> rules = PricingRule.list("strategy.id = ?1 ORDER BY sortOrder ASC", s.id);
                        return PricingStrategyDTO.from(s, rules);
                    })
                    .collect(Collectors.toList());
        }

        LOG.debugf("list pricing strategies page=%d size=%d customerId=%s total=%d", page, size, customerId, total);
        return new PageResult<>(content, page, size, total);
    }

    public PricingStrategyDTO getById(UUID id) {
        PricingStrategy strategy = PricingStrategy.findById(id);
        if (strategy == null) {
            throw new BusinessException(404, "PricingStrategy not found: " + id);
        }
        List<PricingRule> rules = PricingRule.list("strategy.id = ?1 ORDER BY sortOrder ASC", id);
        return PricingStrategyDTO.from(strategy, rules);
    }

    @Transactional
    public PricingStrategyDTO create(CreatePricingStrategyRequest request) {
        PricingStrategy strategy = new PricingStrategy();
        strategy.customerId = request.customerId;
        strategy.name = request.name;
        if (request.type != null) strategy.type = request.type;
        if (request.baseDiscount != null) strategy.baseDiscount = request.baseDiscount;
        if (request.minOrderAmount != null) strategy.minOrderAmount = request.minOrderAmount;
        strategy.effectiveDate = request.effectiveDate;
        strategy.expirationDate = request.expirationDate;
        if (request.priority != null) strategy.priority = request.priority;
        strategy.status = "ACTIVE";
        strategy.persist();

        if (request.rules != null) {
            for (CreatePricingStrategyRequest.RuleRequest ruleReq : request.rules) {
                PricingRule rule = new PricingRule();
                rule.strategy = strategy;
                if (ruleReq.ruleType != null) rule.ruleType = ruleReq.ruleType;
                rule.thresholdAmount = ruleReq.thresholdAmount != null ? ruleReq.thresholdAmount : BigDecimal.ZERO;
                rule.discountRate = ruleReq.discountRate != null ? ruleReq.discountRate : BigDecimal.ZERO;
                rule.sortOrder = ruleReq.sortOrder != null ? ruleReq.sortOrder : 0;
                rule.persist();
            }
        }

        List<PricingRule> rules = PricingRule.list("strategy.id = ?1 ORDER BY sortOrder ASC", strategy.id);
        LOG.infof("Created pricing strategy id=%s name=%s customerId=%s rules=%d",
                strategy.id, strategy.name, strategy.customerId, rules.size());
        return PricingStrategyDTO.from(strategy, rules);
    }

    @Transactional
    public PricingStrategyDTO update(UUID id, CreatePricingStrategyRequest request) {
        PricingStrategy strategy = PricingStrategy.findById(id);
        if (strategy == null) {
            throw new BusinessException(404, "PricingStrategy not found: " + id);
        }
        if (request.name != null) strategy.name = request.name;
        if (request.type != null) strategy.type = request.type;
        if (request.baseDiscount != null) strategy.baseDiscount = request.baseDiscount;
        if (request.minOrderAmount != null) strategy.minOrderAmount = request.minOrderAmount;
        if (request.effectiveDate != null) strategy.effectiveDate = request.effectiveDate;
        if (request.expirationDate != null) strategy.expirationDate = request.expirationDate;
        if (request.priority != null) strategy.priority = request.priority;

        // Delete and recreate rules
        if (request.rules != null) {
            PricingRule.delete("strategy.id = ?1", id);
            for (CreatePricingStrategyRequest.RuleRequest ruleReq : request.rules) {
                PricingRule rule = new PricingRule();
                rule.strategy = strategy;
                if (ruleReq.ruleType != null) rule.ruleType = ruleReq.ruleType;
                rule.thresholdAmount = ruleReq.thresholdAmount != null ? ruleReq.thresholdAmount : BigDecimal.ZERO;
                rule.discountRate = ruleReq.discountRate != null ? ruleReq.discountRate : BigDecimal.ZERO;
                rule.sortOrder = ruleReq.sortOrder != null ? ruleReq.sortOrder : 0;
                rule.persist();
            }
        }

        List<PricingRule> rules = PricingRule.list("strategy.id = ?1 ORDER BY sortOrder ASC", id);
        LOG.infof("Updated pricing strategy id=%s name=%s", id, strategy.name);
        return PricingStrategyDTO.from(strategy, rules);
    }

    @Transactional
    public void delete(UUID id) {
        PricingStrategy strategy = PricingStrategy.findById(id);
        if (strategy == null) {
            throw new BusinessException(404, "PricingStrategy not found: " + id);
        }
        PricingRule.delete("strategy.id = ?1", id);
        strategy.delete();
        LOG.infof("Deleted pricing strategy id=%s", id);
    }

    public byte[] exportToExcel(UUID customerId) {
        List<PricingStrategy> strategies;
        if (customerId != null) {
            strategies = PricingStrategy.find("customerId = ?1 ORDER BY priority ASC, createdAt DESC", customerId).list();
        } else {
            strategies = PricingStrategy.find("ORDER BY priority ASC, createdAt DESC").list();
        }

        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("定价策略");

            // Header row
            Row header = sheet.createRow(0);
            String[] columns = { "策略名称", "类型", "基础折扣(%)", "最低订单金额", "优先级", "状态", "生效日期", "到期日期" };
            for (int i = 0; i < columns.length; i++) {
                Cell cell = header.createCell(i);
                cell.setCellValue(columns[i]);
            }

            // Data rows
            int rowNum = 1;
            for (PricingStrategy s : strategies) {
                Row row = sheet.createRow(rowNum++);
                row.createCell(0).setCellValue(s.name != null ? s.name : "");
                row.createCell(1).setCellValue(s.type != null ? s.type : "");
                row.createCell(2).setCellValue(s.baseDiscount != null ? s.baseDiscount.doubleValue() : 100.0);
                row.createCell(3).setCellValue(s.minOrderAmount != null ? s.minOrderAmount.doubleValue() : 0.0);
                row.createCell(4).setCellValue(s.priority != null ? s.priority : 1);
                row.createCell(5).setCellValue(s.status != null ? s.status : "");
                row.createCell(6).setCellValue(s.effectiveDate != null ? s.effectiveDate.toString() : "");
                row.createCell(7).setCellValue(s.expirationDate != null ? s.expirationDate.toString() : "");
            }

            // Auto-size columns
            for (int i = 0; i < columns.length; i++) {
                sheet.autoSizeColumn(i);
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            LOG.infof("Exported %d pricing strategies to Excel", strategies.size());
            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to generate Excel export", e);
        }
    }

    @Transactional
    public PricingStrategyDTO updateStatus(UUID id, String status) {
        PricingStrategy strategy = PricingStrategy.findById(id);
        if (strategy == null) {
            throw new BusinessException(404, "PricingStrategy not found: " + id);
        }
        strategy.status = status;
        List<PricingRule> rules = PricingRule.list("strategy.id = ?1 ORDER BY sortOrder ASC", id);
        LOG.infof("Updated status for pricing strategy id=%s status=%s", id, status);
        return PricingStrategyDTO.from(strategy, rules);
    }
}
