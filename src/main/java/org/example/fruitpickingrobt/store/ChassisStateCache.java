package org.example.fruitpickingrobt.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * 底盘「最新态」缓存
 * 存两样东西：
 *   1) chassis:latest:{deviceId}  最新一帧完整遥测 JSON —— 前端打开页面第一屏读它，
 *                                 保证刚进来就有数据，不用等下一帧推送
 *   2) chassis:target:{deviceId}  当前目标速度 —— 供 /latest 接口回显"正在以多少速度前进"
 *
 * 为什么都设 TTL（60秒）：
 *   小车断线后不应该永远显示"在线/有速度"。TTL 到期 key 自动消失，
 *   接口就返回空，前端据此显示"设备离线"，比手动清理僵尸数据可靠得多。
 *
 * ── 关于失败处理 ─────────────────────────────────────────────
 * Redis 是「最新态」的唯一来源，所以这里 **不做内存兜底**：
 *   - 写失败 → 只打日志，不抛异常。遥测帧已经进了 MySQL 缓冲队列，历史数据不丢，
 *               只是 /latest 首屏会短暂为空（前端刷新或等下一帧推送即可恢复）。
 *   - 读失败 → 返回 Optional.empty()，前端显示"设备离线"，与其他异常情况表现一致。
 * 这样一旦 Redis 真的没配好，会**立刻在页面上暴露出来**（首屏空白 / 显示离线），
 * 而不是被本地缓存掩盖成一个"看起来一切正常"的假象 —— 排障时更好定位。
 */
@Component
@Slf4j
public class ChassisStateCache {

    private static final String LATEST_PREFIX = "chassis:latest:";
    private static final String TARGET_PREFIX = "chassis:target:";
    private static final Duration TTL = Duration.ofSeconds(60);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public ChassisStateCache(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    /**
     * 写入最新一帧遥测（每条遥测都会调用，一次 SET，O(1)，非常快）。
     * 失败只记日志，不影响主流程 —— 数据落库不依赖这一步。
     */
    public void saveLatest(String deviceId, ObjectNode json) {
        try {
            redis.opsForValue().set(LATEST_PREFIX + deviceId, json.toString(), TTL);
        } catch (Exception e) {
            // 遥测已完成MySQL缓冲，此处失败仅使`/latest`为空；此外，下一帧覆盖率也很高，无需重试
            log.warn("写 Redis 最新态失败: deviceId={}, {}", deviceId, e.getMessage());
        }
    }

    /** 读取最新一帧（REST /latest 接口用） */
    public Optional<JsonNode> readLatest(String deviceId) {
        try {
            String json = redis.opsForValue().get(LATEST_PREFIX + deviceId);
            if (json != null) {
                return Optional.of(objectMapper.readTree(json));
            }
        } catch (Exception e) {
            log.warn("读 Redis 最新态失败: deviceId={}, {}", deviceId, e.getMessage());
        }
        return Optional.empty();
    }

    /** 更新目标速度（前端下发 motion 时调用） */
    public void saveTarget(String deviceId, double linear, double angular) {
        try {
            ObjectNode n = objectMapper.createObjectNode();
            n.put("linear", linear);
            n.put("angular", angular);
            n.put("updatedAt", System.currentTimeMillis());
            redis.opsForValue().set(TARGET_PREFIX + deviceId, n.toString(), TTL);
        } catch (Exception e) {
            log.warn("写 Redis 目标速度失败: deviceId={}, {}", deviceId, e.getMessage());
        }
    }

    /** 读取目标速度（回显用：让前端知道当前指令是什么） */
    public Optional<JsonNode> readTarget(String deviceId) {
        try {
            String json = redis.opsForValue().get(TARGET_PREFIX + deviceId);
            if (json != null) {
                return Optional.of(objectMapper.readTree(json));
            }
        } catch (Exception e) {
            log.warn("读 Redis 目标速度失败: deviceId={}, {}", deviceId, e.getMessage());
        }
        return Optional.empty();
    }

}
