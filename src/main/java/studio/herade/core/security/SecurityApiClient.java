package studio.herade.core.security;

import studio.herade.core.CoreConfig;
import studio.herade.core.HeradeCore;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class SecurityApiClient {
    public enum VerificationResult {
        APPROVED,
        DENIED,
        UNAVAILABLE
    }

    private static final String TOKEN_ENV = "HERADE_CORE_API_TOKEN";
    private final URI endpoint;
    private final String secret;
    private final HttpClient httpClient;
    private final Duration requestTimeout;

    public SecurityApiClient(CoreConfig config) {
        String configuredUrl = config.apiBaseUrl().trim();
        URI base = URI.create(configuredUrl.endsWith("/") ? configuredUrl : configuredUrl + "/");
        if (!"https".equalsIgnoreCase(base.getScheme()) || base.getHost() == null
                || base.getUserInfo() != null || base.getFragment() != null || base.getRawQuery() != null) {
            throw new IllegalArgumentException("Security API must be an HTTPS base URL without user-info, query or fragment.");
        }

        this.endpoint = base.resolve("api/v1/minecraft/verify");
        this.secret = System.getenv(TOKEN_ENV);
        this.requestTimeout = Duration.ofSeconds(config.requestTimeoutSeconds());
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(config.connectTimeoutSeconds()))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public CompletableFuture<VerificationResult> verify(UUID playerId, String playerName) {
        if (playerId == null || playerName == null || playerName.isBlank()) {
            return CompletableFuture.completedFuture(VerificationResult.DENIED);
        }
        if (secret == null || secret.isBlank()) {
            HeradeCore.LOGGER.error("Authentication is enabled but {} is not configured.", TOKEN_ENV);
            return CompletableFuture.completedFuture(VerificationResult.UNAVAILABLE);
        }

        String body = "{\"uuid\":\"" + playerId + "\",\"username\":\"" + escapeJson(playerName) + "\"}";
        String timestamp = Long.toString(System.currentTimeMillis() / 1000L);
        String nonce = UUID.randomUUID().toString();
        String path = endpoint.getRawPath();
        String canonical = timestamp + "\n" + nonce + "\nPOST\n" + path + "\n" + body;

        final String signature;
        try {
            signature = sign(canonical, secret);
        } catch (GeneralSecurityException exception) {
            HeradeCore.LOGGER.error("Could not sign Security API request.", exception);
            return CompletableFuture.completedFuture(VerificationResult.UNAVAILABLE);
        }

        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(requestTimeout)
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Accept", "application/json")
                .header("X-Herade-Timestamp", timestamp)
                .header("X-Herade-Nonce", nonce)
                .header("X-Herade-Signature", signature)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                .thenApply(response -> switch (response.statusCode()) {
                    case 204 -> VerificationResult.APPROVED;
                    case 403 -> VerificationResult.DENIED;
                    default -> {
                        HeradeCore.LOGGER.warn("Security API returned HTTP {}.", response.statusCode());
                        yield VerificationResult.UNAVAILABLE;
                    }
                })
                .exceptionally(exception -> {
                    HeradeCore.LOGGER.warn("Security API request failed: {}",
                            exception.getClass().getSimpleName());
                    return VerificationResult.UNAVAILABLE;
                });
    }

    private static String sign(String value, String key) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static String escapeJson(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            switch (character) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.toString();
    }
}
