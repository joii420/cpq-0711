package com.cpq.tax.service;

import com.cpq.common.exception.BusinessException;
import com.cpq.tax.dto.TaxCategorySaveRequest;
import com.cpq.tax.dto.TaxCategoryVatVersionDTO;
import com.cpq.tax.dto.TaxCategoryVatVersionSaveRequest;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
class TaxCategoryServiceTest {

    @Inject TaxCategoryService service;

    @Test
    @TestTransaction
    void addFirstVersionDoesNotBindUntypedNullExclude() {
        TaxCategorySaveRequest category = new TaxCategorySaveRequest();
        category.code = "VAT_TEST_" + UUID.randomUUID().toString().replace("-", "");
        category.name = "VAT test category";
        category.status = "ACTIVE";
        UUID categoryId = service.create(category).id;

        TaxCategoryVatVersionSaveRequest version = version("10.0000", LocalDate.of(2026, 10, 3), null);
        TaxCategoryVatVersionDTO saved = service.addVersion(categoryId, version);

        assertEquals(0, new BigDecimal("10.0000").compareTo(saved.vatRate));
        assertEquals(LocalDate.of(2026, 10, 3), saved.effectiveFrom);
    }

    @Test
    @TestTransaction
    void overlappingActiveVersionReturnsBusinessConflict() {
        TaxCategorySaveRequest category = new TaxCategorySaveRequest();
        category.code = "VAT_TEST_" + UUID.randomUUID().toString().replace("-", "");
        category.name = "VAT overlap category";
        category.status = "ACTIVE";
        UUID categoryId = service.create(category).id;
        service.addVersion(categoryId, version("13", LocalDate.of(2026, 1, 1), null));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.addVersion(categoryId, version("10", LocalDate.of(2026, 10, 3), null)));

        assertEquals(409, error.getCode());
        assertEquals("TAX_RATE_PERIOD_OVERLAP", error.getMessage());
    }

    private static TaxCategoryVatVersionSaveRequest version(String rate, LocalDate from, LocalDate to) {
        TaxCategoryVatVersionSaveRequest request = new TaxCategoryVatVersionSaveRequest();
        request.vatRate = new BigDecimal(rate);
        request.effectiveFrom = from;
        request.effectiveTo = to;
        request.status = "ACTIVE";
        return request;
    }
}
