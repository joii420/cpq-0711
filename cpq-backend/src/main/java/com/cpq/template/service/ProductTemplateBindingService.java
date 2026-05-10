package com.cpq.template.service;

import com.cpq.common.exception.BusinessException;
import com.cpq.template.dto.CreateBindingRequest;
import com.cpq.template.dto.ProductTemplateBindingDTO;
import com.cpq.template.entity.ProductTemplateBinding;
import com.cpq.template.entity.Template;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class ProductTemplateBindingService {

    private static final Logger LOG = Logger.getLogger(ProductTemplateBindingService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public List<ProductTemplateBindingDTO> listByProduct(UUID productId) {
        List<ProductTemplateBinding> bindings = ProductTemplateBinding.list(
            "productId = ?1 ORDER BY createdAt DESC", productId
        );
        return bindings.stream().map(ProductTemplateBindingDTO::from).collect(Collectors.toList());
    }

    @Transactional
    public ProductTemplateBindingDTO create(CreateBindingRequest request) {
        if (request.productId == null) {
            throw new BusinessException("productId is required");
        }
        if (request.templateId == null) {
            throw new BusinessException("templateId is required");
        }

        // Validate template is PUBLISHED
        Template template = Template.findById(request.templateId);
        if (template == null) {
            throw new BusinessException(404, "Template not found: " + request.templateId);
        }
        if (!"PUBLISHED".equals(template.status)) {
            throw new BusinessException("Only PUBLISHED templates can be bound");
        }

        List<String> processIds = request.processIds == null ? new ArrayList<>() : request.processIds;
        String hash = computeHash(processIds);

        // Check unique constraint
        long existing = ProductTemplateBinding.count(
            "productId = ?1 AND processIdsHash = ?2 AND templateId = ?3",
            request.productId, hash, request.templateId
        );
        if (existing > 0) {
            throw new BusinessException("Binding already exists for this product/process/template combination");
        }

        ProductTemplateBinding binding = new ProductTemplateBinding();
        binding.productId = request.productId;
        binding.processIds = toJson(processIds);
        binding.processIdsHash = hash;
        binding.templateId = request.templateId;
        binding.isDefault = request.isDefault != null && request.isDefault;

        // If setting as default, unset existing defaults for same product+hash
        if (Boolean.TRUE.equals(binding.isDefault)) {
            clearDefaults(request.productId, hash);
        }

        binding.persist();
        LOG.infof("Created binding id=%s productId=%s templateId=%s", binding.id, binding.productId, binding.templateId);
        return ProductTemplateBindingDTO.from(binding);
    }

    @Transactional
    public void delete(UUID bindingId) {
        ProductTemplateBinding binding = ProductTemplateBinding.findById(bindingId);
        if (binding == null) {
            throw new BusinessException(404, "Binding not found: " + bindingId);
        }
        binding.delete();
        LOG.infof("Deleted binding id=%s", bindingId);
    }

    @Transactional
    public ProductTemplateBindingDTO setDefault(UUID bindingId) {
        ProductTemplateBinding binding = ProductTemplateBinding.findById(bindingId);
        if (binding == null) {
            throw new BusinessException(404, "Binding not found: " + bindingId);
        }
        // Unset all other defaults for this product+hash
        clearDefaults(binding.productId, binding.processIdsHash);
        binding.isDefault = true;
        LOG.infof("Set default binding id=%s productId=%s hash=%s", bindingId, binding.productId, binding.processIdsHash);
        return ProductTemplateBindingDTO.from(binding);
    }

    public List<ProductTemplateBindingDTO> matchTemplates(UUID productId, List<UUID> processIds) {
        List<String> stringIds = processIds == null ? new ArrayList<>() :
            processIds.stream().map(UUID::toString).collect(Collectors.toList());
        String hash = computeHash(stringIds);
        List<ProductTemplateBinding> bindings = ProductTemplateBinding.list(
            "productId = ?1 AND processIdsHash = ?2 ORDER BY isDefault DESC, createdAt DESC",
            productId, hash
        );
        return bindings.stream().map(ProductTemplateBindingDTO::from).collect(Collectors.toList());
    }

    // ---- Private helpers ----

    private void clearDefaults(UUID productId, String hash) {
        List<ProductTemplateBinding> existing = ProductTemplateBinding.list(
            "productId = ?1 AND processIdsHash = ?2 AND isDefault = true", productId, hash
        );
        for (ProductTemplateBinding b : existing) {
            b.isDefault = false;
        }
    }

    public static String computeHash(List<String> processIds) {
        if (processIds == null || processIds.isEmpty()) {
            return sha256("[]");
        }
        // Sort and join with commas for deterministic hash
        List<String> sorted = new ArrayList<>(processIds);
        Collections.sort(sorted);
        String joined = String.join(",", sorted);
        return sha256(joined);
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    private String toJson(Object obj) {
        try {
            return MAPPER.writeValueAsString(obj);
        } catch (Exception e) {
            return "[]";
        }
    }
}
