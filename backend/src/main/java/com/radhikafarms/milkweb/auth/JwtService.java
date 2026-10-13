package com.radhikafarms.milkweb.auth;

import com.radhikafarms.milkweb.config.JwtProperties;
import com.radhikafarms.milkweb.user.User;
import com.radhikafarms.milkweb.user.UserRole;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;

@Service
public class JwtService {

    // HS256 requires a 256-bit (32-byte) key. Enforcing the byte length here gives a
    // clear startup error instead of letting the JWT library reject the key later,
    // and prevents an empty default from silently producing forgeable tokens.
    private static final int MIN_SECRET_BYTES = 32;

    private final JwtProperties jwtProperties;
    private final SecretKey secretKey;

    public JwtService(JwtProperties jwtProperties) {
        this.jwtProperties = jwtProperties;
        String secret = jwtProperties.getSecret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                "MILKWEB_JWT_SECRET is missing or blank. Set it to a random value of at least "
                    + MIN_SECRET_BYTES + " bytes before starting the application.");
        }
        // Detect unresolved Spring placeholder (e.g. "${MILKWEB_JWT_SECRET}") which indicates
        // the environment variable was not set and no default was provided.
        if (secret.startsWith("${") && secret.endsWith("}")) {
            throw new IllegalStateException(
                "MILKWEB_JWT_SECRET is not configured. The placeholder '" + secret + "' was not resolved. "
                    + "Ensure the environment variable is set in the deployment environment.");
        }
        byte[] secretBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (secretBytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                "MILKWEB_JWT_SECRET is too short for HS256. It must be at least "
                    + MIN_SECRET_BYTES + " bytes, but was " + secretBytes.length + " bytes.");
        }
        this.secretKey = Keys.hmacShaKeyFor(secretBytes);
    }

    public String generateToken(User user) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + jwtProperties.getExpirationMs());

        return Jwts.builder()
                .subject(user.getId().toString())
                .claim("phone", user.getPhone())
                .claim("role", user.getRole().name())
                .issuedAt(now)
                .expiration(expiry)
                .signWith(secretKey)
                .compact();
    }

    public UUID extractUserId(String token) {
        return UUID.fromString(parseClaims(token).getSubject());
    }

    public UserRole extractRole(String token) {
        return UserRole.valueOf(parseClaims(token).get("role", String.class));
    }

    public boolean isTokenValid(String token) {
        try {
            Claims claims = parseClaims(token);
            return claims.getExpiration().after(new Date());
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
