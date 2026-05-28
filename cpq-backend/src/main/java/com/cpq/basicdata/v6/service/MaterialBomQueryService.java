package com.cpq.basicdata.v6.service;

import com.cpq.basicdata.v6.dto.MaterialBomItemDTO;
import com.cpq.basicdata.v6.entity.MaterialBomItem;
import com.cpq.basicdata.v6.repository.MaterialBomItemRepository;
import com.cpq.common.dto.PageResult;
import com.cpq.common.exception.BusinessException;
import io.quarkus.panache.common.Page;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * V6 物料 BOM 查询服务（只读）。
 *
 * <p>systemType 语义宽松展开规则：
 * <ul>
 *   <li>QUOTE   → IN ('QUOTE','BOTH')
 *   <li>PRICING → IN ('PRICING','BOTH')
 *   <li>BOTH    → = 'BOTH'（精确匹配）
 *   <li>null    → 不过滤
 * </ul>
 *
 * <p>customer-nos 用 {@link ConcurrentHashMap} + lastFetchedAt 做 5 分钟本地缓存，
 * 避免高频查询全表 DISTINCT。
 */
@ApplicationScoped
public class MaterialBomQueryService {

    private static final long CUSTOMER_NOS_TTL_MS = 5L * 60 * 1000; // 5 分钟

    @Inject
    MaterialBomItemRepository repository;

    // ── customer-nos 本地缓存 ─────────────────────────────────────────────────
    private volatile List<String> cachedCustomerNos = null;
    private volatile long lastFetchedAt = 0L;

    // ── 公开接口 ──────────────────────────────────────────────────────────────

    /**
     * 分页查询 BOM 子表。
     *
     * @param customerNo  必填，空时抛 400 MISSING_CUSTOMER_NO
     * @param materialNo  可为 null
     * @param systemType  可为 null / QUOTE / PRICING / BOTH
     * @param page        从 0 开始
     * @param size        每页条数（最大 200）
     */
    @Transactional(Transactional.TxType.SUPPORTS)
    public PageResult<MaterialBomItemDTO> queryItems(String customerNo, String materialNo,
                                                    String systemType, int page, int size) {
        validateCustomerNo(customerNo);
        if (size > 200) {
            throw new BusinessException(400, "INVALID_PAGE_SIZE: size 不能超过 200");
        }
        List<String> systemTypes = expandSystemType(systemType);
        var query = repository.queryItems(customerNo, materialNo, systemTypes);
        long total = query.count();
        List<MaterialBomItemDTO> content = query.page(Page.of(page, size))
                .list()
                .stream()
                .map(MaterialBomItemDTO::from)
                .collect(Collectors.toList());
        return new PageResult<>(content, page, size, total);
    }

    /**
     * 返回所有客户编号（去重），结果缓存 5 分钟。
     */
    @Transactional(Transactional.TxType.SUPPORTS)
    public List<String> findDistinctCustomerNos() {
        long now = Instant.now().toEpochMilli();
        if (cachedCustomerNos != null && (now - lastFetchedAt) < CUSTOMER_NOS_TTL_MS) {
            return cachedCustomerNos;
        }
        List<String> result = repository.findDistinctCustomerNos();
        cachedCustomerNos = result;
        lastFetchedAt = now;
        return result;
    }

    /**
     * 返回指定客户下的物料编号（去重），支持前缀/模糊搜索。
     *
     * @param customerNo 必填
     * @param q          可为 null，模糊匹配
     * @param limit      最大 1000，默认 500
     */
    @Transactional(Transactional.TxType.SUPPORTS)
    public List<String> findDistinctMaterialNos(String customerNo, String q, int limit) {
        validateCustomerNo(customerNo);
        int safeLimit = Math.min(Math.max(1, limit), 1000);
        return repository.findDistinctMaterialNos(customerNo, q, safeLimit);
    }

    // ── 内部工具 ─────────────────────────────────────────────────────────────

    /**
     * 将前端传入的 systemType 字符串展开为 JPQL IN 参数列表。
     * 返回 null 表示不过滤（所有 systemType）。
     */
    private List<String> expandSystemType(String systemType) {
        if (systemType == null || systemType.isBlank()) {
            return null;
        }
        return switch (systemType.toUpperCase()) {
            case "QUOTE"   -> List.of("QUOTE", "BOTH");
            case "PRICING" -> List.of("PRICING", "BOTH");
            case "BOTH"    -> List.of("BOTH");
            default -> throw new BusinessException(400,
                    "INVALID_SYSTEM_TYPE: systemType 必须是 QUOTE / PRICING / BOTH，实际传入: " + systemType);
        };
    }

    private void validateCustomerNo(String customerNo) {
        if (customerNo == null || customerNo.isBlank()) {
            throw new BusinessException(400, "MISSING_CUSTOMER_NO: customerNo 为必填参数");
        }
    }
}
