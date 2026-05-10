package com.cpq.datasource.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

public class CreateDataSourceRequest {

    @NotBlank(message = "Code is required")
    @Size(max = 100)
    @Pattern(regexp = "^[A-Za-z0-9_-]+$", message = "Code must contain only letters, digits, underscores, and hyphens")
    public String code;

    @NotBlank(message = "Name is required")
    @Size(max = 200)
    public String name;

    @NotBlank(message = "Type is required")
    @Pattern(regexp = "^(SQL|API)$", message = "Type must be SQL or API")
    public String type;

    public String status = "ACTIVE";

    public String description;

    // SQL fields
    public String sqlQuery;
    public String sqlResultColumn;

    // API fields
    public String apiUrl;
    public String apiMethod;
    public String apiHeaders;
    public String apiBodyTemplate;
    public String apiResultPath;
    public Integer apiTimeoutSeconds;

    @Valid
    public List<ParamRequest> params;

    public static class ParamRequest {

        @NotBlank(message = "Param code is required")
        @Size(max = 100)
        public String paramCode;

        @NotBlank(message = "Param name is required")
        @Size(max = 200)
        public String paramName;

        @NotNull(message = "Source type is required")
        @Pattern(regexp = "^(USER_FIELD|SYSTEM_PARAM)$", message = "Source type must be USER_FIELD or SYSTEM_PARAM")
        public String sourceType;

        public String systemParamCode;
        public Boolean isRequired = true;
        public String description;
    }
}
