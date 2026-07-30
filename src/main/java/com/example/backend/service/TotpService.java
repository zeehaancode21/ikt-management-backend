package com.example.backend.service;

import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Locale;

/**
 * Time-based One-Time Password service (RFC 6238 / RFC 4226), fully
 * compatible with Google Authenticator, Authy, 1Password, etc. — this is
 * what generates the rotating 6-digit code on the owner's phone.
 *
 * No external dependency required: just HMAC-SHA1 (built into the JDK) plus
 * a small RFC 4648 Base32 codec, since TOTP secrets are conventionally
 * shared as Base32 (so they're easy to type if QR scanning fails).
 */
@Service
public class TotpService {

    private static final String HMAC_ALGO = "HmacSHA1";
    private static final int TIME_STEP_SECONDS = 30;
    private static final int CODE_DIGITS = 6;
    private static final String BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private final SecureRandom random = new SecureRandom();

    /** Generates a fresh random 160-bit secret, Base32-encoded. */
    public String generateSecret() {
        byte[] bytes = new byte[20];
        random.nextBytes(bytes);
        return base32Encode(bytes);
    }

    /** otpauth:// URI to render as a QR code for the authenticator app. */
    public String buildOtpAuthUri(String secretBase32, String accountLabel, String issuer) {
        String encodedLabel = issuer + ":" + accountLabel;
        return String.format(
                "otpauth://totp/%s?secret=%s&issuer=%s&algorithm=SHA1&digits=%d&period=%d",
                encodedLabel.replace(" ", "%20"),
                secretBase32,
                issuer.replace(" ", "%20"),
                CODE_DIGITS,
                TIME_STEP_SECONDS
        );
    }

    /** Verifies a 6-digit code, tolerating +/-1 time step (~30s) of clock drift. */
    public boolean verifyCode(String secretBase32, String code) {
        if (code == null || !code.matches("\\d{6}")) return false;
        long currentStep = Instant.now().getEpochSecond() / TIME_STEP_SECONDS;
        for (long step = currentStep - 1; step <= currentStep + 1; step++) {
            if (generateCodeForStep(secretBase32, step).equals(code)) {
                return true;
            }
        }
        return false;
    }

    private String generateCodeForStep(String secretBase32, long step) {
        try {
            byte[] key = base32Decode(secretBase32);
            byte[] msg = new byte[8];
            long counter = step;
            for (int i = 7; i >= 0; i--) {
                msg[i] = (byte) (counter & 0xFF);
                counter >>= 8;
            }

            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(key, HMAC_ALGO));
            byte[] hash = mac.doFinal(msg);

            int offset = hash[hash.length - 1] & 0x0F;
            int binary = ((hash[offset] & 0x7F) << 24)
                    | ((hash[offset + 1] & 0xFF) << 16)
                    | ((hash[offset + 2] & 0xFF) << 8)
                    | (hash[offset + 3] & 0xFF);

            int otp = binary % 1_000_000;
            return String.format("%06d", otp);
        } catch (Exception e) {
            throw new RuntimeException("TOTP generation failed", e);
        }
    }

    // ---- RFC 4648 Base32 codec (no padding on encode; ASCII only) ----

    private String base32Encode(byte[] data) {
        StringBuilder sb = new StringBuilder();
        int bits = 0, value = 0;
        for (byte b : data) {
            value = (value << 8) | (b & 0xFF);
            bits += 8;
            while (bits >= 5) {
                sb.append(BASE32_ALPHABET.charAt((value >>> (bits - 5)) & 0x1F));
                bits -= 5;
            }
        }
        if (bits > 0) {
            sb.append(BASE32_ALPHABET.charAt((value << (5 - bits)) & 0x1F));
        }
        return sb.toString();
    }

    private byte[] base32Decode(String encoded) {
        String clean = encoded.replace("=", "").toUpperCase(Locale.ROOT);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int bits = 0, value = 0;
        for (char c : clean.toCharArray()) {
            int idx = BASE32_ALPHABET.indexOf(c);
            if (idx < 0) continue;
            value = (value << 5) | idx;
            bits += 5;
            if (bits >= 8) {
                out.write((value >>> (bits - 8)) & 0xFF);
                bits -= 8;
            }
        }
        return out.toByteArray();
    }
}