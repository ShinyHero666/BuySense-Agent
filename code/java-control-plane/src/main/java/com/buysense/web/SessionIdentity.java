package com.buysense.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

@Component
public class SessionIdentity {
    static final String COOKIE = "buysense_identity";
    private static final Duration TTL = Duration.ofDays(180);

    private final byte[] secret;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public SessionIdentity(
            @Value("${buysense.session-secret}") String secret,
            JdbcTemplate jdbc,
            ObjectMapper mapper
    ) {
        if (secret == null || secret.length() < 16) {
            throw new IllegalArgumentException(
                    "buysense.session-secret must contain at least 16 characters");
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public IdentitySession resolve(HttpServletRequest request, HttpServletResponse response) {
        String raw = request.getCookies() == null ? null : Arrays.stream(request.getCookies())
                .filter(cookie -> COOKIE.equals(cookie.getName()))
                .map(Cookie::getValue)
                .findFirst()
                .orElse(null);
        IdentityToken token = decode(raw);
        IdentitySession existing = token == null ? null : find(token);
        if (existing != null) return existing;

        Instant issuedAt = Instant.now();
        IdentitySession created = new IdentitySession(
                "id_" + UUID.randomUUID(),
                "session_" + UUID.randomUUID(),
                issuedAt,
                issuedAt.plus(TTL));
        jdbc.update("""
                insert into identities(identity_id, session_id, issued_at, expires_at)
                values (?, ?, ?, ?)
                """,
                created.identityId(), created.sessionId(),
                Timestamp.from(created.issuedAt()), Timestamp.from(created.expiresAt()));
        Cookie cookie = new Cookie(COOKIE, encode(created));
        cookie.setPath("/");
        cookie.setHttpOnly(true);
        cookie.setSecure(request.isSecure());
        cookie.setMaxAge(Math.toIntExact(TTL.toSeconds()));
        cookie.setAttribute("SameSite", "Lax");
        response.addCookie(cookie);
        return created;
    }

    public void assertSameOrigin(HttpServletRequest request) {
        if ("cross-site".equalsIgnoreCase(request.getHeader("Sec-Fetch-Site"))) {
            throw new CrossSiteRequestException();
        }
        String origin = request.getHeader("Origin");
        if (origin == null || origin.isBlank()) return;
        String host = request.getHeader("Host");
        try {
            if (host == null || !host.equalsIgnoreCase(URI.create(origin).getAuthority())) {
                throw new CrossSiteRequestException();
            }
        } catch (IllegalArgumentException error) {
            throw new CrossSiteRequestException();
        }
    }

    private IdentitySession find(IdentityToken token) {
        if (!token.expiresAt().isAfter(Instant.now())) return null;
        List<IdentitySession> values = jdbc.query("""
                        select identity_id, session_id, issued_at, expires_at
                        from identities
                        where identity_id = ? and session_id = ? and expires_at > ?
                        """,
                (rs, rowNum) -> new IdentitySession(
                        rs.getString("identity_id"),
                        rs.getString("session_id"),
                        rs.getTimestamp("issued_at").toInstant(),
                        rs.getTimestamp("expires_at").toInstant()),
                token.identityId(), token.sessionId(), Timestamp.from(Instant.now()));
        return values.stream().findFirst().orElse(null);
    }

    private String encode(IdentitySession identity) {
        try {
            LinkedHashMap<String, String> token = new LinkedHashMap<>();
            token.put("identityId", identity.identityId());
            token.put("sessionId", identity.sessionId());
            token.put("expiresAt", identity.expiresAt().toString());
            String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                    mapper.writeValueAsBytes(token));
            return "v1." + payload + "." + sign(payload);
        } catch (Exception error) {
            throw new IllegalStateException("unable to encode identity", error);
        }
    }

    private IdentityToken decode(String value) {
        if (value == null) return null;
        String[] parts = value.split("\\.", -1);
        if (parts.length != 3 || !parts[0].equals("v1")
                || parts[1].isBlank() || parts[2].isBlank()) return null;
        if (!MessageDigest.isEqual(
                sign(parts[1]).getBytes(StandardCharsets.US_ASCII),
                parts[2].getBytes(StandardCharsets.US_ASCII))) return null;
        try {
            JsonNode token = mapper.readTree(Base64.getUrlDecoder().decode(parts[1]));
            String identityId = token.path("identityId").asText();
            String sessionId = token.path("sessionId").asText();
            String expiresAt = token.path("expiresAt").asText();
            if (identityId.isBlank() || sessionId.isBlank() || expiresAt.isBlank()) return null;
            return new IdentityToken(identityId, sessionId, Instant.parse(expiresAt));
        } catch (Exception error) {
            return null;
        }
    }

    private String sign(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    mac.doFinal(payload.getBytes(StandardCharsets.US_ASCII)));
        } catch (Exception error) {
            throw new IllegalStateException("unable to sign identity", error);
        }
    }

    public record IdentitySession(
            String identityId,
            String sessionId,
            Instant issuedAt,
            Instant expiresAt
    ) {
    }

    private record IdentityToken(String identityId, String sessionId, Instant expiresAt) {
    }

    public static final class CrossSiteRequestException extends RuntimeException {
        public CrossSiteRequestException() {
            super("cross_site_request_rejected");
        }
    }
}
