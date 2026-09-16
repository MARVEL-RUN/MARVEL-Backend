package kr.co.teambrain.marvelrun.user.common.swagger;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class SwaggerConfig {

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .servers(List.of(
                        new Server().url("http://localhost:8080").description("Local Dev"),
                        new Server().url("https://marathontest2026.duckdns.org").description("Production")
                ))
                .components(new Components()
                        .addSecuritySchemes("bearerAuth", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT") // 선택
                        )
                )
                // 전역 보안 요구사항 추가(이게 있어야 Swagger가 Authorization 헤더를 붙임)
                .addSecurityItem(new io.swagger.v3.oas.models.security.SecurityRequirement()
                        .addList("bearerAuth"));
    }
}
