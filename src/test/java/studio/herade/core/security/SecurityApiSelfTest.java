package studio.herade.core.security;

import java.security.GeneralSecurityException;

public final class SecurityApiSelfTest {
    private SecurityApiSelfTest() {
    }

    public static void run() throws GeneralSecurityException {
        String signature = SecurityApiClient.sign(
                "The quick brown fox jumps over the lazy dog", "key");
        check("f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8".equals(signature),
                "HMAC-SHA256 must match the standard test vector");

        check("12345678".equals(SecurityApiClient.extractChallengeCode(
                "{\"challenge\":\"12345678\"}")),
                "eight-digit login challenge should be parsed");
        check(SecurityApiClient.extractChallengeCode("{\"challenge\":\"123456\"}") == null,
                "six-digit challenge must be rejected");
        check(SecurityApiClient.extractChallengeCode("not-json") == null,
                "malformed response must not produce a challenge");
        System.out.println("[Herade Core] Security API signing and challenge parsing: OK");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
