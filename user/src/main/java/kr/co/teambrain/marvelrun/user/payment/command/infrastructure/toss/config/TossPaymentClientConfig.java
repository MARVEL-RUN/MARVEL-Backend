package kr.co.teambrain.marvelrun.user.payment.command.infrastructure.toss.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

@Configuration
public class TossPaymentClientConfig {

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