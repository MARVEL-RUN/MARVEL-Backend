package kr.co.teambrain.marvelrun.user.common.security.util;

import kr.co.teambrain.marvelrun.user.common.security.config.TokenProperties;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

/** 토큰 테스트용 설정. HS512에 맞는 64바이트 키와 테스트 발급자를 쓴다. */
public final class TestTokenProperties {

    public static final TokenProperties TOKEN_PROPERTIES = new TokenProperties(
            Base64.getEncoder().encodeToString(
                    "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
                            .getBytes(StandardCharsets.UTF_8)),
            "marvelrun-test",
            new TokenProperties.ExpirationTime(Duration.ofHours(1), Duration.ofDays(7))
    );

    private TestTokenProperties() {
    }
}
