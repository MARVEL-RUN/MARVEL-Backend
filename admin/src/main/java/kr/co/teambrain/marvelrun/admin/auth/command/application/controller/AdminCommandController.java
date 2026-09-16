package kr.co.teambrain.marvelrun.admin.auth.command.application.controller;

import kr.co.teambrain.marvelrun.admin.auth.command.application.dto.AdminLoginRequest;
import kr.co.teambrain.marvelrun.admin.auth.command.application.dto.AdminLoginSuccess;
import kr.co.teambrain.marvelrun.admin.auth.command.application.dto.reseponse.AdminLoginResponse;
import kr.co.teambrain.marvelrun.admin.auth.command.application.service.AdminCommandService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/admin")
public class AdminCommandController {

    private static final String ACCESS_TOKEN_HEADER =
            "Authorization";

    private static final String REFRESH_TOKEN_HEADER =
            "refreshToken";

    private static final String BEARER_PREFIX =
            "Bearer ";


    private final AdminCommandService
            adminCommandService;


    @PostMapping("/public/login")
    public ResponseEntity<AdminLoginResponse> login(
            @RequestBody
            AdminLoginRequest request
    ) {

        AdminLoginSuccess result =
                adminCommandService.login(
                        request
                );


        HttpHeaders headers =
                createTokenHeaders(
                        result
                );


        return ResponseEntity
                .ok()
                .headers(headers)
                .body(
                        result.response()
                );
    }


    @PostMapping("/public/refresh")
    public ResponseEntity<AdminLoginResponse> refresh(

            @RequestHeader(
                    value = "refreshToken",
                    required = false
            )
            String refreshToken
    ) {

        AdminLoginSuccess result =
                adminCommandService.refresh(
                        refreshToken
                );


        return ResponseEntity
                .ok()
                .headers(
                        createTokenHeaders(
                                result
                        )
                )
                .body(
                        result.response()
                );
    }


    @PostMapping("/logout")

    public ResponseEntity<Void> logout(

            @RequestHeader(
                    value = "Authorization",
                    required = false
            )
            String accessToken,

            @RequestHeader(
                    value = "refreshToken",
                    required = false
            )
            String refreshToken
    ) {

        adminCommandService.logout(
                accessToken,
                refreshToken
        );


        return ResponseEntity
                .noContent()
                .build();
    }


    private HttpHeaders createTokenHeaders(
            AdminLoginSuccess result
    ) {

        HttpHeaders headers =
                new HttpHeaders();


        headers.add(
                ACCESS_TOKEN_HEADER,
                BEARER_PREFIX
                        + result.accessToken()
        );


        headers.add(
                REFRESH_TOKEN_HEADER,
                BEARER_PREFIX
                        + result.refreshToken()
        );


        return headers;
    }
}