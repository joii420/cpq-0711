package com.cpq.basicdata.service;

import com.cpq.basicdata.dto.CreateProductCategoryRequest;
import com.cpq.basicdata.dto.ProductCategoryDTO;
import com.cpq.basicdata.entity.ProductCategory;
import com.cpq.common.exception.BusinessException;
import com.cpq.product.entity.Product;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class ProductCategoryService {

    private static final Logger LOG = Logger.getLogger(ProductCategoryService.class);

    public List<ProductCategoryDTO> list(String status) {
        List<ProductCategory> rows = (status == null || status.isBlank())
                ? ProductCategory.findAll(io.quarkus.panache.common.Sort.by("sortOrder").and("name")).list()
                : ProductCategory.find("status = ?1 ORDER BY sortOrder, name", status).list();
        return rows.stream().map(ProductCategoryDTO::from).collect(Collectors.toList());
    }

    public ProductCategoryDTO getById(UUID id) {
        ProductCategory c = ProductCategory.findById(id);
        if (c == null) throw new BusinessException(404, "ProductCategory not found: " + id);
        return ProductCategoryDTO.from(c);
    }

    @Transactional
    public ProductCategoryDTO create(CreateProductCategoryRequest req) {
        long dup = ProductCategory.count("code = ?1", req.code);
        if (dup > 0) throw new BusinessException(400, "code already exists: " + req.code);
        if (req.parentId != null) ensureExists(req.parentId);

        ProductCategory c = new ProductCategory();
        c.code = req.code;
        c.name = req.name;
        c.description = req.description;
        c.parentId = req.parentId;
        if (req.status != null) c.status = req.status;
        if (req.sortOrder != null) c.sortOrder = req.sortOrder;
        c.persist();

        LOG.infof("Created product category code=%s id=%s", c.code, c.id);
        return ProductCategoryDTO.from(c);
    }

    @Transactional
    public ProductCategoryDTO update(UUID id, CreateProductCategoryRequest req) {
        ProductCategory c = ProductCategory.findById(id);
        if (c == null) throw new BusinessException(404, "ProductCategory not found: " + id);

        if (req.code != null && !req.code.equals(c.code)) {
            long dup = ProductCategory.count("code = ?1 AND id != ?2", req.code, id);
            if (dup > 0) throw new BusinessException(400, "code already exists: " + req.code);
            c.code = req.code;
        }
        if (req.name != null) c.name = req.name;
        if (req.description != null) c.description = req.description;
        if (req.parentId != null) {
            if (req.parentId.equals(id)) throw new BusinessException(400, "Cannot set self as parent");
            ensureNotCircular(id, req.parentId);
            ensureExists(req.parentId);
            c.parentId = req.parentId;
        }
        if (req.status != null) c.status = req.status;
        if (req.sortOrder != null) c.sortOrder = req.sortOrder;

        return ProductCategoryDTO.from(c);
    }

    @Transactional
    public void delete(UUID id) {
        ProductCategory c = ProductCategory.findById(id);
        if (c == null) throw new BusinessException(404, "ProductCategory not found: " + id);

        long children = ProductCategory.count("parentId = ?1", id);
        if (children > 0) throw new BusinessException(400, "Cannot delete: has child categories");

        long products = Product.count("categoryId = ?1", id);
        if (products > 0) throw new BusinessException(400, "Cannot delete: " + products + " products linked");

        c.delete();
        LOG.infof("Deleted product category id=%s code=%s", id, c.code);
    }

    private void ensureExists(UUID id) {
        if (ProductCategory.findById(id) == null) {
            throw new BusinessException(400, "Parent category not found: " + id);
        }
    }

    private void ensureNotCircular(UUID selfId, UUID newParentId) {
        UUID cursor = newParentId;
        int depth = 0;
        while (cursor != null && depth < 32) {
            if (selfId.equals(cursor)) {
                throw new BusinessException(400, "Circular parent reference");
            }
            ProductCategory parent = ProductCategory.findById(cursor);
            if (parent == null) return;
            cursor = parent.parentId;
            depth++;
        }
    }
}
