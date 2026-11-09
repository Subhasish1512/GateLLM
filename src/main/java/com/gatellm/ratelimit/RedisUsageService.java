package com.gatellm.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

@Component
public class RedisUsageService implements UsageService {

    private static final Logger log = LoggerFactory.getLogger(RedisUsageService.class);
    private static final String KEY_PREFIX = "usage:";
    private static final long TTL_SECONDS = 172_800; // 2 days

    /*
     * Lua script: atomically HINCRBY all three counters, set TTL if new key,
     * return total tokens consumed (inputTokens + outputTokens).
     */
    private static final String RECORD_USAGE_LUA = """
            local key          = KEYS[1]
            local inputTokens  = tonumber(ARGV[1])
            local outputTokens = tonumber(ARGV[2])
            local ttlSeconds   = tonumber(ARGV[3])

            redis.call('HINCRBY', key, 'requests',     1)
            redis.call('HINCRBY', key, 'inputTokens',  inputTokens)
            redis.call('HINCRBY', key, 'outputTokens', outputTokens)

            if redis.call('TTL', key) == -1 then
                redis.call('EXPIRE', key, ttlSeconds)
            end

            local totalIn  = tonumber(redis.call('HGET', key, 'inputTokens')  or '0')
            local totalOut = tonumber(redis.call('HGET', key, 'outputTokens') or '0')
            return totalIn + totalOut
            """;

    private final StringRedisTemplate redisTemplate;
    private final DefaultRedisScript<Long> recordUsageScript;

    public RedisUsageService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.recordUsageScript = new DefaultRedisScript<>();
        this.recordUsageScript.setScriptText(RECORD_USAGE_LUA);
        this.recordUsageScript.setResultType(Long.class);
    }

    @Override
    public long getTokensUsedToday(String apiKeyId) {
        String key = usageKey(apiKeyId);
        Object input = redisTemplate.opsForHash().get(key, "inputTokens");
        Object output = redisTemplate.opsForHash().get(key, "outputTokens");
        return parseLong(input) + parseLong(output);
    }

    @Override
    public long recordUsage(String apiKeyId, int inputTokens, int outputTokens) {
        String key = usageKey(apiKeyId);

        Long totalTokens = redisTemplate.execute(
                recordUsageScript,
                List.of(key),
                String.valueOf(inputTokens),
                String.valueOf(outputTokens),
                String.valueOf(TTL_SECONDS));

        long result = totalTokens != null ? totalTokens : 0;
        log.debug("Recorded usage for '{}': +{}in/+{}out → total={} tokens",
                apiKeyId, inputTokens, outputTokens, result);
        return result;
    }

    @Override
    public UsageStats getUsageStats(String apiKeyId) {
        String key = usageKey(apiKeyId);
        Map<Object, Object> entries = redisTemplate.opsForHash().entries(key);
        return new UsageStats(
                parseLong(entries.get("requests")),
                parseLong(entries.get("inputTokens")),
                parseLong(entries.get("outputTokens"))
        );
    }

    private String usageKey(String apiKeyId) {
        String today = LocalDate.now(ZoneOffset.UTC).toString();
        return KEY_PREFIX + apiKeyId + ":" + today;
    }

    private static long parseLong(Object value) {
        if (value == null) return 0;
        if (value instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
