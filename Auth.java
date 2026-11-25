package branch;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Password hashing (PBKDF2), sessions and login throttling. */
public final class Auth {
    private Auth() {}
    private static final SecureRandom RNG = new SecureRandom();
    private static final long SESSION_MS = 12L * 3600 * 1000;

    public record Session(long userId, String email, String name, String role, long orgId, String orgName, long expires) {}
    private static final Map<String, Session> SESSIONS = new ConcurrentHashMap<>();
    private static final Map<String, long[]> FAILS = new ConcurrentHashMap<>();   // {count, lockedUntil}

    public static String[] hash(String password) {
        byte[] salt = new byte[16]; RNG.nextBytes(salt);
        return new String[]{Base64.getEncoder().encodeToString(salt), derive(password, salt)};
    }
    public static boolean verify(String password, String saltB64, String hashB64) {
        String h = derive(password, Base64.getDecoder().decode(saltB64));
        return MessageDigest.isEqual(h.getBytes(), hashB64.getBytes());
    }
    private static String derive(String pw, byte[] salt) {
        try {
            var spec = new PBEKeySpec(pw.toCharArray(), salt, 120_000, 256);
            return Base64.getEncoder().encodeToString(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded());
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    public static String open(long userId, String email, String name, String role, long orgId, String orgName) {
        byte[] b = new byte[32]; RNG.nextBytes(b);
        String token = HexFormat.of().formatHex(b);
        SESSIONS.put(token, new Session(userId, email, name, role, orgId, orgName, System.currentTimeMillis() + SESSION_MS));
        return token;
    }
    public static Session get(String token) {
        if (token == null) return null;
        Session s = SESSIONS.get(token);
        if (s == null) return null;
        if (s.expires() < System.currentTimeMillis()) { SESSIONS.remove(token); return null; }
        return s;
    }
    public static void close(String token) { if (token != null) SESSIONS.remove(token); }
    public static void closeAllFor(long userId) { SESSIONS.values().removeIf(s -> s.userId() == userId); }

    public static void checkEmail(String email) {
        if (!email.matches("[^@\\s]{1,64}@[^@\\s]+\\.[^@\\s]{2,}")) throw ApiException.bad("Enter a valid email address.");
    }
    public static void checkPassword(String pw) {
        if (pw.length() < 8 || pw.length() > 200 || !pw.matches(".*[A-Za-z].*") || !pw.matches(".*\\d.*"))
            throw ApiException.bad("Use at least 8 characters, with a letter and a number.");
    }
    /** Burns the same time as a real check so unknown emails can't be told apart by speed. */
    public static void dummyVerify(String pw) { derive(pw, new byte[16]); }

    public static void checkNotLocked(String username) {
        long[] f = FAILS.get(username.toLowerCase());
        if (f != null && f[1] > System.currentTimeMillis())
            throw new ApiException(429, "Too many attempts. Try again in a few minutes.");
    }
    public static void fail(String username) {
        long[] f = FAILS.computeIfAbsent(username.toLowerCase(), k -> new long[2]);
        if (++f[0] >= 5) { f[0] = 0; f[1] = System.currentTimeMillis() + 5 * 60_000; }
    }
    public static void succeed(String username) { FAILS.remove(username.toLowerCase()); }
}
