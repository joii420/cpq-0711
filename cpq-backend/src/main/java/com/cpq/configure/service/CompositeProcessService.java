package com.cpq.configure.service;

import com.cpq.configure.dto.CompositeProcessDefDTO;
import com.cpq.configure.entity.CompositeProcessDef;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.stream.Collectors;

@ApplicationScoped
public class CompositeProcessService {

    public List<CompositeProcessDefDTO> listActive() {
        return CompositeProcessDef.<CompositeProcessDef>find(
                "status = 'ACTIVE' ORDER BY sortOrder").list()
            .stream().map(this::toDTO).collect(Collectors.toList());
    }

    private CompositeProcessDefDTO toDTO(CompositeProcessDef d) {
        CompositeProcessDefDTO dto = new CompositeProcessDefDTO();
        dto.id = d.id;
        dto.code = d.code;
        dto.name = d.name;
        dto.icon = d.icon;
        dto.description = d.description;
        dto.paramSchema = d.paramSchema;
        dto.sortOrder = d.sortOrder;
        return dto;
    }
}
