package uems;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Properties;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

// INFRASTRUCTURE: configuration, password hashing, Arabic text normalization, token generation.

// ======== infra ========

/** Normalizes text for bilingual search: case-folding, diacritics removal and letter-variant unification (FR-SRC-06). */
final class ArabicNormalizer {
    private ArabicNormalizer() {}

    /** Returns a lower-case form without Arabic diacritics/tatweel, with alef, yaa and taa-marbuta variants unified. */
    public static String normalize(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            if ((c >= '\u064B' && c <= '\u065F') || c == '\u0670' || c == '\u0640') continue;
            switch (c) {
                case '\u0622', '\u0623', '\u0625', '\u0671' -> sb.append('\u0627');
                case '\u0649' -> sb.append('\u064A');
                case '\u0629' -> sb.append('\u0647');
                default -> {
                    if (c >= '\u0660' && c <= '\u0669') sb.append((char) ('0' + (c - '\u0660')));
                    else sb.append(c);
                }
            }
        }
        return sb.toString().toLowerCase(Locale.ROOT).trim();
    }
}

/** Application configuration loaded from a properties file kept outside version control (NFR-SEC-06). */
final class Config {
    private final Properties props;

    public Config(Properties props) { this.props = props; }

    /** Loads configuration from the given file. */
    public static Config load(Path file) throws IOException {
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(file)) { p.load(in); }
        return new Config(p);
    }

    /** Returns the value for a key or the default. */
    public String get(String key, String def) { return props.getProperty(key, def); }

    /** Returns the value for a key; fails if it is missing. */
    public String require(String key) {
        String v = props.getProperty(key);
        if (v == null || v.isBlank()) throw new IllegalStateException("Missing configuration key: " + key);
        return v.trim();
    }

    /** Returns an integer value or the default. */
    public int getInt(String key, int def) {
        String v = props.getProperty(key);
        return v == null ? def : Integer.parseInt(v.trim());
    }
}

/** A stored PBKDF2 password hash: iteration count, salt and derived key (NFR-SEC-01). */
record PasswordHash(int iterations, byte[] salt, byte[] hash) {
    /** Encodes as {@code pbkdf2-sha256$iterations$salt$hash} for storage. */
    public String encode() {
        Base64.Encoder e = Base64.getEncoder();
        return "pbkdf2-sha256$" + iterations + "$" + e.encodeToString(salt) + "$" + e.encodeToString(hash);
    }

    /** Parses the stored form produced by {@link #encode()}. */
    public static PasswordHash decode(String s) {
        String[] p = s.split("\\$");
        if (p.length != 4 || !p[0].equals("pbkdf2-sha256")) throw new IllegalArgumentException("Bad hash format");
        Base64.Decoder d = Base64.getDecoder();
        return new PasswordHash(Integer.parseInt(p[1]), d.decode(p[2]), d.decode(p[3]));
    }
}

/** Salted PBKDF2-HMAC-SHA256 password hashing with constant-time verification (FR-AUT-02, NFR-SEC-01). */
final class PasswordHasher {
    /** OWASP-recommended iteration count at the time of writing. */
    public static final int DEFAULT_ITERATIONS = 600_000;
    private static final int SALT_BYTES = 16;
    private static final int KEY_BITS = 256;
    private final int iterations;
    private final SecureRandom random = new SecureRandom();

    public PasswordHasher() { this(DEFAULT_ITERATIONS); }
    public PasswordHasher(int iterations) { this.iterations = iterations; }

    /** Hashes a password with a fresh random salt. The caller's array is wiped afterwards. */
    public PasswordHash hash(char[] password) {
        byte[] salt = new byte[SALT_BYTES];
        random.nextBytes(salt);
        try { return new PasswordHash(iterations, salt, derive(password, salt, iterations)); }
        finally { Arrays.fill(password, '\0'); }
    }

    /** Verifies a password using the iteration count and salt stored with the hash. The array is wiped afterwards. */
    public boolean verify(char[] password, PasswordHash stored) {
        try {
            byte[] actual = derive(password, stored.salt(), stored.iterations());
            return MessageDigest.isEqual(actual, stored.hash());
        } finally { Arrays.fill(password, '\0'); }
    }

    private static byte[] derive(char[] pw, byte[] salt, int iter) {
        PBEKeySpec spec = new PBEKeySpec(pw, salt, iter, KEY_BITS);
        try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); }
        catch (GeneralSecurityException e) { throw new IllegalStateException(e); }
        finally { spec.clearPassword(); }
    }
}

/** Generates random session tokens (128 bit). */
final class SecureTokens {
    private static final SecureRandom RANDOM = new SecureRandom();
    private SecureTokens() {}

    /** Returns a new random 128-bit token as hex. */
    public static String newToken() {
        byte[] b = new byte[16];
        RANDOM.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }
}
