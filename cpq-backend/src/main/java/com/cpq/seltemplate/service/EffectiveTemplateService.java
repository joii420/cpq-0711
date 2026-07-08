package com.cpq.seltemplate.service;

import com.cpq.customer.entity.Customer;
import com.cpq.seltemplate.dto.EffectiveTemplateDTO;
import com.cpq.seltemplate.dto.ParamCandidateDTO;
import com.cpq.seltemplate.dto.SelTemplateDTO;
import com.cpq.seltemplate.entity.SelParamType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@ApplicationScoped
public class EffectiveTemplateService {

    public static final String DEFAULT_INDUSTRY = "__DEFAULT__";

    @Inject SelTemplateService selTemplateService;
    @Inject SelParamCandidateService candidateService;

    /**
     * 解析某客户(业务码)的有效选配模板：
     * 客户所属行业 → 行业模板；无则回退 __DEFAULT__；再无则 hasTemplate=false。
     * 每个 enabled 参数的 effectiveValues = 候选值 ∩ 模板 allowedValues（allowedValues 空=全部候选）。
     */
    public EffectiveTemplateDTO getEffective(String customerNo) {
        EffectiveTemplateDTO out = new EffectiveTemplateDTO();
        out.customerNo = customerNo;

        Customer customer = Customer.find("code", customerNo).firstResult();
        String industryCode = customer == null ? null : customer.industryCode;

        SelTemplateDTO tpl = null;
        if (industryCode != null && !industryCode.isBlank()) {
            tpl = selTemplateService.getByIndustry(industryCode);
            if (tpl != null) { out.resolvedIndustryCode = industryCode; out.usedDefault = false; }
        }
        if (tpl == null) {
            tpl = selTemplateService.getByIndustry(DEFAULT_INDUSTRY);
            if (tpl != null) { out.resolvedIndustryCode = DEFAULT_INDUSTRY; out.usedDefault = true; }
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
