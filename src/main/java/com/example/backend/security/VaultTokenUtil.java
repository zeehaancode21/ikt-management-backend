package com.example.backend.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Date;

/**
 * The "vault token" is a second, short-lived credential issued only after a
 * valid TOTP code is presented. It is deliberately separate from the normal
 * login JWT: being logged in as the owner is not enough on its own to read
 * confidential employee documents — every sensitive read/download also
 * requires this token, which expires after 10 minutes and must be reissued
 * with a fresh phone code.
 *
 * IMPORTANT: change VAULT_SECRET in production and load it from an
 * environment variable, same as the encryption key.
 */
@Component
public class VaultTokenUtil {

    private static final String VAULT_SECRET = "MUIV6w2E9mDaGTA3oXbLzQE5u73QIj7NaLMzfzMDWnc=";
    private static final SecretKey KEY = Keys.hmacShaKeyFor(VAULT_SECRET.getBytes());
    private static final long VAULT_TOKEN_VALIDITY_MS = 10 * 60 * 1000; 

    public String generateVaultToken(String username) {
        return Jwts.builder()
                .subject(username)
                .claim("scope", "vault")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + VAULT_TOKEN_VALIDITY_MS))
                .signWith(KEY)
                .compact();
    }

    /** Returns the username if the vault token is valid and unexpired, otherwise null. */
    public String validateAndExtractUsername(String token) {
        if (token == null || token.isBlank()) return null;
        try {
            Claims claims = Jwts.parser().verifyWith(KEY).build()
                    .parseSignedClaims(token).getPayload();
            if (!"vault".equals(claims.get("scope"))) return null;
            return claims.getSubject();
        } catch (JwtException | IllegalArgumentException e) {
            return null;
        }
    }
}