package kr.co.teambrain.marvelrun.admin.auth.command.application.service;

import io.jsonwebtoken.Claims;
import kr.co.teambrain.marvelrun.admin.auth.command.application.domain.Admin;
import kr.co.teambrain.marvelrun.admin.auth.command.application.dto.AdminLoginRequest;

import kr.co.teambrain.marvelrun.admin.auth.command.application.dto.AdminLoginSuccess;
import kr.co.teambrain.marvelrun.admin.auth.command.application.dto.reseponse.AdminLoginResponse;
import kr.co.teambrain.marvelrun.admin.auth.command.application.exception.AuthErrorCode;
import kr.co.teambrain.marvelrun.admin.auth.command.application.exception.JwtAuthenticationException;
import kr.co.teambrain.marvelrun.admin.auth.command.repository.AdminCommandRepository;
import kr.co.teambrain.marvelrun.admin.common.exception.CustomException;
import kr.co.teambrain.marvelrun.admin.common.exception.ErrorCode;
import kr.co.teambrain.marvelrun.admin.common.redis.RedisService;
import kr.co.teambrain.marvelrun.admin.security.config.TokenProperties;
import kr.co.teambrain.marvelrun.admin.security.details.CustomAdminDetail;
import kr.co.teambrain.marvelrun.admin.security.util.JwtTokenProvider;
import kr.co.teambrain.marvelrun.admin.security.util.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class AdminCommandService {

    private final AdminCommandRepository
            adminCommandRepository;

    private final PasswordEncoder
            passwordEncoder;

    private final JwtTokenProvider
            jwtTokenProvider;

    private final JwtUtil
            jwtUtil;

    private final RedisService
            redisService;

    private final TokenProperties tokenProperties;


    @Transactional(readOnly = true)
    public AdminLoginSuccess login(
            AdminLoginRequest request
    ) {

        Admin admin =
                adminCommandRepository
                        .findByLoginId(
                                request.loginId()
                        )
                        .orElseThrow(
                                () -> new CustomException(
                                        ErrorCode.AUTHENTICATION_FAILED
                                )
                        );


        if (!passwordEncoder.matches(
                request.password(),
                admin.getPassword()
        )) {

            throw new CustomException(
                    ErrorCode.AUTHENTICATION_FAILED
            );
        }


        CustomAdminDetail adminDetail =
                new CustomAdminDetail(
                        admin
                );


        String accessToken =
                jwtTokenProvider
                        .createAccessToken(
                                adminDetail
                        );


        String refreshToken =
                jwtTokenProvider
                        .createRefreshToken(
                                adminDetail
                        );


        redisService
                .saveWhitelistRefreshToken(
                        admin.getId(),
                        refreshToken,
                        tokenProperties.refreshTokenExpirationTime(),
                        TimeUnit.MILLISECONDS
                );


        return new AdminLoginSuccess(
                accessToken,
                refreshToken,
                new AdminLoginResponse(
                        admin.getName()
                )
        );
    }


    public AdminLoginSuccess refresh(
            String refreshHeader
    ) {

        String refreshToken =
                extractToken(
                        refreshHeader,
                        AuthErrorCode.MISSING_REFRESH_HEADER
                );


        Claims claims =
                jwtUtil.validateRefreshToken(
                        refreshToken
                );


        String adminId =
                claims.getSubject();


        if (!redisService
                .isValidRefreshToken(
                        adminId,
                        refreshToken
                )) {

            throw new JwtAuthenticationException(
                    AuthErrorCode.INVALID_REFRESH_TOKEN
            );
        }


        CustomAdminDetail adminDetail =
                new CustomAdminDetail(
                        adminId,
                        claims.get(
                                "name",
                                String.class
                        ),
                        claims.get(
                                "role",
                                String.class
                        )
                );


        String newAccessToken =
                jwtTokenProvider
                        .createAccessToken(
                                adminDetail
                        );


        String newRefreshToken =
                jwtTokenProvider
                        .createRefreshToken(
                                adminDetail
                        );


        redisService.saveWhitelistRefreshToken(
                adminId,
                newRefreshToken,
                tokenProperties.refreshTokenExpirationTime(),
                TimeUnit.MILLISECONDS
        );

        return new AdminLoginSuccess(
                newAccessToken,
                newRefreshToken,
                new AdminLoginResponse(
                        adminDetail.getName()
                )
        );
    }


    public void logout(
            String accessHeader,
            String refreshHeader
    ) {

        String accessToken =
                extractToken(
                        accessHeader,
                        AuthErrorCode.MISSING_AUTH_HEADER
                );

        String refreshToken =
                extractToken(
                        refreshHeader,
                        AuthErrorCode.MISSING_REFRESH_HEADER
                );


        Claims accessClaims =
                jwtUtil.validateAccessToken(
                        accessToken
                );


        Claims refreshClaims =
                jwtUtil.validateRefreshToken(
                        refreshToken
                );


        String accessAdminId =
                accessClaims.getSubject();

        String refreshAdminId =
                refreshClaims.getSubject();


        if (!accessAdminId.equals(
                refreshAdminId
        )) {

            throw new JwtAuthenticationException(
                    AuthErrorCode.INVALID_REFRESH_TOKEN
            );
        }


        if (!redisService
                .isValidRefreshToken(
                        refreshAdminId,
                        refreshToken
                )) {

            throw new JwtAuthenticationException(
                    AuthErrorCode.INVALID_REFRESH_TOKEN
            );
        }


        redisService.deleteRefreshToken(
                refreshAdminId
        );


        long remainingTtl =
                // 필요시 별도 jwtutil 쪽 메서드로 분리하여 사용하기
                Math.max(
                        accessClaims
                                .getExpiration()
                                .getTime()
                                - System.currentTimeMillis(),
                        0L
                );


        if (remainingTtl > 0) {

            redisService.saveBlacklistAccessToken(
                    accessToken,
                    remainingTtl,
                    TimeUnit.MILLISECONDS
            );
        }
    }


    private String extractToken(
            String header,
            AuthErrorCode missingHeaderError
    ) {

        if (header == null
                || header.isBlank()) {

            throw new JwtAuthenticationException(
                    missingHeaderError
            );
        }


        String trimmed =
                header.trim();


        if (trimmed.regionMatches(
                true,
                0,
                "Bearer ",
                0,
                7
        )) {

            String token =
                    trimmed
                            .substring(7)
                            .trim();


            if (token.isBlank()) {

                throw new JwtAuthenticationException(
                        missingHeaderError
                );
            }


            return token;
        }


        return trimmed;
    }
}