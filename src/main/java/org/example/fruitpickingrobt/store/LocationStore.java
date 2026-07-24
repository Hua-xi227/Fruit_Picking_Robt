package org.example.fruitpickingrobt.store;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/** Redis 中每台设备的最新位置、状态和机械臂参数。 */
@Component
public class LocationStore {
    private static final String KEY_PREFIX = "device:latest:";

    private final StringRedisTemplate redis;

    public LocationStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void put(String deviceId, String field, String payloadJson) {
        redis.opsForHash().put(KEY_PREFIX + deviceId, field, payloadJson);
    }

    public Map<String, String> getAll(String deviceId) {
        Map<String, String> result = new HashMap<>();
        redis.opsForHash().entries(KEY_PREFIX + deviceId)
                .forEach((field, value) -> result.put(field.toString(), value.toString()));
        return result;
    }
}
