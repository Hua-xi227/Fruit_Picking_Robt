package org.example.fruitpickingrobt.websocket;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

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
 * 注意：WebSocketSession 本身不保证线程安全，调用 sendMessage 时必须做
 *       isOpen() 判断并捕获 IOException（见 CommandService）。
 */
@Component
public class DeviceSessionRegistry {

    /** deviceId -> 该设备当前的 WebSocket 会话 */
    private final ConcurrentHashMap<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    /** 登记一个设备会话（同一设备重复连接时，新会话覆盖旧会话） */
    public void register(String deviceId, WebSocketSession session) {
        if (deviceId == null || session == null) {
            return;
        }
        sessions.put(deviceId, session);
    }

    /** 移除某个设备会话（连接关闭时调用） */
    public void unregister(String deviceId) {
        if (deviceId == null) {
            return;
        }
        sessions.remove(deviceId);
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

    /** 当前所有在线设备的 ID 集合 */
    public Set<String> onlineDeviceIds() {
        return sessions.keySet();
    }
}
