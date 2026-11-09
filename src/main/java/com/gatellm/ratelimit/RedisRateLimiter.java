package com.gatellm.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class RedisRateLimiter implements RateLimiterService {

    private static final Logger log = LoggerFactory.getLogger(RedisRateLimiter.class);
    private static final String KEY_PREFIX = "ratelimit:";

    /*
     * Lua script: atomically INCR the counter and set EXPIRE only on first hit.
     * Returns {currentCount, ttlSeconds}.
     */
    private static final String RATE_LIMIT_LUA = """
            local key     = KEYS[1]
            local limit   = tonumber(ARGV[1])
            local window  = tonumber(ARGV[2])
            local current = redis.call('INCR', key)
            if current == 1 then
                redis.call('EXPIRE', key, window)
            end
            local ttl = redis.call('TTL', key)
            return {current, ttl}
            """;

    private final StringRedisTemplate redisTemplate;
    private final DefaultRedisScript<List<Long>> rateLimitScript;

    @SuppressWarnings("unchecked")
    public RedisRateLimiter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.rateLimitScript = new DefaultRedisScript<>();
        this.rateLimitScript.setScriptText(RATE_LIMIT_LUA);
        this.rateLimitScript.setResultType((Class) List.class);
    }

    @Override
    public RateLimitResult checkRateLimit(String apiKeyId, int limit, int windowSeconds) {
        String key = KEY_PREFIX + apiKeyId;

        List<Long> result = redisTemplate.execute(
                rateLimitScript,
                List.of(key),
                String.valueOf(limit),
                String.valueOf(windowSeconds));

        if (result == null || result.size() < 2) {
            log.error("Unexpected Lua result for rate limit check on key '{}'", key);
            return new RateLimitResult(true, limit, 0);
        }

        long current = result.get(0);
        long ttl = result.get(1);
        boolean allowed = current <= limit;
        long remaining = Math.max(0, limit - current);
        long retryAfter = allowed ? 0 : Math.max(1, ttl);

        log.debug("Rate limit {}: current={}, limit={}, remaining={}, allowed={}",
                apiKeyId, current, limit, remaining, allowed);

        return new RateLimitResult(allowed, remaining, retryAfter);
    }

    @Override
    public long getRemainingRequests(String apiKeyId, int limit) {
        String key = KEY_PREFIX + apiKeyId;
        String value = redisTemplate.opsForValue().get(key);
        long current = value != null ? Long.parseLong(value) : 0;
        return Math.max(0, limit - current);
    }
}
