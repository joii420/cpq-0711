package com.cpq.seltemplate.service;

import com.cpq.common.exception.BusinessException;
import com.cpq.seltemplate.dto.ParamCandidateDTO;
import com.cpq.seltemplate.entity.SelParamType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@ApplicationScoped
public class SelParamCandidateService {

    @Inject com.cpq.configure.service.MaterialRecipeService materialRecipeService;
    @Inject com.cpq.basicdata.v6.service.ProcessMasterReadService processMasterReadService;

    /** 按参数类型的 data_source_key 取可选值候选。adjust 类(元素含量)返回空(启用即微调,不限值)。 */
    public List<ParamCandidateDTO> candidates(String paramTypeCode) {
        SelParamType pt = SelParamType.findById(paramTypeCode);
        if (pt == null) throw new BusinessException("参数类型不存在: " + paramTypeCode);
        if (pt.dataSourceKey == null) return Collections.emptyList();
        switch (pt.dataSourceKey) {
            case "MATERIAL_RECIPE":
                // MaterialRecipeService.listActive() 返回 List<MaterialRecipeDTO>；字段 code/name
                return materialRecipeService.listActive().stream()
                        .map(r -> new ParamCandidateDTO(r.code, r.name))
                        .collect(Collectors.toList());
            case "V6_PROCESS_MASTER":
                // ProcessMasterReadService.list(page,size,keyword) 返回 PageResult<ProcessMasterDTO>；
                // 字段 processNo/processName；全量取用 size=200(> 现役工序条数)
                return processMasterReadService.list(0, 200, null).getContent().stream()
                        .map(p -> new ParamCandidateDTO(p.processNo, p.processName))
                        .collect(Collectors.toList());
            default:
                return Collections.emptyList();
        }
    }
}
