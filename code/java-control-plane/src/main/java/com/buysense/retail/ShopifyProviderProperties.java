package com.buysense.retail;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Locale;
import java.util.regex.Pattern;

@ConfigurationProperties("buysense.shopify")
public record ShopifyProviderProperties(
        boolean enabled,
        String storeDomain,
        String adminAccessToken,
        String apiVersion,
        boolean fallbackEnabled,
        Duration connectTimeout,
        Duration readTimeout,
        int maxResponseBytes,
        Duration catalogCacheTtl
) {
    public static final String SUPPORTED_API_VERSION = "2026-07";
    private static final Pattern STORE_DOMAIN = Pattern.compile(
            "^(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)\\.myshopify\\.com$");

    public ShopifyProviderProperties {
        storeDomain = storeDomain == null ? "" : storeDomain.trim().toLowerCase(Locale.ROOT);
        adminAccessToken = adminAccessToken == null ? "" : adminAccessToken;
        apiVersion = apiVersion == null || apiVersion.isBlank()
                ? SUPPORTED_API_VERSION : apiVersion.trim();
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(1) : connectTimeout;
        readTimeout = readTimeout == null ? Duration.ofSeconds(2) : readTimeout;
        maxResponseBytes = maxResponseBytes <= 0 ? 1_048_576 : maxResponseBytes;
        catalogCacheTtl = catalogCacheTtl == null ? Duration.ofMinutes(5) : catalogCacheTtl;

        if (!apiVersion.equals(SUPPORTED_API_VERSION)) {
            throw new IllegalArgumentException(
                    "Shopify API version must equal " + SUPPORTED_API_VERSION);
        }
        if (enabled && !STORE_DOMAIN.matcher(storeDomain).matches()) {
            throw new IllegalArgumentException(
                    "enabled Shopify provider requires a bare myshopify.com domain");
        }
        if (enabled && invalidToken(adminAccessToken)) {
            throw new IllegalArgumentException(
                    "enabled Shopify provider requires a valid Admin access token");
        }
        if (connectTimeout.isNegative() || connectTimeout.isZero()
                || readTimeout.compareTo(Duration.ofMillis(50)) < 0
                || readTimeout.compareTo(Duration.ofSeconds(10)) > 0) {
            throw new IllegalArgumentException("Shopify provider timeouts are out of range");
        }
        if (maxResponseBytes < 1_024 || maxResponseBytes > 4 * 1_024 * 1_024) {
            throw new IllegalArgumentException("Shopify response limit is out of range");
        }
        if (catalogCacheTtl.isNegative() || catalogCacheTtl.isZero()
                || catalogCacheTtl.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalArgumentException("Shopify catalog cache TTL is out of range");
        }
    }

    public static ShopifyProviderProperties disabled() {
        return new ShopifyProviderProperties(
                false, "", "", SUPPORTED_API_VERSION, false,
                Duration.ofSeconds(1), Duration.ofSeconds(2),
                1_048_576, Duration.ofMinutes(5));
    }

    private static boolean invalidToken(String value) {
        if (value.isBlank() || value.startsWith("replace-") || value.length() > 4_096
                || !value.equals(value.trim())) {
            return true;
        }
        return value.chars().anyMatch(character -> character < 33 || character > 126);
    }
}
