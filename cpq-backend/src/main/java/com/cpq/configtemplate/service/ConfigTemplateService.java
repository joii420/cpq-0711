package com.cpq.configtemplate.service;

import com.cpq.common.exception.BusinessException;
import com.cpq.configtemplate.dto.*;
import com.cpq.configtemplate.entity.ConfigCategory;
import com.cpq.configtemplate.entity.ConfigItem;
import com.cpq.configtemplate.entity.ConfigTemplate;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * V203 / Phase B1: 配置模板的 CRUD + 状态机.
 *
 * <p>核心操作:
 * <ul>
 *   <li>{@link #listTemplates(String)} — 按状态过滤列出模板</li>
 *   <li>{@link #getTemplate(UUID)} — 拉单个模板含 categories.items 嵌套</li>
 *   <li>{@link #createTemplate(TemplateRequest)} — 新建 DRAFT 模板</li>
 *   <li>{@link #publishTemplate(UUID)} / {@link #archiveTemplate(UUID)} — 状态机迁移</li>
 *   <li>category / item 的 CRUD (含 ON DELETE CASCADE)</li>
 * </ul>
 */
@ApplicationScoped
public class ConfigTemplateService {

    private static final Logger LOG = Logger.getLogger(ConfigTemplateService.class);

    // ─── Template ────────────────────────────────────────────────────────────

    public List<ConfigTemplateDTO> listTemplates(String status) {
        List<ConfigTemplate> rows;
        if (status == null || status.isBlank()) {
            rows = ConfigTemplate.<ConfigTemplate>listAll();
        } else {
            rows = ConfigTemplate.<ConfigTemplate>list("status", status);
        }
        return rows.stream().map(ConfigTemplateDTO::from).collect(Collectors.toList());
    }

    /**
     * 拉单个模板, 嵌套 categories + items, items 按 sort_order 排序.
     */
    public ConfigTemplateDTO getTemplate(UUID id) {
        ConfigTemplate t = ConfigTemplate.findById(id);
        if (t == null) throw new BusinessException(404, "ConfigTemplate not found: " + id);
        ConfigTemplateDTO dto = ConfigTemplateDTO.from(t);

        List<ConfigCategory> cats = ConfigCategory.<ConfigCategory>list("templateId", id);
        cats.sort(Comparator.comparingInt((ConfigCategory c) -> c.sortOrder).thenComparing(c -> c.code));

        for (ConfigCategory c : cats) {
            ConfigCategoryDTO cdto = ConfigCategoryDTO.from(c);
            List<ConfigItem> items = ConfigItem.<ConfigItem>list("categoryId", c.id);
            items.sort(Comparator.comparingInt((ConfigItem i) -> i.sortOrder).thenComparing(i -> i.code));
            cdto.items = items.stream().map(ConfigItemDTO::from).collect(Collectors.toList());
            dto.categories.add(cdto);
        }
        return dto;
    }

    @Transactional
    public ConfigTemplateDTO createTemplate(TemplateRequest req) {
        if (ConfigTemplate.count("code", req.code) > 0) {
            throw new BusinessException(400, "code 已存在: " + req.code);
        }
        ConfigTemplate t = new ConfigTemplate();
        t.code = req.code.trim();
        t.name = req.name.trim();
        t.description = req.description;
        t.status = "DRAFT";
        t.persist();
        LOG.infof("Created ConfigTemplate id=%s code=%s", t.id, t.code);
        return ConfigTemplateDTO.from(t);
    }

    @Transactional
    public ConfigTemplateDTO updateTemplate(UUID id, TemplateRequest req) {
        ConfigTemplate t = ConfigTemplate.findById(id);
        if (t == null) throw new BusinessException(404, "ConfigTemplate not found: " + id);
        if ("ARCHIVED".equals(t.status)) {
            throw new BusinessException(400, "ARCHIVED 模板不可修改, 请先恢复为 DRAFT/PUBLISHED");
        }
        // code 变更需查重 (排除自己)
        if (!t.code.equals(req.code)) {
            long dup = ConfigTemplate.count("code = ?1 AND id <> ?2", req.code, id);
            if (dup > 0) throw new BusinessException(400, "code 已被占用: " + req.code);
            t.code = req.code.trim();
        }
        t.name = req.name.trim();
        t.description = req.description;
        return ConfigTemplateDTO.from(t);
    }

    @Transactional
    public void deleteTemplate(UUID id) {
        ConfigTemplate t = ConfigTemplate.findById(id);
        if (t == null) return;
        if ("PUBLISHED".equals(t.status)) {
            throw new BusinessException(400, "PUBLISHED 模板不可删除, 请先归档");
        }
        // ON DELETE CASCADE 会清掉 categories + items
        t.delete();
    }

    @Transactional
    public ConfigTemplateDTO publishTemplate(UUID id) {
        ConfigTemplate t = ConfigTemplate.findById(id);
        if (t == null) throw new BusinessException(404, "ConfigTemplate not found: " + id);
        if (!"DRAFT".equals(t.status)) {
            throw new BusinessException(400, "只有 DRAFT 状态可以发布, 当前: " + t.status);
        }
        // 发布前校验: 至少 1 个 active category + 至少 1 个 active item
        long activeCats = ConfigCategory.count("templateId = ?1 AND status = 'ACTIVE'", id);
        if (activeCats == 0) {
            throw new BusinessException(400, "发布前必须至少有 1 个启用的大类");
        }
        @SuppressWarnings("unchecked")
        List<UUID> catIds = (List<UUID>) ConfigCategory.<ConfigCategory>list("templateId = ?1 AND status = 'ACTIVE'", id)
                .stream().map(c -> ((ConfigCategory) c).id).collect(Collectors.toList());
        long activeItems = ConfigItem.count("categoryId IN ?1 AND status = 'ACTIVE'", catIds);
        if (activeItems == 0) {
            throw new BusinessException(400, "发布前所有大类下必须至少有 1 个启用的明细项");
        }
        t.status = "PUBLISHED";
        t.publishedAt = OffsetDateTime.now();
        return ConfigTemplateDTO.from(t);
    }

    @Transactional
    public ConfigTemplateDTO archiveTemplate(UUID id) {
        ConfigTemplate t = ConfigTemplate.findById(id);
        if (t == null) throw new BusinessException(404, "ConfigTemplate not found: " + id);
        if (!"PUBLISHED".equals(t.status) && !"DRAFT".equals(t.status)) {
            throw new BusinessException(400, "只有 DRAFT/PUBLISHED 可归档, 当前: " + t.status);
        }
        t.status = "ARCHIVED";
        return ConfigTemplateDTO.from(t);
    }

    // ─── Category ────────────────────────────────────────────────────────────

    @Transactional
    public ConfigCategoryDTO createCategory(UUID templateId, CategoryRequest req) {
        ConfigTemplate t = ConfigTemplate.findById(templateId);
        if (t == null) throw new BusinessException(404, "ConfigTemplate not found: " + templateId);
        if ("ARCHIVED".equals(t.status)) {
            throw new BusinessException(400, "ARCHIVED 模板不可加大类");
        }
        if (ConfigCategory.count("templateId = ?1 AND code = ?2", templateId, req.code) > 0) {
            throw new BusinessException(400, "大类 code 在该模板内已存在: " + req.code);
        }
        ConfigCategory c = new ConfigCategory();
        c.templateId = templateId;
        c.code = req.code.trim();
        c.name = req.name.trim();
        c.sortOrder = req.sortOrder == null ? 0 : req.sortOrder;
        c.status = (req.status == null || req.status.isBlank()) ? "ACTIVE" : req.status;
        c.persist();
        return ConfigCategoryDTO.from(c);
    }

    @Transactional
    public ConfigCategoryDTO updateCategory(UUID id, CategoryRequest req) {
        ConfigCategory c = ConfigCategory.findById(id);
        if (c == null) throw new BusinessException(404, "ConfigCategory not found: " + id);
        ConfigTemplate t = ConfigTemplate.findById(c.templateId);
        if (t != null && "ARCHIVED".equals(t.status)) {
            throw new BusinessException(400, "ARCHIVED 模板下的大类不可修改");
        }
        if (!c.code.equals(req.code)) {
            long dup = ConfigCategory.count("templateId = ?1 AND code = ?2 AND id <> ?3",
                    c.templateId, req.code, id);
            if (dup > 0) throw new BusinessException(400, "code 已被占用: " + req.code);
            c.code = req.code.trim();
        }
        c.name = req.name.trim();
        if (req.sortOrder != null) c.sortOrder = req.sortOrder;
        if (req.status != null && !req.status.isBlank()) c.status = req.status;
        return ConfigCategoryDTO.from(c);
    }

    @Transactional
    public void deleteCategory(UUID id) {
        ConfigCategory c = ConfigCategory.findById(id);
        if (c == null) return;
        ConfigTemplate t = ConfigTemplate.findById(c.templateId);
        if (t != null && "PUBLISHED".equals(t.status)) {
            throw new BusinessException(400, "PUBLISHED 模板下的大类不能删除, 请先改回 DRAFT");
        }
        c.delete();
    }

    // ─── Item ────────────────────────────────────────────────────────────────

    @Transactional
    public ConfigItemDTO createItem(UUID categoryId, ItemRequest req) {
        ConfigCategory c = ConfigCategory.findById(categoryId);
        if (c == null) throw new BusinessException(404, "ConfigCategory not found: " + categoryId);
        ConfigTemplate t = ConfigTemplate.findById(c.templateId);
        if (t != null && "ARCHIVED".equals(t.status)) {
            throw new BusinessException(400, "ARCHIVED 模板下的明细项不可新增");
        }
        if (ConfigItem.count("categoryId = ?1 AND code = ?2", categoryId, req.code) > 0) {
            throw new BusinessException(400, "明细项 code 在该大类内已存在: " + req.code);
        }
        ConfigItem i = new ConfigItem();
        i.categoryId = categoryId;
        i.code = req.code.trim();
        i.name = req.name.trim();
        i.defaultValue = req.defaultValue;
        i.sortOrder = req.sortOrder == null ? 0 : req.sortOrder;
        i.status = (req.status == null || req.status.isBlank()) ? "ACTIVE" : req.status;
        i.persist();
        return ConfigItemDTO.from(i);
    }

    @Transactional
    public ConfigItemDTO updateItem(UUID id, ItemRequest req) {
        ConfigItem i = ConfigItem.findById(id);
        if (i == null) throw new BusinessException(404, "ConfigItem not found: " + id);
        ConfigCategory c = ConfigCategory.findById(i.categoryId);
        ConfigTemplate t = c == null ? null : ConfigTemplate.findById(c.templateId);
        if (t != null && "ARCHIVED".equals(t.status)) {
            throw new BusinessException(400, "ARCHIVED 模板下的明细项不可修改");
        }
        if (!i.code.equals(req.code)) {
            long dup = ConfigItem.count("categoryId = ?1 AND code = ?2 AND id <> ?3",
                    i.categoryId, req.code, id);
            if (dup > 0) throw new BusinessException(400, "code 已被占用: " + req.code);
            i.code = req.code.trim();
        }
        i.name = req.name.trim();
        i.defaultValue = req.defaultValue;
        if (req.sortOrder != null) i.sortOrder = req.sortOrder;
        if (req.status != null && !req.status.isBlank()) i.status = req.status;
        return ConfigItemDTO.from(i);
    }

    @Transactional
    public void deleteItem(UUID id) {
        ConfigItem i = ConfigItem.findById(id);
        if (i == null) return;
        ConfigCategory c = ConfigCategory.findById(i.categoryId);
        ConfigTemplate t = c == null ? null : ConfigTemplate.findById(c.templateId);
        if (t != null && "PUBLISHED".equals(t.status)) {
            throw new BusinessException(400, "PUBLISHED 模板下的明细项不能删除, 请先改回 DRAFT");
        }
        i.delete();
    }
}
