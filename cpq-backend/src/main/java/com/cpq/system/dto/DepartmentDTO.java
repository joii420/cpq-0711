package com.cpq.system.dto;

import com.cpq.system.entity.Department;
import jakarta.validation.constraints.NotBlank;

import java.time.OffsetDateTime;
import java.util.UUID;

public class DepartmentDTO {

    public UUID id;

    public UUID parentId;

    @NotBlank
    public String code;

    @NotBlank
    public String name;

    public Integer sortOrder;

    public String status;

    public OffsetDateTime createdAt;

    public java.util.List<DepartmentDTO> children;

    public static DepartmentDTO from(Department department) {
        DepartmentDTO dto = new DepartmentDTO();
        dto.id = department.id;
        dto.parentId = department.parentId;
        dto.code = department.code;
        dto.name = department.name;
        dto.sortOrder = department.sortOrder;
        dto.status = department.status;
        dto.createdAt = department.createdAt;
        return dto;
    }
}
