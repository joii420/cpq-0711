package com.cpq.component.dto;

import com.cpq.component.entity.ComponentDirectory;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class ComponentDirectoryDTO {

    public UUID id;
    public UUID parentId;
    public String name;
    public Integer sortOrder;
    public OffsetDateTime createdAt;
    public List<ComponentDirectoryDTO> children = new ArrayList<>();
    public List<ComponentDTO> components = new ArrayList<>();

    public static ComponentDirectoryDTO from(ComponentDirectory dir) {
        ComponentDirectoryDTO dto = new ComponentDirectoryDTO();
        dto.id = dir.id;
        dto.parentId = dir.parentId;
        dto.name = dir.name;
        dto.sortOrder = dir.sortOrder;
        dto.createdAt = dir.createdAt;
        return dto;
    }
}
