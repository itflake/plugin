package plugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class TokenManager {
    private static final Map<String, Long> validTokens = new ConcurrentHashMap<>();
    private static final long EXPIRE_MS = 5 * 60 * 1000;

    public static String generateToken() {
        String token = UUID.randomUUID().toString();
        validTokens.put(token, System.currentTimeMillis() + EXPIRE_MS);
        return token;
    }

    public static boolean isValid(String token) {
        Long expiry = validTokens.get(token);
        if (expiry == null) return false;
        if (System.currentTimeMillis() > expiry) {
            validTokens.remove(token);
            return false;
        }
        return true;
    }

    public static void cleanUpExpiredTokens() {
        long now = System.currentTimeMillis();
        validTokens.entrySet().removeIf(e -> e.getValue() < now);
    }
}

