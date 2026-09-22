package kr.co.teambrain.marvelrun.admin.payment.command.infrastructure.toss.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

/** 사용자와 동일한 인증 및 연결·응답 시간 제한으로 관리자 환불 클라이언트를 구성한다. */
@Configuration
@EnableConfigurationProperties(TossPaymentProperties.class)
public class TossPaymentClientConfig {

    /** 외부 요청마다 같은 Basic 인증 및 제한 시간을 사용한다. */
    @Bean
    public RestClient tossPaymentRestClient(
            TossPaymentProperties properties
    ) {

        HttpClient httpClient =
                HttpClient.newBuilder()
                        .connectTimeout(
                                properties.connectTimeout()
                        )
                        .build();

        JdkClientHttpRequestFactory requestFactory =
                new JdkClientHttpRequestFactory(
                        httpClient
                );

        requestFactory.setReadTimeout(
                properties.readTimeout()
        );

        return RestClient.builder()
                .baseUrl(
                        properties.baseUrl()
                )
                .defaultHeaders(headers ->
                        headers.setBasicAuth(
                                properties.secretKey(),
                                ""
                        )
                )
                .requestFactory(
                        requestFactory
                )
                .build();
    }
}