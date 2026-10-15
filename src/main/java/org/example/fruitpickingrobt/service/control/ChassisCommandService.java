package org.example.fruitpickingrobt.service.control;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.example.fruitpickingrobt.dao.chassis.ChassisCommandDAO;
import org.example.fruitpickingrobt.entity.chassis.ChassisCommand;
import org.example.fruitpickingrobt.store.ChassisStateCache;
import org.example.fruitpickingrobt.websocket.DeviceSessionRegistry;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;

/**
 * 底盘控制：把「目标速度」和「一次性动作」下发给小车。
 *
 * 这里下发的是「目标速度」，不是「一次性的前进命令」。
 *
 * 原因：SCOUT 2.0 底盘有安全机制——**500ms 内收不到新的运动指令帧，速度自动归零**。
 *   前端按住 → 每 50ms 刷一次目标速度 → 服务端转发 → 车载本地 50Hz 循环持续发送
 *   前端松手 → 发一次 (0,0) → 车载继续以 0 速度发帧（保持使能、保持静止、随时可动）
 *
 * 服务端只负责"更新目标"，真正"持续喂指令"的是车载的 50Hz 控制环（scout_bridge.py）。
 * 这样即使网络抖动 200ms，小车也不会停。
 */
@Service
@Slf4j
public class ChassisCommandService {
    /** 允许的底盘动作白名单（防止任意字符串下发到小车） */
    private static final Set<String> ALLOWED_ACTIONS = Set.of(
            "enable",           // 切换到指令控制模式（上电后必须先执行，否则底盘不响应代码指令）
            "clear_error",      // 清除急停/故障错误（急停释放后必须执行，否则一直锁死）
            "stop",             // 停止（速度归零，保持使能）
            "emergency_stop",   // 急停
            "set_light"         // 灯光控制
    );

    private final DeviceSessionRegistry registry;
    private final ChassisCommandDAO dao;
    private final ChassisStateCache cache;
    private final ObjectMapper objectMapper;

    public ChassisCommandService(DeviceSessionRegistry registry,
                                 ChassisCommandDAO dao,
                                 ChassisStateCache cache,
                                 ObjectMapper objectMapper) {
        this.registry = registry;
        this.dao = dao;
        this.cache = cache;
        this.objectMapper = objectMapper;
    }

    /**
     * 下发一次性动作（enable / clear_error / emergency_stop / stop）。
     * 这类是低频的、需要审计的，所以要落库。
     *
     * @param source 来源标记：REST（HTTP接口）或 WS（WebSocket），便于排查是谁发的
     */
    public ChassisCommand sendAction(String deviceId, String action,
                                     Map<String, Object> params, Long operatorId, String source) {
        ChassisCommand cmd = new ChassisCommand();
        cmd.setDeviceId(deviceId);
        cmd.setAction(action);
        cmd.setSource(source);
        cmd.setOperatorId(operatorId);
        cmd.setSentAt(LocalDateTime.now());
        cmd.setResult(ChassisCommand.RESULT_PENDING);

        if (deviceId == null || deviceId.isBlank()) {
            cmd.setResult(ChassisCommand.RESULT_FAILED);
            cmd.setMessage("deviceId 不能为空");
            return cmd;
        }
        if (!ALLOWED_ACTIONS.contains(action)) {
            cmd.setResult(ChassisCommand.RESULT_FAILED);
            cmd.setMessage("不支持的动作: " + action);
            return cmd;
        }
        // 设备在线检查：直接查内存 ConcurrentHashMap，O(1)，不查数据库
        if (!registry.isOnline(deviceId)) {
            cmd.setResult(ChassisCommand.RESULT_OFFLINE);
            cmd.setMessage("设备不在线，指令未下发");
            dao.insert(cmd);
            return cmd;
        }

        try {
            if (params != null) {
                cmd.setParamsJson(objectMapper.writeValueAsString(params));
            }
        } catch (Exception ignored) {
            cmd.setParamsJson("{}");
        }
        dao.insert(cmd);

        // 组装下发给小车的 JSON
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("type", "command");
        payload.put("commandId", cmd.getId());
        payload.put("command", action);
        if (params != null) {
            payload.set("params", objectMapper.valueToTree(params));
        }

        WebSocketSession session = registry.getSession(deviceId);
        if (session == null || !session.isOpen()) {
            cmd.setResult(ChassisCommand.RESULT_OFFLINE);
            cmd.setMessage("设备会话已断开");
            dao.markResult(cmd.getId(), cmd.getResult(), cmd.getMessage());
            return cmd;
        }
        try {
            session.sendMessage(new TextMessage(payload.toString()));
            cmd.setResult(ChassisCommand.RESULT_OK);
            log.info("[底盘动作] deviceId={}, action={}, id={}", deviceId, action, cmd.getId());
        } catch (Exception e) {
            cmd.setResult(ChassisCommand.RESULT_FAILED);
            cmd.setMessage("发送失败: " + e.getMessage());
            log.error("[底盘动作] 下发失败: {}", e.getMessage());
        }
        dao.markResult(cmd.getId(), cmd.getResult(), cmd.getMessage());

        // 急停后要把目标速度也清掉，避免车载控制环继续按旧目标跑
        if ("emergency_stop".equals(action) || "stop".equals(action)) {
            cache.saveTarget(deviceId, 0.0, 0.0);
        }
        return cmd;
    }

    /**
     * 转发目标速度（高频路径，20Hz 调用）。
     *
     * ★ 这个方法不落库——每秒 20 条指令记录毫无价值，只会撑爆表。
     *   只更新 Redis 的目标值（供 /latest 回显）+ 转发给小车。
     *
     * 同样做速度钳制：即使前端传了 99，也不会真的让小车超速。这是服务端的最后一道安全闸。
     */
    public void forwardMotion(String deviceId, double linear, double angular, String source) {
        if (deviceId == null || deviceId.isBlank()) {
            return;
        }
        // 安全钳制：SCOUT 2.0 最高 1.67 m/s
        linear = Math.max(-1.67, Math.min(1.67, linear));

        // 记录目标（Redis，供回显）
        cache.saveTarget(deviceId, linear, angular);

        WebSocketSession session = registry.getSession(deviceId);
        if (session == null || !session.isOpen()) {
            log.warn("[底盘运动] 设备不在线，目标未下发: {}", deviceId);
            return;
        }
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("type", "motion");
        payload.put("deviceId", deviceId);
        payload.put("linear", linear);
        payload.put("angular", angular);
        payload.put("ts", System.currentTimeMillis());
        try {
            session.sendMessage(new TextMessage(payload.toString()));
        } catch (Exception e) {
            log.error("[底盘运动] 转发失败: {}", e.getMessage());
        }
    }
}
