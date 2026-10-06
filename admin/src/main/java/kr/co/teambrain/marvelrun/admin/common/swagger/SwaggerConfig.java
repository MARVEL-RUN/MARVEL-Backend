package kr.co.teambrain.marvelrun.admin.common.swagger;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/** 환경별 Swagger 요청 서버와 JWT 인증 방식을 정의한다. */
@Configuration
public class SwaggerConfig {

    private static final String SECURITY_SCHEME_NAME =
            "bearerAuth";


    /** 로컬·테스트·운영 도메인에 현재 모듈의 context path를 붙여 API 문서를 구성한다. */
    @Bean
    public OpenAPI customOpenAPI(
            @Value("${server.servlet.context-path:}")
            String contextPath
    ) {

        // 각 환경의 API 기본 주소와 공통 인증 설정을 구성한다.
        return new OpenAPI()
                .servers(
                        List.of(
                                new Server()
                                        .url(
                                                "http://localhost:8080"
                                                        + contextPath
                                        )
                                        .description(
                                                "Local Dev"
                                        ),

                                new Server()
                                        .url(
                                                "https://marathontest2026.duckdns.org"
                                                        + contextPath
                                        )
                                        .description(
                                                "Test"
                                        ),

                                new Server()
                                        .url(
                                                "https://marvelrunkorea2026.com"
                                                        + contextPath
                                        )
                                        .description(
                                                "Production"
                                        )
                        )
                )

                .components(
                        new Components()
                                .addSecuritySchemes(
                                        SECURITY_SCHEME_NAME,
                                        new SecurityScheme()
                                                .type(
                                                        SecurityScheme.Type.HTTP
                                                )
                                                .scheme(
                                                        "bearer"
                                                )
                                                .bearerFormat(
                                                        "JWT"
                                                )
                                )
                )

                .addSecurityItem(
                        new SecurityRequirement()
                                .addList(
                                        SECURITY_SCHEME_NAME
                                )
                );
    }
}
