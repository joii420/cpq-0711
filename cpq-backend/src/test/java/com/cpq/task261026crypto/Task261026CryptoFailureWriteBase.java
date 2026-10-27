package com.cpq.task261026crypto;

import io.restassured.config.HttpClientConfig;
import io.restassured.config.RestAssuredConfig;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Private write-path fixture used to prove crypto failures happen before business persistence. */
abstract class Task261026CryptoFailureWriteBase {

    private static final String PREFIX = "T261026-CRYPTO-BE-FAIL-";
    private static final byte[] CIPHER =
            "T261026-FAILURE-CIPHERTEXT-MUST-NOT-BE-PARSED".getBytes(StandardCharsets.UTF_8);

    @Inject
    EntityManager em;

    @Inject
    UserTransaction utx;

    UUID directoryId;

    @BeforeEach
    void createPrivateDirectory() throws Exception {
        directoryId = UUID.randomUUID();
        utx.begin();
        em.joinTransaction();
        em.createNativeQuery("INSERT INTO component_directory(id, name, sort_order, created_at) "
                        + "VALUES (:id, :name, 0, NOW())")
                .setParameter("id", directoryId)
                .setParameter("name", PREFIX + directoryId.toString().substring(0, 8))
                .executeUpdate();
        utx.commit();
    }

    @AfterEach
    void cleanupPrivateDirectory() throws Exception {
        utx.begin();
        em.joinTransaction();
        em.createNativeQuery("DELETE FROM component_sql_view WHERE component_id IN "
                        + "(SELECT id FROM component WHERE directory_id = :directory)")
                .setParameter("directory", directoryId)
                .executeUpdate();
        em.createNativeQuery("DELETE FROM component WHERE directory_id = :directory")
                .setParameter("directory", directoryId)
                .executeUpdate();
        em.createNativeQuery("DELETE FROM component_directory WHERE id = :directory")
                .setParameter("directory", directoryId)
                .executeUpdate();
        utx.commit();
    }

    Response postEncryptedCommit() {
        return given()
                .multiPart("file", "failure-bundle.json", CIPHER, "application/json")
                .queryParam("conflictPolicy", "RENAME")
                .queryParam("ignoreMissingDeps", false)
                .queryParam("ignoreUnboundFormulas", false)
                .when().post("/api/cpq/component-directories/{id}/import/commit", directoryId);
    }

    Response postEncryptedCommitWithClientReadTimeout(int timeoutMillis) {
        return given()
                .config(RestAssuredConfig.config().httpClient(
                        HttpClientConfig.httpClientConfig()
                                .setParam("http.socket.timeout", timeoutMillis)))
                .multiPart("file", "failure-bundle.json", CIPHER, "application/json")
                .queryParam("conflictPolicy", "RENAME")
                .queryParam("ignoreMissingDeps", false)
                .queryParam("ignoreUnboundFormulas", false)
                .when().post("/api/cpq/component-directories/{id}/import/commit", directoryId);
    }

    void assertFailureAndNoBusinessWrite(Response response, int status, String bizCode) {
        assertEquals(status, response.statusCode(), response.asString());
        assertEquals(bizCode, response.jsonPath().getString("detail.bizCode"), response.asString());
        assertEquals(0L, componentRows(),
                "A crypto/config failure must not create a component or successful import result");
    }

    long componentRows() {
        return ((Number) em.createNativeQuery(
                        "SELECT count(*) FROM component WHERE directory_id = :directory")
                .setParameter("directory", directoryId)
                .getSingleResult()).longValue();
    }
}
