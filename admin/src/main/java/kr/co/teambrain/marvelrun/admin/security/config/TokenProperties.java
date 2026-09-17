package kr.co.teambrain.marvelrun.admin.security.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "token")
public record TokenProperties(

        String secret,

        String issuer,

        ExpirationTime expirationTime

) {

    public record ExpirationTime(

            Duration accessToken,

            Duration refreshToken

    ) {
    }
}