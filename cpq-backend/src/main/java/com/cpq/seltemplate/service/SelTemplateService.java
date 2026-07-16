package com.cpq.seltemplate.service;

import com.cpq.common.exception.BusinessException;
import com.cpq.seltemplate.dto.SelTemplateDTO;
import com.cpq.seltemplate.dto.SelTemplateUpsertRequest;
import com.cpq.seltemplate.entity.SelTemplate;
import com.cpq.seltemplate.entity.SelTemplateItem;
import com.cpq.seltemplate.entity.SelTemplateItemValue;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class SelTemplateService {

    public List<SelTemplateDTO> list() {
        return SelTemplate.<SelTemplate>find("ORDER BY createdAt DESC").list()
                .stream().map(this::assemble).collect(Collectors.toList());
    }

    public SelTemplateDTO getById(UUID id) {
        SelTemplate t = SelTemplate.findById(id);
        if (t == null) throw new BusinessException("选配模板不存在");
        return assemble(t);
    }

    /** 按产品分类取模板（选配运行时/Plan 3 会用）。无则返回 null。 */
    public SelTemplateDTO getByCategory(UUID productCategoryId) {
        SelTemplate t = SelTemplate.find("productCategoryId", productCategoryId).firstResult();
        return t == null ? null : assemble(t);
    }

    private SelTemplateDTO assemble(SelTemplate t) {
        SelTemplateDTO d = SelTemplateDTO.from(t);
        List<SelTemplateItem> items = SelTemplateItem.<SelTemplateItem>find(
                "templateId = ?1 ORDER BY sortOrder", t.id).list();
        for (SelTemplateItem it : items) {
            SelTemplateDTO.Item dto = new SelTemplateDTO.Item();
            dto.paramTypeCode = it.paramTypeCode;
            dto.enabled = it.enabled;
            dto.sortOrder = it.sortOrder;
            dto.allowedValues = SelTemplateItemValue.<SelTemplateItemValue>find("itemId", it.id)
                    .list().stream().map(v -> v.allowedValueKey).collect(Collectors.toList());
            d.items.add(dto);
        }
        return d;
    }

    @Transactional
    public SelTemplateDTO upsert(SelTemplateUpsertRequest req) {
        // api.md §1.1: productCategoryId 必须为存在的 product_category.id，不存在报 400
        if (com.cpq.basicdata.entity.ProductCategory.findById(req.productCategoryId) == null) {
            throw new BusinessException(400, "产品分类不存在: " + req.productCategoryId);
        }
        // 一产品分类一套：按 productCategoryId 找既有，有则更新，无则新建
        SelTemplate t = SelTemplate.find("productCategoryId", req.productCategoryId).firstResult();
        if (t == null) {
            t = new SelTemplate();
            t.productCategoryId = req.productCategoryId;
        }
        t.name = req.name;
        if (req.status != null && !req.status.isBlank()) t.status = req.status;
        t.persist();

        // 全量替换 items + values（简单可靠；模板规模小）
        List<SelTemplateItem> old = SelTemplateItem.<SelTemplateItem>find("templateId", t.id).list();
        for (SelTemplateItem it : old) {
            SelTemplateItemValue.delete("itemId", it.id);
        }
        SelTemplateItem.delete("templateId", t.id);

        int order = 0;
        for (SelTemplateUpsertRequest.Item ri : req.items) {
            SelTemplateItem it = new SelTemplateItem();
            it.templateId = t.id;
            it.paramTypeCode = ri.paramTypeCode;
            it.enabled = ri.enabled;
            it.sortOrder = order++;
            it.persist();
            if (ri.allowedValues != null) {
                for (String v : ri.allowedValues) {
                    if (v == null || v.isBlank()) continue;
                    SelTemplateItemValue tv = new SelTemplateItemValue();
                    tv.itemId = it.id;
                    tv.allowedValueKey = v;
                    tv.persist();
                }
            }
        }
        return assemble(t);
    }

    @Transactional
    public void delete(UUID id) {
        SelTemplate t = SelTemplate.findById(id);
        if (t == null) return;
        List<SelTemplateItem> items = SelTemplateItem.<SelTemplateItem>find("templateId", t.id).list();
        for (SelTemplateItem it : items) SelTemplateItemValue.delete("itemId", it.id);
        SelTemplateItem.delete("templateId", t.id);
        t.delete();
    }
}
