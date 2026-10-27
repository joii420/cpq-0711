package com.cpq.task261026crypto;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** S-PRIVATE backend half of AC-4 and the retained JSON compatibility in AC-13. */
@QuarkusTest
@TestProfile(Task261026CryptoTestSupport.ProdProfile.class)
@QuarkusTestResource(value = Task261026CryptoTestSupport.FakeCryptoResource.class,
        restrictToAnnotatedClass = true)
class Task261026ComponentBundleContractTest {

    private static final String PREFIX = "T261026-CRYPTO-BE-COMP-";
    private static final byte[] CIPHER = "T261026-BUNDLE-CIPHERTEXT".getBytes(StandardCharsets.UTF_8);

    @Inject
    EntityManager em;

    @Inject
    UserTransaction utx;

    private UUID directoryId;

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
        Task261026CryptoTestSupport.FakeCryptoResource.reset();
    }

    @AfterEach
    void cleanupPrivateDirectory() throws Exception {
        utx.begin();
        em.joinTransaction();
        em.createNativeQuery("DELETE FROM component_sql_view WHERE component_id IN "
                        + "(SELECT id FROM component WHERE directory_id = :dir)")
                .setParameter("dir", directoryId).executeUpdate();
        em.createNativeQuery("DELETE FROM component WHERE directory_id = :dir")
                .setParameter("dir", directoryId).executeUpdate();
        em.createNativeQuery("DELETE FROM component_directory WHERE id = :dir")
                .setParameter("dir", directoryId).executeUpdate();
        utx.commit();
    }

    @Test
    void ac4_multipartPreviewAndCommitEachDecryptTheOriginalFile() {
        String code = PREFIX + UUID.randomUUID().toString().substring(0, 8);
        byte[] bundle = bundle(code);
        planEncrypted(bundle);

        Response preview = given()
                .multiPart("file", "bundle.json", CIPHER, "application/json")
                .queryParam("conflictPolicy", "RENAME")
                .when().post("/api/cpq/component-directories/{id}/import", directoryId);
        assertEquals(200, preview.statusCode(), preview.asString());
        assertTrue(preview.jsonPath().getList("data.components").size() > 0,
                "Preview result must be non-empty before its component assertions are trusted");
        assertEquals(code, preview.jsonPath().getString("data.components[0].code"), preview.asString());
        assertInspectDecryptPair();

        planEncrypted(bundle);
        Response commit = given()
                .multiPart("file", "bundle.json", CIPHER, "application/json")
                .queryParam("conflictPolicy", "RENAME")
                .queryParam("ignoreMissingDeps", false)
                .queryParam("ignoreUnboundFormulas", false)
                .when().post("/api/cpq/component-directories/{id}/import/commit", directoryId);
        assertEquals(200, commit.statusCode(), commit.asString());
        assertInspectDecryptPair();
        assertEquals(1L, componentCount(code), "Commit must persist exactly the decrypted component");
    }

    @Test
    void ac13_applicationJsonCompatibilityDoesNotCallCryptoService() {
        String code = PREFIX + "JSON-" + UUID.randomUUID().toString().substring(0, 8);
        byte[] bundle = bundle(code);

        Response response = given()
                .contentType(ContentType.JSON)
                .body(bundle)
                .queryParam("conflictPolicy", "RENAME")
                .when().post("/api/cpq/component-directories/{id}/import", directoryId);

        assertEquals(200, response.statusCode(), response.asString());
        assertTrue(response.jsonPath().getList("data.components").size() > 0);
        assertEquals(0, Task261026CryptoTestSupport.FakeCryptoResource.requests().size(),
                "The retained JSON-body API is not a file upload and must not call crypto service");
    }

    private static byte[] bundle(String code) {
        return ("{\"bundleVersion\":\"1.0\",\"components\":[{"
                + "\"code\":\"" + code + "\",\"name\":\"" + code + "\","
                + "\"componentType\":\"NORMAL\",\"fields\":[],\"formulas\":[]}]}")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static void planEncrypted(byte[] plaintext) {
        Task261026CryptoTestSupport.FakeCryptoResource.plan(
                200, "{\"encrypted\":true}".getBytes(StandardCharsets.UTF_8), 200, plaintext);
    }

    private static void assertInspectDecryptPair() {
        List<Task261026CryptoTestSupport.CapturedRequest> requests =
                Task261026CryptoTestSupport.FakeCryptoResource.requests();
        assertEquals(List.of("/v1/inspect", "/v1/decrypt"),
                requests.stream().map(Task261026CryptoTestSupport.CapturedRequest::path).toList());
        assertEquals(requests.get(0).requestId(), requests.get(1).requestId());
        assertEquals(requests.get(0).sha256(), requests.get(1).sha256());
    }

    private long componentCount(String code) {
        return ((Number) em.createNativeQuery(
                        "SELECT count(*) FROM component WHERE directory_id = :dir AND code = :code")
                .setParameter("dir", directoryId)
                .setParameter("code", code)
                .getSingleResult()).longValue();
    }
}
