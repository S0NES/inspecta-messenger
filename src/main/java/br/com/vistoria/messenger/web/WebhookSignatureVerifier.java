package br.com.vistoria.messenger.web;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;

/**
 * Verifies webhook signatures from messaging brokers (Resend, Infobip, Expo).
 * Fails closed: rejects webhooks without valid signatures.
 */
@Component
@RequiredArgsConstructor
public class WebhookSignatureVerifier {

    /** Svix (Resend) anti-replay tolerance: reject timestamps more than this many seconds from now. */
    private static final long SVIX_TIMESTAMP_TOLERANCE_SECONDS = 5 * 60;

    @Value("${inspecta.webhooks.resend-webhook-secret:}")
    private String resendSecret;

    @Value("${inspecta.webhooks.infobip-webhook-secret:}")
    private String infobipSecret;

    @Value("${inspecta.webhooks.expo-webhook-secret:}")
    private String expoSecret;

    /**
     * Verifies webhook signature based on broker type.
     * Each broker has a different signature algorithm and header name.
     *
     * @param broker the broker name ("resend", "infobip", "expo")
     * @param payload the raw request body
     * @param headers the request headers (case-insensitive lookup)
     * @return true if signature is valid; false if missing, blank secret, or invalid
     */
    public boolean verifySignature(String broker, String payload, java.util.Map<String, String> headers) {
        if (payload == null || payload.isBlank()) {
            return false;
        }

        return switch (broker.toLowerCase()) {
            case "resend" -> verifyResend(payload, headers);
            case "infobip" -> verifyInfobip(payload, headers);
            case "expo" -> verifyExpo(payload, headers);
            default -> {
                // Unknown broker: fail closed
                yield false;
            }
        };
    }

    /**
     * Resend signs webhooks via Svix. Manual verification per
     * <a href="https://docs.svix.com/receiving/verifying-payloads/how-manual">Svix docs</a>:
     * <ol>
     *   <li>Read headers {@code svix-id}, {@code svix-timestamp}, {@code svix-signature}
     *       (case-insensitive). Any missing header rejects the request.</li>
     *   <li>{@code signedContent = svixId + "." + svixTimestamp + "." + rawPayload}</li>
     *   <li>Secret is {@code whsec_<base64>}: strip the {@code whsec_} prefix and base64-decode
     *       the rest to get the raw HMAC key bytes.</li>
     *   <li>{@code expected = base64(HmacSHA256(signedContent, keyBytes))}</li>
     *   <li>{@code svix-signature} is a space-separated list of {@code v1,<base64sig>} tokens;
     *       accept if any {@code v1} token matches {@code expected} (constant-time).</li>
     *   <li>Anti-replay: reject if {@code svix-timestamp} is more than ~5 minutes from now.</li>
     * </ol>
     */
    private boolean verifyResend(String payload, java.util.Map<String, String> headers) {
        if (resendSecret == null || resendSecret.isBlank()) return false;

        String svixId = headerValue(headers, "svix-id");
        String svixTimestamp = headerValue(headers, "svix-timestamp");
        String svixSignature = headerValue(headers, "svix-signature");
        if (svixId == null || svixTimestamp == null || svixSignature == null) return false;

        // Anti-replay: timestamp must be unix seconds and within tolerance of now (past or future).
        long ts;
        try {
            ts = Long.parseLong(svixTimestamp.trim());
        } catch (NumberFormatException e) {
            return false;
        }
        long now = Instant.now().getEpochSecond();
        if (Math.abs(now - ts) > SVIX_TIMESTAMP_TOLERANCE_SECONDS) return false;

        // Decode the secret: strip "whsec_" prefix, base64-decode the remainder to raw key bytes.
        String secretBody = resendSecret.startsWith("whsec_") ? resendSecret.substring("whsec_".length()) : resendSecret;
        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(secretBody);
        } catch (IllegalArgumentException e) {
            return false;
        }

        String signedContent = svixId + "." + svixTimestamp + "." + payload;
        String expected = hmacSha256Base64(signedContent, keyBytes);
        if (expected.isEmpty()) return false;

        // svix-signature: space-separated "v1,<base64sig>" tokens. Accept if any v1 token matches.
        for (String token : svixSignature.split(" ")) {
            int comma = token.indexOf(',');
            if (comma < 0) continue;
            String version = token.substring(0, comma);
            String sig = token.substring(comma + 1);
            if ("v1".equals(version) && constantTimeEquals(sig, expected)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Infobip webhook signature.
     *
     * <p>TODO: The header name ({@code X-Hub-Signature}) and algorithm (HMAC-SHA256 hex of the
     * raw body with the UTF-8 secret) below are UNVERIFIED against the real Infobip product.
     * Infobip's actual inbound-webhook signing scheme has not been confirmed. SMS is currently
     * off the critical path ({@code InfobipAdapter} is a stub that throws
     * {@code UnsupportedOperationException}). Before enabling Infobip/SMS webhooks in
     * production, confirm the real header name and signing algorithm from Infobip's docs and
     * update this method and its tests accordingly.</p>
     */
    private boolean verifyInfobip(String payload, java.util.Map<String, String> headers) {
        if (infobipSecret == null || infobipSecret.isBlank()) return false;

        String signature = headerValue(headers, "X-Hub-Signature");
        if (signature == null || !signature.startsWith("sha256=")) return false;

        String hmac = hmacSha256Hex(payload, infobipSecret);
        String expectedSig = "sha256=" + hmac;
        return constantTimeEquals(signature, expectedSig);
    }

    /**
     * Expo (EAS) webhook signature, per
     * <a href="https://docs.expo.dev/eas/webhooks/">Expo docs</a>: the {@code expo-signature}
     * header carries a hex-encoded HMAC-SHA1 digest of the raw request body, using the webhook
     * secret (UTF-8 bytes) as the HMAC key, formatted as {@code sha1=<hex>}.
     */
    private boolean verifyExpo(String payload, java.util.Map<String, String> headers) {
        if (expoSecret == null || expoSecret.isBlank()) return false;

        String signature = headerValue(headers, "expo-signature");
        if (signature == null || !signature.startsWith("sha1=")) return false;

        String expectedSig = "sha1=" + hmacSha1Hex(payload, expoSecret);
        return constantTimeEquals(signature, expectedSig);
    }

    private String hmacSha256Base64(String payload, byte[] key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            return "";
        }
    }

    private String hmacSha256Hex(String payload, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return toHex(hash);
        } catch (Exception e) {
            return "";
        }
    }

    private String hmacSha1Hex(String payload, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return toHex(hash);
        } catch (Exception e) {
            return "";
        }
    }

    private String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        byte[] aBytes = a.getBytes(StandardCharsets.UTF_8);
        byte[] bBytes = b.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(aBytes, bBytes);
    }

    private String headerValue(java.util.Map<String, String> headers, String name) {
        if (headers == null) return null;
        return headers.entrySet().stream()
                .filter(e -> e.getKey().equalsIgnoreCase(name))
                .map(java.util.Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }
}
