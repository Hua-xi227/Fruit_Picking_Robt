package org.example.fruitpickingrobt.utils;
import cn.hutool.core.codec.Base64;
import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import org.example.fruitpickingrobt.constant.RoleConstant;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.example.fruitpickingrobt.config.SecurityProperties;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Calendar;
import java.util.Date;
import java.util.UUID;

@Component
@Slf4j
public class JwtUtil {
    private static final String JWT_EXP_STR = "exp";

    @Autowired
    private SecurityProperties properties;

    /**
     * 创建JWT令牌
     */
    public String createJwt(Long userId, String username, String role) {
        if (userId == null || username == null || role == null) {
            return null;
        }
        Algorithm algorithm = Algorithm.HMAC256(Base64.decodeStr(properties.getBase64Secret(), StandardCharsets.UTF_8));
        Calendar calendar = Calendar.getInstance();
        Date now = calendar.getTime();
        calendar.add(Calendar.SECOND, properties.getTokenValiditySeconds().intValue());

        return JWT.create()
                .withJWTId(UUID.randomUUID().toString())
                .withClaim(RoleConstant.USER_ID, userId)
                .withClaim(RoleConstant.USERNAME, username)
                .withClaim(RoleConstant.ROLE, role)
                .withExpiresAt(calendar.getTime())
                .withIssuedAt(now)
                .sign(algorithm);
    }

    /**
     * 解析并验证JWT
     */
    public JwtInfo resolveJwt(String token) {
        Algorithm algorithm = Algorithm.HMAC256(Base64.decodeStr(properties.getBase64Secret(), StandardCharsets.UTF_8));
        JWTVerifier verifier = JWT.require(algorithm).build();
        try {
            DecodedJWT verified = verifier.verify(token);
            if (new Date().after(verified.getClaim(JWT_EXP_STR).asDate())) {
                return null; // 令牌过期
            }
            Long userId = verified.getClaim(RoleConstant.USER_ID).asLong();
            String username = verified.getClaim(RoleConstant.USERNAME).asString();
            String role = verified.getClaim(RoleConstant.ROLE).asString();
            return new JwtInfo(userId, username, role);
        } catch (JWTVerificationException e) {
            log.info("JWT验证失败: {}", token);
        }
        return null;
    }

    /**
     * JWT解析结果封装
     */
    public static record JwtInfo(Long userId, String username, String role) {}
}
