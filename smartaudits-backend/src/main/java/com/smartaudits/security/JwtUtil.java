package com.smartaudits.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import javax.crypto.SecretKey;
import java.util.Date;

@Component
public class JwtUtil {
    @Value("${jwt.secret}")
    private String secretKey;
    @Value("${jwt.expiration:86400000}")
    private long jwtExpiration;
    @Value("${jwt.issuer:smartaudits}")
    private String issuer;
    @Value("${jwt.audience:smartaudits-api}")
    private String audience;

    public String generateToken(CustomUserDetails details) {
        if (!details.isEnabled()) {
            throw new IllegalArgumentException("No se puede emitir un token para una cuenta inactiva");
        }
        var usuario = details.getUsuario();
        return Jwts.builder()
                .issuer(issuer).audience().add(audience).and()
                .subject(usuario.getId().toString())
                .claim("userId", usuario.getId())
                .claim("tokenVersion", usuario.getTokenVersion())
                .claim("role", usuario.getRole().name())
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + jwtExpiration))
                .signWith(getSignInKey(), Jwts.SIG.HS256).compact();
    }

    public Long extractUserId(String token) {
        return Long.valueOf(extractAllClaims(token).getSubject());
    }

    public boolean isTokenValid(String token, CustomUserDetails details) {
        try {
            Claims claims = extractAllClaims(token);
            Long id = Long.valueOf(claims.getSubject());
            Long version = claims.get("tokenVersion", Long.class);
            return details.isEnabled()
                    && id.equals(details.getUsuario().getId())
                    && id.equals(claims.get("userId", Long.class))
                    && version != null && version == details.getUsuario().getTokenVersion()
                    && claims.getExpiration() != null && claims.getExpiration().after(new Date());
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    private Claims extractAllClaims(String token) {
        return Jwts.parser().verifyWith(getSignInKey())
                .sig().clear().add(Jwts.SIG.HS256).and()
                .requireIssuer(issuer).requireAudience(audience).build()
                .parseSignedClaims(token).getPayload();
    }

    private SecretKey getSignInKey() {
        return Keys.hmacShaKeyFor(Decoders.BASE64.decode(secretKey));
    }
}
