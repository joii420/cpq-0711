package com.cpq.product.service;

import com.cpq.common.exception.BusinessException;
import com.cpq.product.dto.ProcessDTO;
import com.cpq.product.dto.ProductProcessDTO;
import com.cpq.product.entity.Process;
import com.cpq.product.entity.ProductProcess;
import com.cpq.template.entity.ProductTemplateBinding;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@ApplicationScoped
public class ProcessService {

    private static final Logger LOG = Logger.getLogger(ProcessService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public List<ProcessDTO> listAll() {
        return Process.<Process>find("ORDER BY category, sortOrder")
                .list()
                .stream()
                .map(ProcessDTO::from)
                .collect(Collectors.toList());
    }

    public List<ProcessDTO> listByCategory(String category) {
        return Process.<Process>find("category = ?1 ORDER BY sortOrder", category)
                .list()
                .stream()
                .map(ProcessDTO::from)
                .collect(Collectors.toList());
    }

    public List<UUID> getProductProcessIds(UUID productId) {
        return ProductProcess.<ProductProcess>find("productId = ?1 ORDER BY sortOrder", productId)
                .list()
                .stream()
                .map(pp -> pp.processId)
                .collect(Collectors.toList());
    }

    public List<ProductProcessDTO> getProductProcesses(UUID productId) {
        List<ProductProcess> ppList = ProductProcess.<ProductProcess>find(
                "productId = ?1 ORDER BY sortOrder", productId).list();
        if (ppList.isEmpty()) return List.of();

        List<UUID> processIds = ppList.stream().map(pp -> pp.processId).collect(Collectors.toList());
        Map<UUID, Process> processMap = Process.<Process>find("id IN ?1", processIds)
                .list().stream()
                .collect(Collectors.toMap(p -> p.id, Function.identity()));

        return ppList.stream()
                .filter(pp -> processMap.containsKey(pp.processId))
                .map(pp -> ProductProcessDTO.from(pp, processMap.get(pp.processId)))
                .collect(Collectors.toList());
    }

    @Transactional
    public void bindProcesses(UUID productId, List<Map<String, Object>> processItems) {
        // Collect new process IDs and required IDs
        Set<UUID> newProcessIds = new LinkedHashSet<>();
        Set<UUID> newRequiredIds = new LinkedHashSet<>();
        for (Map<String, Object> item : processItems) {
            UUID pid = UUID.fromString(String.valueOf(item.get("processId")));
            newProcessIds.add(pid);
            if (Boolean.TRUE.equals(item.get("isRequired"))) {
                newRequiredIds.add(pid);
            }
        }

        // Load existing process IDs for this product
        Set<UUID> existingProcessIds = ProductProcess.<ProductProcess>find("productId", productId)
                .list().stream().map(pp -> pp.processId).collect(Collectors.toSet());

        // Load all template bindings for this product
        List<ProductTemplateBinding> bindings = ProductTemplateBinding
                .<ProductTemplateBinding>list("productId", productId);

        // Parse binding processIds into sets
        List<BindingProcessInfo> bindingInfos = bindings.stream().map(b -> {
            Set<String> ids = parseProcessIds(b.processIds);
            return new BindingProcessInfo(b.id, ids);
        }).collect(Collectors.toList());

        // Load process name map for error messages
        Map<UUID, String> processNameMap = loadProcessNameMap();

        // Rule 1: Cannot remove a process that is referenced by any template binding
        Set<UUID> removedProcessIds = new LinkedHashSet<>(existingProcessIds);
        removedProcessIds.removeAll(newProcessIds);
        for (UUID removedId : removedProcessIds) {
            for (BindingProcessInfo info : bindingInfos) {
                if (info.processIds.contains(removedId.toString())) {
                    String processName = processNameMap.getOrDefault(removedId, removedId.toString());
                    throw new BusinessException(
                        "无法取消工序「" + processName + "」，该工序已被产品模板绑定引用，请先调整相关绑定后再取消"
                    );
                }
            }
        }

        // Rule 2: Cannot set a process as required if any binding does NOT include it
        for (UUID requiredId : newRequiredIds) {
            for (BindingProcessInfo info : bindingInfos) {
                if (!info.processIds.contains(requiredId.toString())) {
                    String processName = processNameMap.getOrDefault(requiredId, requiredId.toString());
                    throw new BusinessException(
                        "无法将工序「" + processName + "」设为必选，存在未引用该工序的模板绑定，请先调整相关绑定后再设置"
                    );
                }
            }
        }

        // Validation passed — delete existing and insert new
        ProductProcess.delete("productId", productId);

        for (int i = 0; i < processItems.size(); i++) {
            Map<String, Object> item = processItems.get(i);
            ProductProcess pp = new ProductProcess();
            pp.productId = productId;
            pp.processId = UUID.fromString(String.valueOf(item.get("processId")));
            pp.sortOrder = item.containsKey("sortOrder") ? ((Number) item.get("sortOrder")).intValue() : i;
            pp.isRequired = item.containsKey("isRequired") ? Boolean.TRUE.equals(item.get("isRequired")) : false;
            pp.persist();
        }

        LOG.infof("Bound %d processes to productId=%s", processItems.size(), productId);
    }

    private Set<String> parseProcessIds(String json) {
        if (json == null || json.isBlank()) return Set.of();
        try {
            List<String> ids = MAPPER.readValue(json, new TypeReference<List<String>>() {});
            return new LinkedHashSet<>(ids);
        } catch (Exception e) {
            LOG.debugf("Failed to parse processIds JSON: %s", e.getMessage());
            return Set.of();
        }
    }

    private Map<UUID, String> loadProcessNameMap() {
        return Process.<Process>listAll().stream()
                .collect(Collectors.toMap(p -> p.id, p -> p.name));
    }

    private static class BindingProcessInfo {
        final UUID bindingId;
        final Set<String> processIds;

        BindingProcessInfo(UUID bindingId, Set<String> processIds) {
            this.bindingId = bindingId;
            this.processIds = processIds;
        }
    }

    @Transactional
    public void unbindAll(UUID productId) {
        long deleted = ProductProcess.delete("productId", productId);
        LOG.infof("Unbound %d processes from productId=%s", deleted, productId);
    }
}
