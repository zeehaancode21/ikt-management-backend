package com.example.backend.security;

import java.nio.charset.StandardCharsets;
import java.util.Date;

import javax.crypto.SecretKey;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * The "vault token" is a second, short-lived credential issued only after a
 * valid TOTP code is presented. It is deliberately separate from the normal
 * login JWT: being logged in as the owner is not enough on its own to read
 * confidential employee documents — every sensitive read/download also
 * requires this token, which expires after 10 minutes and must be reissued
 * with a fresh phone code.
 *
 * The signing key is loaded from the VAULT_SECRET environment variable
 * (application.properties: vault.secret). It must NOT be hardcoded, and it
 * must differ from JWT_SECRET. Generate one with: openssl rand -base64 48
 */
@Component
public class VaultTokenUtil {

    private final SecretKey KEY;
    private static final long VAULT_TOKEN_VALIDITY_MS = 10 * 60 * 1000;

    public VaultTokenUtil(@Value("${vault.secret}") String secret) {
        if (secret == null || secret.length() < 32) {
            throw new IllegalStateException(
                "vault.secret must be at least 32 characters. " +
                "Set the VAULT_SECRET environment variable (different from JWT_SECRET).");
        }
        this.KEY = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

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