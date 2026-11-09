package com.gatellm.security;

import com.gatellm.config.GateLlmProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Authenticates requests via the {@code X-API-Key} header.
 * Keys are stored as SHA-256 hashes in config; comparison is constant-time.
 * <p>Not a {@code @Component} — registered as a bean through {@link SecurityConfig}.
 */
public class ApiKeyFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyFilter.class);
    private static final String API_KEY_HEADER = "X-API-Key";

    private final Map<String, GateLlmProperties.ApiKeyProperties> keyMap;

    public ApiKeyFilter(GateLlmProperties properties) {
        List<GateLlmProperties.ApiKeyProperties> apiKeys = properties.apiKeys();
        this.keyMap = apiKeys != null
                ? apiKeys.stream().collect(Collectors.toMap(
                        GateLlmProperties.ApiKeyProperties::keyHash,
                        Function.identity()))
                : Map.of();
        log.info("API key filter initialized with {} configured key(s)", keyMap.size());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain)
            throws ServletException, IOException {

        String apiKey = request.getHeader(API_KEY_HEADER);

        if (apiKey == null || apiKey.isBlank()) {
            log.warn("Missing API key from {}", request.getRemoteAddr());
            sendUnauthorized(response, "Missing API key. Provide a valid key in the X-API-Key header.");
            return;
        }

        String incomingHash = sha256(apiKey);
        GateLlmProperties.ApiKeyProperties matchedEntry = findMatchingKey(incomingHash);

        if (matchedEntry == null) {
            log.warn("Invalid API key attempt from {} (key: {})",
                    request.getRemoteAddr(), maskKey(apiKey));
            sendUnauthorized(response, "Invalid API key.");
            return;
        }

        log.debug("Authenticated apiKeyId={}", matchedEntry.apiKeyId());

        var authentication = new ApiKeyAuthentication(
                matchedEntry.apiKeyId(), matchedEntry, List.of());
        SecurityContextHolder.getContext().setAuthentication(authentication);

        chain.doFilter(request, response);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/actuator");
    }

    /**
     * Constant-time comparison: iterates all stored hashes with
     * {@link MessageDigest#isEqual} to avoid timing side-channels.
     */
    private GateLlmProperties.ApiKeyProperties findMatchingKey(String incomingHash) {
        byte[] incomingBytes = incomingHash.getBytes(StandardCharsets.UTF_8);
        for (var entry : keyMap.entrySet()) {
            byte[] storedBytes = entry.getKey().getBytes(StandardCharsets.UTF_8);
            if (MessageDigest.isEqual(incomingBytes, storedBytes)) {
                return entry.getValue();
            }
        }
        return null;
    }

    static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }

    static String maskKey(String key) {
        if (key == null || key.length() <= 8) return "****";
        return key.substring(0, 4) + "****" + key.substring(key.length() - 4);
    }

    private void sendUnauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(
                "{\"status\": 401, \"message\": \"%s\"}".formatted(message));
    }
}
