package com.cpq.system.service;

import com.cpq.common.dto.PageResult;
import com.cpq.common.exception.BusinessException;
import com.cpq.system.dto.DepartmentDTO;
import com.cpq.system.entity.Department;
import com.cpq.system.entity.User;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class DepartmentService {

    private static final Logger LOG = Logger.getLogger(DepartmentService.class);

    public PageResult<DepartmentDTO> list(int page, int size) {
        page = com.cpq.common.dto.Pagination.clampPage(page);
        size = com.cpq.common.dto.Pagination.clampSize(size);
        long total = Department.count();
        List<DepartmentDTO> content = Department
                .find("ORDER BY sortOrder ASC")
                .page(page, size)
                .list()
                .stream()
                .map(d -> DepartmentDTO.from((Department) d))
                .collect(Collectors.toList());
        LOG.debugf("list departments page=%d size=%d total=%d", page, size, total);
        return new PageResult<>(content, page, size, total);
    }

    @Transactional
    public DepartmentDTO create(DepartmentDTO dto) {
        long existing = Department.count("code = ?1", dto.code);
        if (existing > 0) {
            throw new BusinessException("Department code already exists: " + dto.code);
        }
        Department department = new Department();
        department.code = dto.code;
        department.name = dto.name;
        department.sortOrder = dto.sortOrder != null ? dto.sortOrder : 0;
        department.status = dto.status != null ? dto.status : "ACTIVE";
        department.parentId = dto.parentId;
        if (dto.parentId != null) {
            Department parent = Department.findById(dto.parentId);
            if (parent == null) {
                throw new BusinessException(400, "Parent department not found: " + dto.parentId);
            }
        }
        department.persist();
        LOG.infof("Created department code=%s name=%s", department.code, department.name);
        return DepartmentDTO.from(department);
    }

    @Transactional
    public DepartmentDTO update(UUID id, DepartmentDTO dto) {
        Department department = Department.findById(id);
        if (department == null) {
            throw new BusinessException(404, "Department not found: " + id);
        }
        department.name = dto.name;
        if (dto.parentId != null) {
            // Prevent self-reference
            if (dto.parentId.equals(id)) {
                throw new BusinessException(400, "Department cannot be its own parent");
            }
            // Prevent circular reference - check if new parent is a descendant of this dept
            UUID checkId = dto.parentId;
            while (checkId != null) {
                if (checkId.equals(id)) {
                    throw new BusinessException(400, "Circular parent reference detected");
                }
                Department ancestor = Department.findById(checkId);
                checkId = ancestor != null ? ancestor.parentId : null;
            }
            department.parentId = dto.parentId;
        } else {
            department.parentId = dto.parentId; // allow setting to null (make top-level)
        }
        if (dto.sortOrder != null) {
            department.sortOrder = dto.sortOrder;
        }
        LOG.infof("Updated department id=%s name=%s", id, department.name);
        return DepartmentDTO.from(department);
    }

    @Transactional
    public DepartmentDTO updateStatus(UUID id, String status) {
        Department department = Department.findById(id);
        if (department == null) {
            throw new BusinessException(404, "Department not found: " + id);
        }
        if ("DISABLED".equals(status)) {
            long activeChildren = Department.count("parentId = ?1 AND status = 'ACTIVE'", id);
            if (activeChildren > 0) {
                throw new BusinessException("该部门下有 " + activeChildren + " 个启用的子部门，请先停用子部门");
            }
            long activeUsers = User.count("departmentId = ?1 AND status = 'ACTIVE'", id);
            if (activeUsers > 0) {
                throw new BusinessException("Cannot disable department with active users: " + activeUsers + " active user(s)");
            }
        }
        department.status = status;
        LOG.infof("Updated department id=%s status=%s", id, status);
        return DepartmentDTO.from(department);
    }
}
