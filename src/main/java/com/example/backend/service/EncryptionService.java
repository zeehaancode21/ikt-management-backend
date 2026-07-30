package com.example.backend.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Encrypts every byte of confidential employee data (file uploads and
 * structured text fields like bank account numbers) with AES-256-GCM before
 * it is persisted. GCM gives us both confidentiality and tamper-detection
 * (authenticity) — if the ciphertext is altered, decryption fails loudly.
 *
 * The key comes from kyc.encryption.key (Base64, 32 raw bytes = AES-256).
 * In production this MUST be supplied via an environment variable, not
 * committed to application.properties. Generate one with:
 *   openssl rand -base64 32
 */
@Service
public class EncryptionService {

    private static final String ALGO = "AES/GCM/NoPadding";
    private static final int IV_LENGTH_BYTES = 12;
    private static final int TAG_LENGTH_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public EncryptionService(@Value("${kyc.encryption.key}") String base64Key) {
        byte[] keyBytes = Base64.getDecoder().decode(base64Key);
        if (keyBytes.length != 32) {
            throw new IllegalStateException(
                    "kyc.encryption.key must decode to exactly 32 bytes (AES-256). " +
                    "Generate one with: openssl rand -base64 32");
        }
        this.key = new SecretKeySpec(keyBytes, "AES");
    }

    /** Encrypts raw bytes. Output = [12-byte IV][ciphertext+16-byte tag]. */
    public byte[] encryptBytes(byte[] plaintext) {
        try {
            byte[] iv = new byte[IV_LENGTH_BYTES];
            random.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGO);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext);

            byte[] out = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ciphertext, 0, out, iv.length, ciphertext.length);
            return out;
        } catch (Exception e) {
            throw new RuntimeException("Encryption failed", e);
        }
    }

    /** Decrypts the full blob at once. Keep using this for small text fields. */
    public byte[] decryptBytes(byte[] ivAndCiphertext) {
        try {
            byte[] iv = new byte[IV_LENGTH_BYTES];
            byte[] ciphertext = new byte[ivAndCiphertext.length - IV_LENGTH_BYTES];
            System.arraycopy(ivAndCiphertext, 0, iv, 0, IV_LENGTH_BYTES);
            System.arraycopy(ivAndCiphertext, IV_LENGTH_BYTES, ciphertext, 0, ciphertext.length);

            Cipher cipher = Cipher.getInstance(ALGO);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            return cipher.doFinal(ciphertext);
        } catch (Exception e) {
            throw new RuntimeException("Decryption failed — data may be corrupted or tampered with", e);
        }
    }

    /**
     * Returns a CipherInputStream that decrypts on-the-fly as the caller reads bytes.
     * Use this for large file downloads — the HTTP response starts immediately
     * without buffering the entire plaintext in memory first.
     *
     * The caller MUST close the returned stream when done.
     *
     * NOTE: AES/GCM defers the authentication-tag check until all ciphertext has
     * been consumed. If the blob is tampered, a BadPaddingException propagates on
     * the last read. By then the HTTP headers are already sent, so the client sees
     * a truncated/broken file — which is the correct behaviour for corrupted data.
     */
    public CipherInputStream decryptStream(byte[] ivAndCiphertext) {
        try {
            byte[] iv = new byte[IV_LENGTH_BYTES];
            System.arraycopy(ivAndCiphertext, 0, iv, 0, IV_LENGTH_BYTES);

            Cipher cipher = Cipher.getInstance(ALGO);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));

            // Skip the IV prefix — wrap only the ciphertext portion
            InputStream ciphertextStream = new ByteArrayInputStream(
                    ivAndCiphertext, IV_LENGTH_BYTES, ivAndCiphertext.length - IV_LENGTH_BYTES);

            return new CipherInputStream(ciphertextStream, cipher);
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialise decryption stream", e);
        }
    }

    /** Convenience for text fields (e.g. bank account number). Returns Base64 string for DB storage. */
    public String encryptText(String plaintext) {
        if (plaintext == null) return null;
        return Base64.getEncoder().encodeToString(encryptBytes(plaintext.getBytes()));
    }

    public String decryptText(String base64Ciphertext) {
        if (base64Ciphertext == null) return null;
        return new String(decryptBytes(Base64.getDecoder().decode(base64Ciphertext)));
    }
}