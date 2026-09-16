package kr.co.teambrain.marvelrun.admin.security.util;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import kr.co.teambrain.marvelrun.admin.security.config.TokenProperties;
import kr.co.teambrain.marvelrun.admin.security.details.CustomAdminDetail;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.Key;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
@Component
public class JwtTokenProvider {

    public static final String TOKEN_TYPE_CLAIM = "tokenType";
    public static final String ACCESS_TOKEN_TYPE = "ACCESS";
    public static final String REFRESH_TOKEN_TYPE = "REFRESH";

    private final Key key;
    private final TokenProperties tokenProperties;


    public JwtTokenProvider(
            TokenProperties tokenProperties
    ) {

        this.tokenProperties =
                tokenProperties;

        byte[] keyBytes =
                Decoders.BASE64.decode(
                        tokenProperties.secret()
                );

        this.key =
                Keys.hmacShaKeyFor(
                        keyBytes
                );
    }


    public String createAccessToken(
            CustomAdminDetail admin
    ) {

        return buildToken(
                admin,
                ACCESS_TOKEN_TYPE,
                tokenProperties.accessTokenExpirationTime()
        );
    }


    public String createRefreshToken(
            CustomAdminDetail admin
    ) {

        return buildToken(
                admin,
                REFRESH_TOKEN_TYPE,
                tokenProperties.refreshTokenExpirationTime()
        );
    }


    private String buildToken(
            CustomAdminDetail admin,
            String tokenType,
            long ttlMillis
    ) {

        Date now =
                new Date();

        Map<String, Object> claims =
                new HashMap<>();

        claims.put(
                "name",
                admin.getName()
        );

        claims.put(
                "role",
                admin.getRoleName()
        );

        claims.put(
                TOKEN_TYPE_CLAIM,
                tokenType
        );


        return Jwts.builder()
                .setClaims(claims)
                .setSubject(admin.getUsername())
                .setIssuedAt(now)
                .setExpiration(
                        new Date(
                                now.getTime()
                                        + ttlMillis
                        )
                )
                .signWith(
                        key,
                        SignatureAlgorithm.HS512
                )
                .compact();
    }
}