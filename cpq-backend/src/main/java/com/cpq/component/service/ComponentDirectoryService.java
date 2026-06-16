package com.cpq.component.service;

import com.cpq.common.exception.BusinessException;
import com.cpq.component.dto.ComponentDirectoryDTO;
import com.cpq.component.dto.ComponentDTO;
import com.cpq.component.entity.Component;
import com.cpq.component.entity.ComponentDirectory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.util.*;
import java.util.stream.Collectors;

@ApplicationScoped
public class ComponentDirectoryService {

    private static final Logger LOG = Logger.getLogger(ComponentDirectoryService.class);

    public List<ComponentDirectoryDTO> listTree() {
        return buildTree(null);
    }

    public List<ComponentDirectoryDTO> listTree(String keyword) {
        return buildTree(keyword);
    }

    private List<ComponentDirectoryDTO> buildTree(String keyword) {
        List<ComponentDirectory> allDirs = ComponentDirectory.listAll();
        List<Component> allComponents = Component.list("ORDER BY createdAt ASC");

        // Filter by keyword if provided
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim().toLowerCase();
            allComponents = allComponents.stream()
                .filter(c -> c.name.toLowerCase().contains(kw) || c.code.toLowerCase().contains(kw))
                .collect(Collectors.toList());
        }

        // Build DTO map
        Map<UUID, ComponentDirectoryDTO> dtoMap = new LinkedHashMap<>();
        for (ComponentDirectory dir : allDirs) {
            dtoMap.put(dir.id, ComponentDirectoryDTO.from(dir));
        }

        // Attach components to their directories
        for (Component comp : allComponents) {
            if (comp.directoryId != null && dtoMap.containsKey(comp.directoryId)) {
                dtoMap.get(comp.directoryId).components.add(ComponentDTO.from(comp));
            }
        }

        // Build tree (attach children to parents)
        List<ComponentDirectoryDTO> roots = new ArrayList<>();
        for (ComponentDirectoryDTO dto : dtoMap.values()) {
            if (dto.parentId == null) {
                roots.add(dto);
            } else {
                ComponentDirectoryDTO parent = dtoMap.get(dto.parentId);
                if (parent != null) {
                    parent.children.add(dto);
                } else {
                    // Orphaned node — add as root
                    roots.add(dto);
                }
            }
        }

        roots.sort(Comparator.comparingInt(d -> (d.sortOrder == null ? 0 : d.sortOrder)));
        LOG.debugf("listTree: %d root directories", roots.size());
        return roots;
    }

    @Transactional
    public ComponentDirectoryDTO create(String name, UUID parentId, Integer sortOrder) {
        if (name == null || name.isBlank()) {
            throw new BusinessException("目录名称不能为空");
        }
        if (parentId != null) {
            ComponentDirectory parent = ComponentDirectory.findById(parentId);
            if (parent == null) {
                throw new BusinessException("父目录不存在：" + parentId);
            }
        }
        ComponentDirectory dir = new ComponentDirectory();
        dir.name = name.trim();
        dir.parentId = parentId;
        dir.sortOrder = sortOrder != null ? sortOrder : 0;
        dir.persist();
        LOG.infof("Created component directory id=%s name=%s", dir.id, dir.name);
        return ComponentDirectoryDTO.from(dir);
    }

    @Transactional
    public ComponentDirectoryDTO update(UUID id, String name, Integer sortOrder) {
        ComponentDirectory dir = ComponentDirectory.findById(id);
        if (dir == null) {
            throw new BusinessException(404, "目录不存在：" + id);
        }
        if (name != null && !name.isBlank()) {
            dir.name = name.trim();
        }
        if (sortOrder != null) {
            dir.sortOrder = sortOrder;
        }
        LOG.infof("Updated component directory id=%s name=%s", id, dir.name);
        return ComponentDirectoryDTO.from(dir);
    }

    @Transactional
    public void delete(UUID id) {
        ComponentDirectory dir = ComponentDirectory.findById(id);
        if (dir == null) {
            throw new BusinessException(404, "目录不存在：" + id);
        }
        // Check for children
        long childCount = ComponentDirectory.count("parentId", id);
        if (childCount > 0) {
            throw new BusinessException("无法删除：该目录下还有子目录，请先删除或移走子目录");
        }
        // Check for components
        long compCount = Component.count("directoryId", id);
        if (compCount > 0) {
            throw new BusinessException("无法删除：该目录下还有组件，请先删除或移走组件");
        }
        dir.delete();
        LOG.infof("Deleted component directory id=%s", id);
    }
}
