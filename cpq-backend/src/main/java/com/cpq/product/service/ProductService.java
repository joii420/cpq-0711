package com.cpq.product.service;

import com.cpq.common.dto.PageResult;
import com.cpq.common.exception.BusinessException;
import com.cpq.product.dto.CreateProductRequest;
import com.cpq.product.dto.ProductDTO;
import com.cpq.product.entity.Product;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class ProductService {

    private static final Logger LOG = Logger.getLogger(ProductService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject
    EntityManager em;

    public PageResult<ProductDTO> list(int page, int size, String category, String status, String keyword) {
        return list(page, size, category, null, status, keyword);
    }

    public PageResult<ProductDTO> list(int page, int size, String category, UUID categoryId, String status, String keyword) {
        page = com.cpq.common.dto.Pagination.clampPage(page);
        size = com.cpq.common.dto.Pagination.clampSize(size);
        StringBuilder query = new StringBuilder("1=1");
        Map<String, Object> params = new HashMap<>();

        if (categoryId != null) {
            query.append(" AND categoryId = :categoryId");
            params.put("categoryId", categoryId);
        } else if (category != null && !category.isBlank()) {
            query.append(" AND category = :category");
            params.put("category", category);
        }
        if (status != null && !status.isBlank()) {
            query.append(" AND status = :status");
            params.put("status", status);
        }
        if (keyword != null && !keyword.isBlank()) {
            query.append(" AND (name LIKE :kw OR partNo LIKE :kw)");
            params.put("kw", "%" + keyword + "%");
        }

        long total = Product.count(query.toString(), params);
        List<ProductDTO> content = Product
                .find(query + " ORDER BY createdAt DESC", params)
                .page(page, size)
                .<Product>list()
                .stream()
                .map(ProductDTO::from)
                .collect(Collectors.toList());

        LOG.debugf("list products page=%d size=%d total=%d", page, size, total);
        return new PageResult<>(content, page, size, total);
    }

    @Transactional
    public ProductDTO create(CreateProductRequest request) {
        // Check part_no uniqueness
        long count = Product.count("partNo", request.partNo);
        if (count > 0) {
            throw new BusinessException("Part No already exists: " + request.partNo);
        }

        Product product = new Product();
        product.name = request.name;
        product.partNo = request.partNo;
        product.categoryId = resolveCategoryId(request);
        product.category = resolveCategoryName(request, product.categoryId);
        product.specification = request.specification;
        product.drawingNo = request.drawingNo;
        product.dimension = request.dimension;
        product.material = request.material;
        product.status = request.status != null ? request.status : "ACTIVE";
        product.tags = toJsonArray(request.tags);
        product.persist();

        LOG.infof("Created product partNo=%s name=%s", product.partNo, product.name);
        return ProductDTO.from(product);
    }

    @Transactional
    public ProductDTO update(UUID id, CreateProductRequest request) {
        Product product = Product.findById(id);
        if (product == null) {
            throw new BusinessException(404, "Product not found: " + id);
        }

        // Check part_no uniqueness if changing partNo
        if (request.partNo != null && !request.partNo.equals(product.partNo)) {
            long count = Product.count("partNo", request.partNo);
            if (count > 0) {
                throw new BusinessException("Part No already exists: " + request.partNo);
            }
            product.partNo = request.partNo;
        }

        if (request.name != null) product.name = request.name;
        if (request.categoryId != null) {
            product.categoryId = request.categoryId;
            com.cpq.basicdata.entity.ProductCategory pc = com.cpq.basicdata.entity.ProductCategory.findById(request.categoryId);
            if (pc == null) throw new BusinessException(400, "Category not found: " + request.categoryId);
            product.category = pc.name;
        } else if (request.category != null) {
            product.category = request.category;
        }
        if (request.specification != null) product.specification = request.specification;
        if (request.drawingNo != null) product.drawingNo = request.drawingNo;
        if (request.dimension != null) product.dimension = request.dimension;
        if (request.material != null) product.material = request.material;
        if (request.status != null) product.status = request.status;
        if (request.tags != null) product.tags = toJsonArray(request.tags);

        LOG.infof("Updated product id=%s partNo=%s", id, product.partNo);
        return ProductDTO.from(product);
    }

    @Transactional
    public void delete(UUID id) {
        Product product = Product.findById(id);
        if (product == null) {
            throw new BusinessException(404, "Product not found: " + id);
        }
        checkNoActiveQuotations(id);
        product.status = "INACTIVE";
        LOG.infof("Soft-deleted product id=%s partNo=%s", id, product.partNo);
    }

    private void checkNoActiveQuotations(UUID productId) {
        try {
            Long tableExists = (Long) em.createNativeQuery(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_name = 'quotation_line_item'"
            ).getSingleResult();
            if (tableExists == null || tableExists == 0) {
                return;
            }
            Long activeCount = (Long) em.createNativeQuery(
                "SELECT COUNT(*) FROM quotation_line_item li JOIN quotation q ON q.id = li.quotation_id" +
                " WHERE li.product_id = :pid AND q.status IN ('DRAFT','SUBMITTED','APPROVED')"
            ).setParameter("pid", productId).getSingleResult();
            if (activeCount != null && activeCount > 0) {
                throw new BusinessException("Cannot delete product with active quotations");
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            LOG.debugf("Quotation check skipped for productId=%s: %s", productId, e.getMessage());
        }
    }

    private UUID resolveCategoryId(CreateProductRequest req) {
        if (req.categoryId != null) {
            com.cpq.basicdata.entity.ProductCategory pc = com.cpq.basicdata.entity.ProductCategory.findById(req.categoryId);
            if (pc == null) throw new BusinessException(400, "Category not found: " + req.categoryId);
            return pc.id;
        }
        if (req.category != null && !req.category.isBlank()) {
            com.cpq.basicdata.entity.ProductCategory pc =
                    com.cpq.basicdata.entity.ProductCategory.find("name = ?1", req.category).firstResult();
            if (pc != null) return pc.id;
        }
        com.cpq.basicdata.entity.ProductCategory dft =
                com.cpq.basicdata.entity.ProductCategory.find("code = ?1", "DEFAULT").firstResult();
        return dft != null ? dft.id : null;
    }

    private String resolveCategoryName(CreateProductRequest req, UUID categoryId) {
        // 优先级 1：调用方显式传 categoryId → 用 ProductCategory.name（用户配置的中文名）
        if (req.categoryId != null) {
            com.cpq.basicdata.entity.ProductCategory pc =
                    com.cpq.basicdata.entity.ProductCategory.findById(req.categoryId);
            if (pc != null) return pc.name;
        }
        // 优先级 2：调用方显式传 category 字符串 → 原样保留（兼容 STANDARD/CUSTOM/RAW_MATERIAL 旧 API 与新中文分类）
        if (req.category != null && !req.category.isBlank()) {
            return req.category;
        }
        // 优先级 3：fallback 到默认分类（DEFAULT 分类的 name）
        if (categoryId != null) {
            com.cpq.basicdata.entity.ProductCategory pc =
                    com.cpq.basicdata.entity.ProductCategory.findById(categoryId);
            if (pc != null) return pc.name;
        }
        return "默认分类";
    }

    private String toJsonArray(List<String> tags) {
        if (tags == null || tags.isEmpty()) return "[]";
        try {
            return MAPPER.writeValueAsString(tags);
        } catch (Exception e) {
            return "[]";
        }
    }
}
