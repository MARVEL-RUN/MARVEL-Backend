package kr.co.teambrain.marvelrun.admin.security.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "token")
public record TokenProperties(
        String secret,
        long accessTokenExpirationTime,
        long refreshTokenExpirationTime
) {
}