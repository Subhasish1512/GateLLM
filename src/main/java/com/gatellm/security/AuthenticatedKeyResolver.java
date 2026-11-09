package com.gatellm.security;

import com.gatellm.config.GateLlmProperties;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Resolves the authenticated API key properties from the security context.
 * Injected into controllers so they do not depend on {@link SecurityContextHolder} directly.
 */
@Component
public class AuthenticatedKeyResolver {

    public GateLlmProperties.ApiKeyProperties getKeyProperties() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof ApiKeyAuthentication apiKeyAuth) {
            return apiKeyAuth.getKeyProperties();
        }
        throw new IllegalStateException("No API key authentication found in security context");
    }

    public String getApiKeyId() {
        return getKeyProperties().apiKeyId();
    }
}
