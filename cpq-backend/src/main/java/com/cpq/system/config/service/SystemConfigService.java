package com.cpq.system.config.service;

import com.cpq.common.exception.BusinessException;
import com.cpq.system.config.dto.CreateSystemConfigRequest;
import com.cpq.system.config.dto.SystemConfigDTO;
import com.cpq.system.config.dto.UpdateSystemConfigRequest;
import com.cpq.system.config.entity.SystemConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@ApplicationScoped
public class SystemConfigService {

    private static final String CACHE_NAME = "system-config";

    // Simple in-memory cache backed by ConcurrentHashMap.
    // Quarkus @CacheResult/@CacheInvalidate key-matching is unreliable when mixed with
    // @Transactional (interceptor ordering causes invalidate to run before DB commit).
    // We manage the cache explicitly so that invalidation happens inside the transaction,
    // and the next getRaw() reads the committed value from DB.
    private final ConcurrentHashMap<String, String> rawCache = new ConcurrentHashMap<>();

    // ---- read with cache ----

    @Transactional(Transactional.TxType.REQUIRED)
    public String getRaw(String key) {
        String cached = rawCache.get(key);
        if (cached != null) {
            return cached;
        }
        // Cache miss: load from DB
        SystemConfig cfg = SystemConfig.findByKey(key);
        if (cfg == null) {
            throw new BusinessException(404, "系统配置项不存在: " + key);
        }
        rawCache.put(key, cfg.configValue);
        return cfg.configValue;
    }

    // ---- typed helpers (no cache, delegate to getRaw) ----

    public String getString(String key) {
        return getRaw(key);
    }

    public double getNumber(String key) {
        String raw = getRaw(key);
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            throw new BusinessException(500, "系统配置项 " + key + " 非数值类型: " + raw);
        }
    }

    public boolean getBoolean(String key) {
        return Boolean.parseBoolean(getRaw(key));
    }

    public String getJson(String key) {
        // Returns raw JSON string; caller is responsible for parsing
        return getRaw(key);
    }

    // ---- list ----

    @Transactional(Transactional.TxType.SUPPORTS)
    public List<SystemConfigDTO> list(String category) {
        List<SystemConfig> configs = (category != null && !category.isBlank())
                ? SystemConfig.listByCategory(category)
                : SystemConfig.listAll();
        return configs.stream().map(SystemConfigDTO::from).collect(Collectors.toList());
    }

    @Transactional(Transactional.TxType.SUPPORTS)
    public SystemConfigDTO get(String key) {
        SystemConfig cfg = SystemConfig.findByKey(key);
        if (cfg == null) {
            throw new BusinessException(404, "系统配置项不存在: " + key);
        }
        return SystemConfigDTO.from(cfg);
    }

    // ---- write operations (invalidate cache) ----

    @Transactional
    public SystemConfigDTO create(CreateSystemConfigRequest req, UUID operatorId) {
        if (SystemConfig.findByKey(req.configKey) != null) {
            throw new BusinessException(409, "配置项已存在: " + req.configKey);
        }
        SystemConfig cfg = new SystemConfig();
        cfg.configKey = req.configKey;
        cfg.configValue = req.configValue;
        cfg.defaultValue = req.defaultValue;
        cfg.dataType = req.dataType;
        cfg.category = req.category;
        cfg.description = req.description;
        cfg.modifiableBy = req.modifiableBy != null ? req.modifiableBy : "SYSTEM_ADMIN";
        cfg.createdBy = operatorId;
        cfg.updatedBy = operatorId;
        cfg.persist();
        return SystemConfigDTO.from(cfg);
    }

    @Transactional
    public SystemConfigDTO update(String key, UpdateSystemConfigRequest req, String callerRole, UUID operatorId) {
        SystemConfig cfg = SystemConfig.findByKey(key);
        if (cfg == null) {
            throw new BusinessException(404, "系统配置项不存在: " + key);
        }
        // Check modifiable_by permission
        if (!callerRole.equals("SYSTEM_ADMIN") && !callerRole.equals(cfg.modifiableBy)) {
            throw new BusinessException(403, "当前角色 " + callerRole + " 无权修改该配置项（需要: " + cfg.modifiableBy + "）");
        }
        cfg.configValue = req.configValue;
        if (req.description != null) {
            cfg.description = req.description;
        }
        cfg.updatedBy = operatorId;
        // Invalidate cache entry so next getRaw() reads the updated value from DB
        rawCache.remove(key);
        return SystemConfigDTO.from(cfg);
    }

    @Transactional
    public void delete(String key, UUID operatorId) {
        SystemConfig cfg = SystemConfig.findByKey(key);
        if (cfg == null) {
            throw new BusinessException(404, "系统配置项不存在: " + key);
        }
        cfg.delete();
        // Invalidate cache entry
        rawCache.remove(key);
    }
}
