package com.moyuan.buysense.web;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;

@Component
public class SessionIdentity {
    static final String COOKIE = "moyuan_session";
    private final byte[] secret;

    public SessionIdentity(@Value("${buysense.session-secret}") String secret) {
        if (secret == null || secret.length() < 16) {
            throw new IllegalArgumentException("buysense.session-secret must contain at least 16 characters");
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    public String resolve(HttpServletRequest request, HttpServletResponse response) {
        String existing = request.getCookies() == null ? null : Arrays.stream(request.getCookies())
                .filter(cookie -> COOKIE.equals(cookie.getName()))
                .map(Cookie::getValue)
                .map(this::verify)
                .filter(value -> value != null)
                .findFirst()
                .orElse(null);
        if (existing != null) return existing;
        String sessionId = UUID.randomUUID().toString();
        Cookie cookie = new Cookie(COOKIE, sessionId + "." + sign(sessionId));
        cookie.setHttpOnly(true);
        cookie.setSecure(false);
        cookie.setPath("/");
        cookie.setMaxAge(60 * 60 * 24 * 180);
        cookie.setAttribute("SameSite", "Lax");
        response.addCookie(cookie);
        return sessionId;
    }

    private String verify(String value) {
        int separator = value.indexOf('.');
        if (separator <= 0 || separator == value.length() - 1) return null;
        String sessionId = value.substring(0, separator);
        if (!sessionId.matches("[a-f0-9-]{36}")) return null;
        byte[] expected = sign(sessionId).getBytes(StandardCharsets.US_ASCII);
        byte[] supplied = value.substring(separator + 1).getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, supplied) ? sessionId : null;
    }

    private String sign(String sessionId) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(sessionId.getBytes(StandardCharsets.US_ASCII)));
        } catch (Exception error) {
            throw new IllegalStateException("unable to sign session identity", error);
        }
    }
}
