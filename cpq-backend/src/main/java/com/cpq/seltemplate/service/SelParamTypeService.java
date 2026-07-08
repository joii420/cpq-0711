package com.cpq.seltemplate.service;

import com.cpq.seltemplate.dto.SelParamTypeDTO;
import com.cpq.seltemplate.entity.SelParamType;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.stream.Collectors;

@ApplicationScoped
public class SelParamTypeService {
    public List<SelParamTypeDTO> listAll() {
        return SelParamType.<SelParamType>find("ORDER BY sortOrder").list()
                .stream().map(SelParamTypeDTO::from).collect(Collectors.toList());
    }
}
