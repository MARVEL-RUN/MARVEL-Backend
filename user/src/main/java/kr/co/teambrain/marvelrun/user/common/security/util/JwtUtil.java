package kr.co.teambrain.marvelrun.user.common.security.util;

import kr.co.teambrain.marvelrun.user.common.security.service.CustomUserDetailService;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.Key;

/** */
@Component
public class JwtUtil {

    private final Key key;

    private final CustomUserDetailService customUserDetailService;

    private final JwtParser jwtParser;

    public JwtUtil(@Value("${token.secret}") String secretKey, CustomUserDetailService customAdminDetailService) {
        this.customUserDetailService = customAdminDetailService;
        byte[] keyBytes = Decoders.BASE64URL.decode(secretKey);
        this.key = Keys.hmacShaKeyFor(keyBytes);
        this.jwtParser = Jwts.parserBuilder().setSigningKey(this.key).build();
    }


}
