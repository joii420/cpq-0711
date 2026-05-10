package com.cpq.product.resource;

import com.cpq.common.dto.ApiResponse;
import com.cpq.common.dto.PageResult;
import com.cpq.common.security.RoleAllowed;
import com.cpq.product.dto.CreateProductRequest;
import com.cpq.product.dto.ImportResult;
import com.cpq.product.dto.ProductDTO;
import com.cpq.product.service.ProductImportService;
import com.cpq.product.service.ProductService;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;

import java.io.IOException;
import java.util.UUID;

@Path("/api/cpq/products")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RoleAllowed({"SALES_REP", "SALES_MANAGER", "PRICING_MANAGER", "SYSTEM_ADMIN"})
public class ProductResource {

    @Inject
    ProductService productService;

    @Inject
    ProductImportService productImportService;

    @GET
    public ApiResponse<PageResult<ProductDTO>> list(
            @QueryParam("page") @DefaultValue("0") int page,
            @QueryParam("size") @DefaultValue("20") int size,
            @QueryParam("category") String category,
            @QueryParam("categoryId") UUID categoryId,
            @QueryParam("status") String status,
            @QueryParam("keyword") String keyword) {
        return ApiResponse.success(productService.list(page, size, category, categoryId, status, keyword));
    }

    @POST
    @RoleAllowed({"SALES_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<ProductDTO> create(@Valid CreateProductRequest request) {
        return ApiResponse.success(productService.create(request));
    }

    @PUT
    @Path("/{id}")
    @RoleAllowed({"SALES_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<ProductDTO> update(@PathParam("id") UUID id, CreateProductRequest request) {
        return ApiResponse.success(productService.update(id, request));
    }

    @DELETE
    @Path("/{id}")
    @RoleAllowed({"SALES_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<Void> delete(@PathParam("id") UUID id) {
        productService.delete(id);
        return ApiResponse.success();
    }

    @POST
    @Path("/import")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @RoleAllowed({"SALES_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<ImportResult> importExcel(@RestForm("file") FileUpload file) throws IOException {
        ImportResult result = productImportService.importFromExcel(file.uploadedFile().toFile().toURI().toURL().openStream());
        return ApiResponse.success(result);
    }
}
