package com.cpq.seltemplate.service;

import com.cpq.basicdata.entity.ProductCategory;
import com.cpq.customer.entity.Customer;
import com.cpq.seltemplate.dto.EffectiveTemplateDTO;
import com.cpq.seltemplate.dto.ParamCandidateDTO;
import com.cpq.seltemplate.dto.SelTemplateDTO;
import com.cpq.seltemplate.entity.SelParamType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class EffectiveTemplateService {

    @Inject SelTemplateService selTemplateService;
    @Inject SelParamCandidateService candidateService;

    /**
     * 解析某客户(业务码)的有效选配模板：
     * 客户所属产品分类 → 该分类模板；无则回退"默认分类"模板；再无则 hasTemplate=false。
     * 每个 enabled 参数的 effectiveValues = 候选值 ∩ 模板 allowedValues（allowedValues 空=全部候选）。
     *
     * task-0712 update-071501 D10: 原 __DEFAULT__ 哨兵换成真实的 name='默认分类' 产品分类，兜底链逻辑不变。
     */
    public EffectiveTemplateDTO getEffective(String customerNo) {
        EffectiveTemplateDTO out = new EffectiveTemplateDTO();
        out.customerNo = customerNo;

        Customer customer = Customer.find("code", customerNo).firstResult();
        UUID categoryId = customer == null ? null : customer.productCategoryId;

        SelTemplateDTO tpl = null;
        if (categoryId != null) {
            tpl = selTemplateService.getByCategory(categoryId);
            if (tpl != null) { out.resolvedCategoryId = categoryId; out.usedDefault = false; }
        }
        if (tpl == null) {
            UUID defaultCategoryId = ProductCategory.requireDefaultId();
            tpl = selTemplateService.getByCategory(defaultCategoryId);
            if (tpl != null) { out.resolvedCategoryId = defaultCategoryId; out.usedDefault = true; }
        }
        if (tpl == null) {
            out.hasTemplate = false;
            return out;
        }
        out.hasTemplate = true;
        out.templateId = tpl.id;

        for (SelTemplateDTO.Item item : tpl.items) {
            if (!item.enabled) continue;
            SelParamType pt = SelParamType.findById(item.paramTypeCode);
            if (pt == null) continue;
            EffectiveTemplateDTO.Param p = new EffectiveTemplateDTO.Param();
            p.paramTypeCode = pt.code;
            p.name = pt.name;
            p.valueMode = pt.valueMode;

            List<ParamCandidateDTO> candidates = candidateService.candidates(pt.code);
            Set<String> allowed = item.allowedValues == null ? Set.of()
                    : item.allowedValues.stream().collect(Collectors.toSet());
            for (ParamCandidateDTO c : candidates) {
                if (allowed.isEmpty() || allowed.contains(c.key)) {
                    p.effectiveValues.add(new EffectiveTemplateDTO.Value(c.key, c.label));
                }
            }
            out.params.add(p);
        }
        return out;
    }
}
