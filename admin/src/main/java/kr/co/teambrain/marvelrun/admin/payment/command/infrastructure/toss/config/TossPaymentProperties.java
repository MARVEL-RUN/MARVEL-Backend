package kr.co.teambrain.marvelrun.admin.payment.command.infrastructure.toss.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/** 관리자 환불의 Toss 연결 설정이다. 사용자 서버와 같은 키 및 상점 구성을 사용한다. */
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