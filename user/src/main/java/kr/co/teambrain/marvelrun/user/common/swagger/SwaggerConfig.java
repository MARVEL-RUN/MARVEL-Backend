package kr.co.teambrain.marvelrun.user.common.swagger;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class SwaggerConfig {

    private static final String SECURITY_SCHEME_NAME =
            "bearerAuth";


    @Bean
    public OpenAPI customOpenAPI(
            @Value("${server.servlet.context-path:}")
            String contextPath
    ) {

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