package org.example.fruitpickingrobt.websocket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 设备会话注册表（下行控制核心）
 *
 * 用 ConcurrentHashMap 维护「设备ID <-> WebSocket 会话」的映射，
 * 是服务端把控制指令精准转发给某一台小车的基础。
 *
 * 为什么用 ConcurrentHashMap：
 *   - WebSocket 连接/断开 与 HTTP 下发指令 可能并发访问，需要线程安全；
 *   - 相比 Collections.synchronizedMap，它在读多写少场景下性能更好（分段锁）；
 *   - 不能用 HashMap：并发 put/remove 会导致死循环或数据丢失。
 *
 * 心跳保活（本次补全）：
 *   - 设备每 N 秒发一次 {"type":"heartbeat","deviceId":"..."}，register() / recordHeartbeat()
 *     都会刷新 lastHeartbeatAt；
 *   - 服务端用 @Scheduled 定时扫描，超过 staleTimeoutSeconds 没心跳的会话会被主动关闭并清理，
 *     避免「僵尸连接」一直显示在线、还能被下发指令却收不到回执。
 *   - 阈值通过 application.yml 的 app.device-session.stale-timeout-seconds 配置；
 *     设为 0 或负数 = 停用清理（默认 0，便于本地测试）；生产建议设 60。
 *
 * 注意：WebSocketSession 本身不保证线程安全，调用 sendMessage 时必须做
 *       isOpen() 判断并捕获 IOException（见 CommandService）。
 */
@Component
public class DeviceSessionRegistry {

    private static final Logger log = LoggerFactory.getLogger(DeviceSessionRegistry.class);

    /**
     * 心跳超过该秒数未刷新，判定为僵尸会话并强制关闭。
     * 通过 application.yml 的 app.device-session.stale-timeout-seconds 配置；
     * 设 0 / 负数 = 停用清理（默认 0，便于本地测试）；生产建议设 60。
     */
    @Value("${app.device-session.stale-timeout-seconds:0}")
    private long staleTimeoutSeconds;

    /** deviceId -> 该设备当前的 WebSocket 会话 */
    private final ConcurrentHashMap<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    /** deviceId -> 最近一次收到心跳/任意上行消息的时间戳（毫秒） */
    private final ConcurrentHashMap<String, Long> lastHeartbeatAt = new ConcurrentHashMap<>();

    /** 登记一个设备会话（同一设备重复连接时，新会话覆盖旧会话） */
    public void register(String deviceId, WebSocketSession session) {
        if (deviceId == null || session == null) {
            return;
        }
        sessions.put(deviceId, session);
        lastHeartbeatAt.put(deviceId, System.currentTimeMillis());
        log.info("[会话注册] deviceId={}, 当前在线={}", deviceId, sessions.size());
    }

    /** 收到一次心跳（或任意上行消息）时刷新存活时间，不强制要求先 register */
    public void recordHeartbeat(String deviceId) {
        if (deviceId == null) {
            return;
        }
        lastHeartbeatAt.put(deviceId, System.currentTimeMillis());
    }

    /** 取出某设备的会话（可能为 null，表示设备离线） */
    public WebSocketSession getSession(String deviceId) {
        return sessions.get(deviceId);
    }

    /** 设备是否在线（会话存在且仍打开） */
    public boolean isOnline(String deviceId) {
        WebSocketSession session = sessions.get(deviceId);
        return session != null && session.isOpen();
    }

    /** 距离上次心跳的秒数（未注册返回 Long.MAX_VALUE） */
    public long secondsSinceHeartbeat(String deviceId) {
        Long ts = lastHeartbeatAt.get(deviceId);
        if (ts == null) {
            return Long.MAX_VALUE;
        }
        return (System.currentTimeMillis() - ts) / 1000;
    }

    /** 当前所有在线设备的 ID 集合 */
    public Set<String> onlineDeviceIds() {
        return sessions.keySet();
    }

    /**
     * 移除某个设备会话（连接关闭时调用）
     *
     * ★ 为什么必须带 session 参数：
     *   sessions 是 deviceId -> 单个会话 的映射。如果真车和模拟器（或两个页面）
     *   用了同一个 deviceId，后连的会顶掉先连的。此时若其中一端断开，
     *   无条件的 remove(deviceId) 会把**另一端正在使用的会话一起注销掉** ——
     *   表现为"我这边明明还连着，指令却突然下不来了"。
     *
     *   ConcurrentMap.remove(key, value) 是原子的条件删除：只有当前登记的会话
     *   确实是这个 session 时才移除，不匹配就什么都不做，误伤就不会发生。
     *
     * @return 是否真的移除了
     */
    public boolean unregister(String deviceId, WebSocketSession session) {
        if (deviceId == null || session == null) {
            return false;
        }
        if (!sessions.remove(deviceId, session)) {
            log.debug("[会话注销] 跳过：deviceId={} 当前登记的会话不是本连接", deviceId);
            return false;
        }
        lastHeartbeatAt.remove(deviceId);
        log.info("[会话注销] deviceId={}, 当前在线={}", deviceId, sessions.size());
        return true;
    }

    /**
     * 强制移除某个设备的会话（不带 session 校验）。
     * 仅用于心跳超时清理这类"确定要踢掉"的场景；连接关闭回调请用上面的重载。
     */
    public void unregister(String deviceId) {
        if (deviceId == null) {
            return;
        }
        sessions.remove(deviceId);
        lastHeartbeatAt.remove(deviceId);
        log.info("[会话注销] deviceId={}, 当前在线={}", deviceId, sessions.size());
    }

    /**
     * 心跳超时扫描：每 5 秒执行一次，关闭超过 STALE_TIMEOUT_SECONDS 未心跳的僵尸会话。
     * 用 fixedDelay 而非 fixedRate，避免上一次还没跑完就重入触发。
     */
    @Scheduled(fixedDelay = 5000)
    public void scanStaleSessions() {
        if (staleTimeoutSeconds <= 0) {
            return; // 清理已停用（配置 <= 0）
        }
        long now = System.currentTimeMillis();
        long threshold = staleTimeoutSeconds * 1000L;

        // 复制 key 集合，避免在遍历过程中并发修改 sessions
        List<String> stale = new ArrayList<>();
        for (var entry : lastHeartbeatAt.entrySet()) {
            String deviceId = entry.getKey();
            WebSocketSession session = sessions.get(deviceId);
            if (session == null) {
                stale.add(deviceId); // 会话已不在，只清时间戳
                continue;
            }
            if (now - entry.getValue() > threshold) {
                stale.add(deviceId);
            }
        }

        for (String deviceId : stale) {
            long idleSeconds = secondsSinceHeartbeat(deviceId);
            WebSocketSession session = sessions.get(deviceId);
            if (session != null) {
                try {
                    if (session.isOpen()) {
                        session.close();
                    }
                } catch (IOException e) {
                    log.warn("[心跳超时] 关闭僵尸会话异常 deviceId={}, {}", deviceId, e.getMessage());
                }
            }
            unregister(deviceId);
            log.warn("[心跳超时] 已强制清理僵尸会话 deviceId={}, 闲置约{}s",
                    deviceId, idleSeconds);
        }
    }
}
