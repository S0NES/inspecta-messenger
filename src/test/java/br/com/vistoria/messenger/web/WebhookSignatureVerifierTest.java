package br.com.vistoria.messenger.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link WebhookSignatureVerifier}: verifies that webhook
 * signatures are correctly validated for each broker.
 *
 * <ul>
 *   <li>Resend: Svix manual verification scheme (svix-id / svix-timestamp /
 *       svix-signature headers, HMAC-SHA256 over "id.timestamp.body" with the
 *       base64-decoded whsec_ key, anti-replay window).</li>
 *   <li>Expo: {@code expo-signature: sha1=<hex>}, HMAC-SHA1 of the raw body with
 *       the UTF-8 secret.</li>
 *   <li>Infobip: unchanged stub scheme (X-Hub-Signature: sha256=&lt;hex&gt;).</li>
 * </ul>
 */
class WebhookSignatureVerifierTest {

    // Svix example-style secret: "whsec_" + base64 payload.
    private static final String RESEND_WHSEC = "whsec_MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw";
    private static final String EXPO_SECRET = "expo-secret-key-789";
    private static final String INFOBIP_SECRET = "infobip-secret-key-456";

    private WebhookSignatureVerifier verifier;

    @BeforeEach
    void setUp() {
        verifier = new WebhookSignatureVerifier();
        setSecret("resendSecret", RESEND_WHSEC);
        setSecret("infobipSecret", INFOBIP_SECRET);
        setSecret("expoSecret", EXPO_SECRET);
    }

