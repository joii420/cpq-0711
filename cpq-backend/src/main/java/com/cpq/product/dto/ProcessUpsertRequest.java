package com.cpq.product.dto;

public class ProcessUpsertRequest {

    /** Max 50 chars, must be unique across process table. */
    public String code;

    /** Display name. */
    public String name;

    /**
     * Category enum — must match DB CHECK constraint:
     * SURFACE_TREATMENT / MACHINING / HEAT_TREATMENT / ASSEMBLY / INSPECTION / PACKAGING
     */
    public String category;

    public String description;

    /** Whether this process is mandatory by default. Nullable; defaults to false. */
    public Boolean isRequired;

    /** Display order within category. Nullable; defaults to 0. */
    public Integer sortOrder;

    /**
     * Status enum — must match DB CHECK constraint: ACTIVE / DISABLED.
     * Nullable on create (defaults to ACTIVE); explicit on update to keep current value.
     */
    public String status;
}
