package com.cpq.task261026crypto;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** S-PRIVATE: AC-5/9 model main-file + thumbnail decryption and failure atomicity. */
@QuarkusTest
@TestProfile(Task261026CryptoTestSupport.ProdProfile.class)
@QuarkusTestResource(value = Task261026CryptoTestSupport.FakeCryptoResource.class,
        restrictToAnnotatedClass = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Task261026ModelAtomicityTest {

    private static final String RUN = UUID.randomUUID().toString().substring(0, 8);
    private static final String PREFIX = "T261026-CRYPTO-BE-MODEL-" + RUN;
    private static final byte[] CIPHER_GLB = "T261026-CIPHER-GLB".getBytes(StandardCharsets.UTF_8);
    private static final byte[] CIPHER_PNG = "T261026-CIPHER-PNG".getBytes(StandardCharsets.UTF_8);
    private static final byte[] PLAIN_GLB = "glTF-T261026-PLAIN-GLB".getBytes(StandardCharsets.UTF_8);
    private static final byte[] PLAIN_PNG = new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 1, 2, 3};

    @Inject
    EntityManager em;

    @Inject
    UserTransaction utx;

    @BeforeAll
    void seedPrivateMaterial() throws Exception {
        utx.begin();
        em.joinTransaction();
        for (String code : List.of(PREFIX, PREFIX + "-FAIL")) {
            em.createNativeQuery("INSERT INTO material_recipe(id, code, symbol, name, recipe_type, status) "
                            + "VALUES (gen_random_uuid(), :code, 'T261026', :name, 'locked', 'ACTIVE') "
                            + "ON CONFLICT (code) DO NOTHING")
                    .setParameter("code", code)
                    .setParameter("name", code)
                    .executeUpdate();
        }
        utx.commit();
    }

    @BeforeEach
    void resetFakeToEncrypted() {
        Task261026CryptoTestSupport.FakeCryptoResource.plan(
                200, "{\"encrypted\":true}".getBytes(StandardCharsets.UTF_8), 200, PLAIN_GLB);
        Task261026CryptoTestSupport.FakeCryptoResource.decryptForFile("cipher.glb", 200, PLAIN_GLB);
        Task261026CryptoTestSupport.FakeCryptoResource.decryptForFile("cipher.png", 200, PLAIN_PNG);
    }

    @AfterAll
    void cleanupPrivateRows() throws Exception {
        utx.begin();
        em.joinTransaction();
        em.createNativeQuery("DELETE FROM model_config WHERE subject_key LIKE :prefix")
                .setParameter("prefix", PREFIX + "%")
                .executeUpdate();
        em.createNativeQuery("DELETE FROM material_recipe WHERE code LIKE :prefix")
                .setParameter("prefix", PREFIX + "%")
                .executeUpdate();
        utx.commit();
    }

    @Test
    void ac5_twoEncryptedFilesAreIndependentlyDecryptedAndDownloadedAsPlaintext() {
        Response upload = upload(PREFIX, true);
        assertEquals(200, upload.statusCode(), upload.asString());
        String id = upload.jsonPath().getString("data.id");
        String glbUrl = upload.jsonPath().getString("data.glbUrl");
        String thumbnailUrl = upload.jsonPath().getString("data.thumbnailUrl");
        assertNotNull(id);
        assertNotNull(glbUrl);
        assertNotNull(thumbnailUrl);

        assertArrayEquals(PLAIN_GLB, given().when().get(glbUrl).then().statusCode(200)
                .extract().asByteArray());
        assertArrayEquals(PLAIN_PNG, given().when().get(thumbnailUrl).then().statusCode(200)
                .extract().asByteArray());
        assertPlaintextFileMetadata(id);

        List<Task261026CryptoTestSupport.CapturedRequest> requests =
                Task261026CryptoTestSupport.FakeCryptoResource.requests();
        assertEquals(List.of("/v1/inspect", "/v1/decrypt", "/v1/inspect", "/v1/decrypt"),
                requests.stream().map(Task261026CryptoTestSupport.CapturedRequest::path).toList());
        assertEquals(4, requests.size());
        assertEquals(requests.get(0).requestId(), requests.get(1).requestId());
        assertEquals(requests.get(2).requestId(), requests.get(3).requestId());
        assertNotEquals(requests.get(0).requestId(), requests.get(2).requestId(),
                "The two multipart file slots must receive different request IDs");
    }

    @Test
    void ac5_ac9_secondFileFailureLeavesNoVersionAndNoNewStoredFile() throws Exception {
        String subjectKey = PREFIX + "-FAIL";
        long rowsBefore = modelRows(subjectKey);
        long filesBefore = storedRegularFiles();
        Task261026CryptoTestSupport.FakeCryptoResource.decryptForFile(
                "cipher.png", 503,
                "{\"code\":\"CRYPTO_NOT_INITIALIZED\",\"message\":\"do-not-leak\"}"
                        .getBytes(StandardCharsets.UTF_8));

        Response upload = upload(subjectKey, true);

        assertEquals(503, upload.statusCode(), upload.asString());
        assertEquals("FILE_CRYPTO_UNAVAILABLE", upload.jsonPath().getString("detail.bizCode"),
                upload.asString());
        assertEquals(rowsBefore, modelRows(subjectKey), "A failed thumbnail must not create a model version");
        assertEquals(filesBefore, storedRegularFiles(), "A failed thumbnail must not leave the GLB on disk");
        assertEquals(List.of("/v1/inspect", "/v1/decrypt", "/v1/inspect", "/v1/decrypt"),
                Task261026CryptoTestSupport.FakeCryptoResource.requests().stream()
                        .map(Task261026CryptoTestSupport.CapturedRequest::path).toList(),
                "The failure must occur on the second file after the first file was processed");
    }

    private Response upload(String subjectKey, boolean thumbnail) {
        var request = given()
                .multiPart("subjectType", "MATERIAL")
                .multiPart("subjectKey", subjectKey)
                .multiPart("label", subjectKey)
                .multiPart("glbFile", "cipher.glb", CIPHER_GLB, "model/gltf-binary")
                .multiPart("setCurrent", "true");
        if (thumbnail) {
            request = request.multiPart("thumbnailFile", "cipher.png", CIPHER_PNG, "image/png");
        }
        return request.when().post("/api/cpq/model-configs");
    }

    private long modelRows(String subjectKey) {
        return ((Number) em.createNativeQuery(
                        "SELECT count(*) FROM model_config WHERE subject_key = :key")
                .setParameter("key", subjectKey)
                .getSingleResult()).longValue();
    }

    private long storedRegularFiles() throws Exception {
        Path storage = Task261026CryptoTestSupport.FakeCryptoResource.storage();
        try (var files = Files.walk(storage)) {
            return files.filter(Files::isRegularFile).count();
        }
    }

    @SuppressWarnings("unchecked")
    private void assertPlaintextFileMetadata(String modelId) {
        List<Object[]> rows = em.createNativeQuery(
                        "SELECT file_role, file_size_bytes, md5_hash FROM model_config_file "
                                + "WHERE model_config_id = CAST(:id AS uuid) ORDER BY file_role")
                .setParameter("id", modelId)
                .getResultList();
        assertEquals(2, rows.size(), "GLB and thumbnail must each have one metadata row");
        Map<String, Object[]> byRole = new HashMap<>();
        for (Object[] row : rows) {
            byRole.put(row[0].toString(), row);
        }
        assertFileMetadata(byRole.get("GLB"), PLAIN_GLB);
        assertFileMetadata(byRole.get("THUMBNAIL"), PLAIN_PNG);
    }

    private static void assertFileMetadata(Object[] row, byte[] plaintext) {
        assertNotNull(row, "Expected file metadata role is missing");
        assertEquals((long) plaintext.length, ((Number) row[1]).longValue());
        try {
            String expectedMd5 = HexFormat.of().formatHex(
                    MessageDigest.getInstance("MD5").digest(plaintext));
            assertEquals(expectedMd5, row[2]);
        } catch (Exception e) {
            throw new AssertionError("MD5 is required by the JVM", e);
        }
    }
}