    private void setSecret(String field, String value) {
        try {
            var f = WebhookSignatureVerifier.class.getDeclaredField(field);
            f.setAccessible(true);
            f.set(verifier, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }


    @Test
    void verifyResendAcceptsValidSvixSignature() {
        String payload = "{\"type\":\"email.delivered\"}";
        String id = "msg_2abc";
        String ts = nowSeconds();
        String sig = "v1," + svixSign(id, ts, payload, RESEND_WHSEC);

        Map<String, String> headers = Map.of(
                "svix-id", id,
                "svix-timestamp", ts,
                "svix-signature", sig);

        assertThat(verifier.verifySignature("resend", payload, headers)).isTrue();
    }

    @Test
    void verifyResendAcceptsWhenAnyV1TokenMatches() {
        String payload = "{\"type\":\"email.delivered\"}";
        String id = "msg_2abc";
        String ts = nowSeconds();
        String good = svixSign(id, ts, payload, RESEND_WHSEC);
        String sig = "v1,AAAAdifferentsignatureAAAA v1," + good;

        Map<String, String> headers = Map.of(
                "svix-id", id,
                "svix-timestamp", ts,
                "svix-signature", sig);

        assertThat(verifier.verifySignature("resend", payload, headers)).isTrue();
    }

    @Test
    void verifyResendIsCaseInsensitiveForHeaderNames() {
        String payload = "{\"type\":\"email.delivered\"}";
        String id = "msg_2abc";
        String ts = nowSeconds();

        Map<String, String> headers = new HashMap<>();
        headers.put("Svix-Id", id);
        headers.put("Svix-Timestamp", ts);
        headers.put("Svix-Signature", "v1," + svixSign(id, ts, payload, RESEND_WHSEC));

        assertThat(verifier.verifySignature("resend", payload, headers)).isTrue();
    }

    @Test
    void verifyResendRejectsMissingSvixId() {
        String payload = "p";
        String ts = nowSeconds();
        Map<String, String> headers = Map.of(
                "svix-timestamp", ts,
                "svix-signature", "v1," + svixSign("msg_1", ts, payload, RESEND_WHSEC));

        assertThat(verifier.verifySignature("resend", payload, headers)).isFalse();
    }

    @Test
    void verifyResendRejectsMissingSvixTimestamp() {
        String payload = "p";
        Map<String, String> headers = Map.of(
                "svix-id", "msg_1",
                "svix-signature", "v1,anything");

        assertThat(verifier.verifySignature("resend", payload, headers)).isFalse();
    }

    @Test
    void verifyResendRejectsMissingSvixSignature() {
        String payload = "p";
        Map<String, String> headers = Map.of(
                "svix-id", "msg_1",
                "svix-timestamp", nowSeconds());

        assertThat(verifier.verifySignature("resend", payload, headers)).isFalse();
    }

    @Test
    void verifyResendRejectsStaleTimestamp() {
        String payload = "{\"type\":\"email.delivered\"}";
        String id = "msg_2abc";
        String staleTs = String.valueOf(Instant.now().getEpochSecond() - (10 * 60)); // 10 min ago
        String sig = "v1," + svixSign(id, staleTs, payload, RESEND_WHSEC);

        Map<String, String> headers = Map.of(
                "svix-id", id,
                "svix-timestamp", staleTs,
                "svix-signature", sig);

        assertThat(verifier.verifySignature("resend", payload, headers)).isFalse();
    }

    @Test
    void verifyResendRejectsFutureTimestamp() {
        String payload = "p";
        String id = "msg_2abc";
        String futureTs = String.valueOf(Instant.now().getEpochSecond() + (10 * 60));
        String sig = "v1," + svixSign(id, futureTs, payload, RESEND_WHSEC);

        Map<String, String> headers = Map.of(
                "svix-id", id,
                "svix-timestamp", futureTs,
                "svix-signature", sig);

        assertThat(verifier.verifySignature("resend", payload, headers)).isFalse();
    }

    @Test
    void verifyResendRejectsNonNumericTimestamp() {
        String payload = "p";
        Map<String, String> headers = Map.of(
                "svix-id", "msg_1",
                "svix-timestamp", "not-a-number",
                "svix-signature", "v1,anything");

        assertThat(verifier.verifySignature("resend", payload, headers)).isFalse();
    }

    @Test
    void verifyResendRejectsWrongSecret() {
        String payload = "{\"type\":\"email.delivered\"}";
        String id = "msg_2abc";
        String ts = nowSeconds();
        // Sign with a different whsec secret than the verifier holds.
        String sig = "v1," + svixSign(id, ts, payload, "whsec_" + base64("another-secret-value-000"));

        Map<String, String> headers = Map.of(
                "svix-id", id,
                "svix-timestamp", ts,
                "svix-signature", sig);

        assertThat(verifier.verifySignature("resend", payload, headers)).isFalse();
    }

    @Test
    void verifyResendRejectsTamperedPayload() {
        String payload = "{\"type\":\"email.delivered\"}";
        String id = "msg_2abc";
        String ts = nowSeconds();
        String sig = "v1," + svixSign(id, ts, payload, RESEND_WHSEC);

        Map<String, String> headers = Map.of(
                "svix-id", id,
                "svix-timestamp", ts,
                "svix-signature", sig);

        assertThat(verifier.verifySignature("resend", "{\"type\":\"email.bounced\"}", headers)).isFalse();
    }

    @Test
    void verifyResendRejectsWhenSecretIsBlank() {
        setSecret("resendSecret", "");
        String payload = "p";
        String ts = nowSeconds();
        Map<String, String> headers = Map.of(
                "svix-id", "msg_1",
                "svix-timestamp", ts,
                "svix-signature", "v1,anything");

        assertThat(verifier.verifySignature("resend", payload, headers)).isFalse();
    }


    @Test
    void verifyInfobipSignatureAcceptsValidSignature() {
        String payload = "test-payload";
        String signature = "sha256=" + hmacHex("HmacSHA256", payload, INFOBIP_SECRET);

        Map<String, String> headers = Map.of("X-Hub-Signature", signature);
        assertThat(verifier.verifySignature("infobip", payload, headers)).isTrue();
    }

    @Test
    void verifyInfobipSignatureRejectsInvalidSignature() {
        String payload = "test-payload";
        String wrongSignature = "sha256=0000000000000000000000000000000000000000000000000000000000000000";

        Map<String, String> headers = Map.of("X-Hub-Signature", wrongSignature);
        assertThat(verifier.verifySignature("infobip", payload, headers)).isFalse();
    }

    @Test
    void verifyInfobipSignatureRejectsMissingSignatureHeader() {
        String payload = "test-payload";
        Map<String, String> headers = Map.of("Content-Type", "application/json");

        assertThat(verifier.verifySignature("infobip", payload, headers)).isFalse();
    }

    @Test
    void verifyInfobipSignatureRejectsMalformedSignatureFormat() {
        String payload = "test-payload";
        Map<String, String> headers = Map.of("X-Hub-Signature", "invalid-format");

        assertThat(verifier.verifySignature("infobip", payload, headers)).isFalse();
    }

    @Test
    void verifyInfobipSignatureRejectsWhenSecretIsBlank() {
        setSecret("infobipSecret", "");

        String payload = "test-payload";
        Map<String, String> headers = Map.of("X-Hub-Signature", "sha256=any-signature");

        assertThat(verifier.verifySignature("infobip", payload, headers)).isFalse();
    }

    @Test
    void verifyInfobipSignatureIsCaseInsensitiveForHeaderName() {
        String payload = "test-payload";
        String signature = "sha256=" + hmacHex("HmacSHA256", payload, INFOBIP_SECRET);

        Map<String, String> headers = new HashMap<>();
        headers.put("x-hub-signature", signature); // lowercase
        assertThat(verifier.verifySignature("infobip", payload, headers)).isTrue();
    }


    @Test
    void verifyExpoAcceptsValidSha1HexSignature() {
        String payload = "{\"id\":\"ticket-1\"}";
        String signature = "sha1=" + hmacHex("HmacSHA1", payload, EXPO_SECRET);

        Map<String, String> headers = Map.of("expo-signature", signature);
        assertThat(verifier.verifySignature("expo", payload, headers)).isTrue();
    }

    @Test
    void verifyExpoRejectsInvalidSignature() {
        String payload = "{\"id\":\"ticket-1\"}";
        Map<String, String> headers = Map.of("expo-signature", "sha1=deadbeef");

        assertThat(verifier.verifySignature("expo", payload, headers)).isFalse();
    }

    @Test
    void verifyExpoRejectsMissingSignatureHeader() {
        String payload = "{\"id\":\"ticket-1\"}";
        Map<String, String> headers = Map.of("Content-Type", "application/json");

        assertThat(verifier.verifySignature("expo", payload, headers)).isFalse();
    }

    @Test
    void verifyExpoRejectsSignatureWithoutSha1Prefix() {
        String payload = "{\"id\":\"ticket-1\"}";
        // Correct digest but missing the "sha1=" prefix -> rejected.
        String bareHex = hmacHex("HmacSHA1", payload, EXPO_SECRET);
        Map<String, String> headers = Map.of("expo-signature", bareHex);

        assertThat(verifier.verifySignature("expo", payload, headers)).isFalse();
    }

    @Test
    void verifyExpoRejectsSha256Digest() {
        String payload = "{\"id\":\"ticket-1\"}";
        // Old (wrong) algorithm: base64 HMAC-SHA256.
        String signature = "sha1=" + hmacHex("HmacSHA256", payload, EXPO_SECRET);
        Map<String, String> headers = Map.of("expo-signature", signature);

        assertThat(verifier.verifySignature("expo", payload, headers)).isFalse();
    }

    @Test
    void verifyExpoRejectsWhenSecretIsBlank() {
        setSecret("expoSecret", "");
        String payload = "{\"id\":\"ticket-1\"}";
        Map<String, String> headers = Map.of("expo-signature", "sha1=anything");

        assertThat(verifier.verifySignature("expo", payload, headers)).isFalse();
    }

    @Test
    void verifyExpoIsCaseInsensitiveForHeaderName() {
        String payload = "{\"id\":\"ticket-1\"}";
        String signature = "sha1=" + hmacHex("HmacSHA1", payload, EXPO_SECRET);

        Map<String, String> headers = new HashMap<>();
        headers.put("Expo-Signature", signature);
        assertThat(verifier.verifySignature("expo", payload, headers)).isTrue();
    }


    @Test
    void verifySignatureRejectsNullPayload() {
        Map<String, String> headers = Map.of("svix-id", "1", "svix-timestamp", nowSeconds(), "svix-signature", "v1,x");
        assertThat(verifier.verifySignature("resend", null, headers)).isFalse();
    }

    @Test
    void verifySignatureRejectsBlankPayload() {
        Map<String, String> headers = Map.of("svix-id", "1", "svix-timestamp", nowSeconds(), "svix-signature", "v1,x");
        assertThat(verifier.verifySignature("resend", "", headers)).isFalse();
    }

    @Test
    void verifySignatureRejectsUnknownBroker() {
        Map<String, String> headers = Map.of("X-Some-Signature", "sha256=anything");
        assertThat(verifier.verifySignature("unknown-broker", "payload", headers)).isFalse();
    }

    @Test
    void verifySignatureIsCaseInsensitiveForBrokerName() {
        String payload = "{\"type\":\"email.delivered\"}";
        String id = "msg_2abc";
        String ts = nowSeconds();
        Map<String, String> headers = Map.of(
                "svix-id", id,
                "svix-timestamp", ts,
                "svix-signature", "v1," + svixSign(id, ts, payload, RESEND_WHSEC));

        assertThat(verifier.verifySignature("RESEND", payload, headers)).isTrue();
    }

    @Test
    void verifySignatureWorksWithNullHeaders() {
        assertThat(verifier.verifySignature("resend", "payload", null)).isFalse();
    }

    @Test
    void verifySignatureWorksWithEmptyHeaders() {
        assertThat(verifier.verifySignature("resend", "payload", Map.of())).isFalse();
    }


    private static String nowSeconds() {
        return String.valueOf(Instant.now().getEpochSecond());
    }

    private static String base64(String s) {
        return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    /** Produces the base64 v1 signature body for the Svix scheme. */
    private static String svixSign(String id, String ts, String payload, String whsecSecret) {
        try {
            String body = whsecSecret.startsWith("whsec_") ? whsecSecret.substring("whsec_".length()) : whsecSecret;
            byte[] key = Base64.getDecoder().decode(body);
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            byte[] hash = mac.doFinal((id + "." + ts + "." + payload).getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String hmacHex(String algo, String payload, String secret) {
        try {
            Mac mac = Mac.getInstance(algo);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), algo));
            byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
