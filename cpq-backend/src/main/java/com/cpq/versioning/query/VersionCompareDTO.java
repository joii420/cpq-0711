package com.cpq.versioning.query;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * DTO for comparing two version rows side-by-side (UI-5/UI-6).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class VersionCompareDTO {

    public int versionA;
    public int versionB;
    /** One entry per field that differs (or matches). Only fields in dataColumns are compared. */
    public List<FieldDiff> fieldDiffs;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class FieldDiff {
        public String fieldName;
        public String valueA;
        public String valueB;
        public boolean sameValue;

        public FieldDiff(String fieldName, String valueA, String valueB, boolean sameValue) {
            this.fieldName = fieldName;
            this.valueA    = valueA;
            this.valueB    = valueB;
            this.sameValue = sameValue;
        }
    }
}
