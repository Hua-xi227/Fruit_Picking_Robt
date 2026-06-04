package org.example.fruitpickingrobt.store;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

@Component
public class LocationStore {

    private static final String KEY = "location:history";
    private static final int MAX_SIZE = 200;

    private final StringRedisTemplate redis;

    public LocationStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    // 存入一个点，超过200个则移除最早的
    public void add(String locationJson) {
        redis.opsForList().rightPush(KEY, locationJson);
        Long size = redis.opsForList().size(KEY);
        if (size != null && size > MAX_SIZE) {
            redis.opsForList().leftPop(KEY);
        }
    }

    // 返回所有历史点
    public List<String> getAll() {
        List<String> list = redis.opsForList().range(KEY, 0, -1);
        return list != null ? list : Collections.emptyList();
    }

    // 返回最新一个点，没有则返回 null
    public String getLatest() {
        return redis.opsForList().index(KEY, -1);
    }
}
