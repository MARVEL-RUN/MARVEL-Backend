package kr.co.teambrain.marvelrun.user.payment.command.infrastructure.toss.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "toss.payments")
public record TossPaymentProperties(

        @NotBlank
        String baseUrl,

        @NotBlank
        String secretKey,

        @NotNull
        Duration connectTimeout,

        @NotNull
        Duration readTimeout

) {
}