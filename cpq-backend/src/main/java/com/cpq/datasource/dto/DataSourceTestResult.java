package com.cpq.datasource.dto;

public class DataSourceTestResult {

    public String rawResponse;
    public String extractedValue;
    public Long executionTimeMs;
    public boolean success;
    public String errorMessage;

    public static DataSourceTestResult ok(String rawResponse, String extractedValue, long executionTimeMs) {
        DataSourceTestResult result = new DataSourceTestResult();
        result.success = true;
        result.rawResponse = rawResponse;
        result.extractedValue = extractedValue;
        result.executionTimeMs = executionTimeMs;
        return result;
    }

    public static DataSourceTestResult error(String errorMessage, long executionTimeMs) {
        DataSourceTestResult result = new DataSourceTestResult();
        result.success = false;
        result.errorMessage = errorMessage;
        result.executionTimeMs = executionTimeMs;
        return result;
    }
}
