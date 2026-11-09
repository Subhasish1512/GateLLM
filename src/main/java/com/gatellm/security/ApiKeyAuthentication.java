package com.gatellm.security;

import com.gatellm.config.GateLlmProperties;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;

import java.util.Collection;

public class ApiKeyAuthentication extends AbstractAuthenticationToken {

    private final String apiKeyId;
    private final GateLlmProperties.ApiKeyProperties keyProperties;

    public ApiKeyAuthentication(String apiKeyId,
                                GateLlmProperties.ApiKeyProperties keyProperties,
                                Collection<? extends GrantedAuthority> authorities) {
        super(authorities);
        this.apiKeyId = apiKeyId;
        this.keyProperties = keyProperties;
        setAuthenticated(true);
    }

    public GateLlmProperties.ApiKeyProperties getKeyProperties() {
        return keyProperties;
    }

    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public Object getPrincipal() {
        return apiKeyId;
    }
}
