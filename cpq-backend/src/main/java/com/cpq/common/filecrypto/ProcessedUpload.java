package com.cpq.common.filecrypto;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

/**
 * Immutable result of the upload trust-boundary processing.
 *
 * <p>Construction is package-private: {@link FileCryptoService} transfers sole ownership of the
 * freshly read/received byte array to this object. Public callers only receive a defensive copy or
 * a read-only stream. This keeps the value immutable without an extra 100 MiB constructor copy at
 * the application's configured request-size boundary.
 */
public final class ProcessedUpload {

    private final String fileName;
    private final byte[] bytes;
    private final boolean decrypted;
    private final String requestId;

    ProcessedUpload(String fileName, byte[] bytes, boolean decrypted, String requestId) {
        this.fileName = fileName == null ? "upload.bin" : fileName;
        this.bytes = bytes == null ? new byte[0] : bytes;
        this.decrypted = decrypted;
        this.requestId = requestId;
    }

    public String fileName() {
        return fileName;
    }

    public byte[] bytes() {
        return bytes.clone();
    }

    public boolean decrypted() {
        return decrypted;
    }

    public String requestId() {
        return requestId;
    }

    public InputStream openStream() {
        return new ByteArrayInputStream(bytes);
    }

    public int size() {
        return bytes.length;
    }
}
