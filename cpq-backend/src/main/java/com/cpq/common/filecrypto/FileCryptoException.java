package com.cpq.common.filecrypto;

import com.cpq.common.exception.BusinessException;

/** A safe, structured error raised before untrusted upload bytes enter a business parser. */
public class FileCryptoException extends BusinessException {

    private final String bizCode;
    private final String upstreamCode;
    private final String requestId;

    public FileCryptoException(int status, String message, String bizCode,
                               String upstreamCode, String requestId) {
        super(status, message);
        this.bizCode = bizCode;
        this.upstreamCode = upstreamCode;
        this.requestId = requestId;
    }

    public String getBizCode() {
        return bizCode;
    }

    public String getUpstreamCode() {
        return upstreamCode;
    }

    public String getRequestId() {
        return requestId;
    }
}
