package org.example.fruitpickingrobt.websocket;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * 前端订阅注册表：记录「哪些浏览器在看哪台车」。
 *   本表按 deviceId 分组管理前端连接，推送线程只给"订阅了该设备"的浏览器发。
 * CopyOnWriteArraySet 的选择理由：
 *   订阅/退订是低频操作（开页面、关页面），推送是高频遍历（20Hz）。
 *   写时复制正好适合"读多写少"，遍历时无需加锁，也不会抛并发修改异常。
 */
@Component
@Slf4j
public class FrontendSessionRegistry {
    /** deviceId -> 订阅该设备的前端会话集合 */
    private final ConcurrentHashMap<String, CopyOnWriteArraySet<WebSocketSession>> byDevice
            = new ConcurrentHashMap<>();

    /** 订阅：一个浏览器可以同时看多台车 */
    public void subscribe(String deviceId, WebSocketSession session) {
        byDevice.computeIfAbsent(deviceId, k -> new CopyOnWriteArraySet<>()).add(session);
        log.info("[前端订阅] deviceId={}, 订阅数={}", deviceId, byDevice.get(deviceId).size());
    }

    /** 退订：页面关闭或切换设备时调用 */
    public void unsubscribe(String deviceId, WebSocketSession session) {
        CopyOnWriteArraySet<WebSocketSession> set = byDevice.get(deviceId);
        if (set != null) {
            set.remove(session);
            if (set.isEmpty()) {
                byDevice.remove(deviceId);
            }
        }
    }

    /** 会话彻底关闭时，从所有设备的订阅列表里清除 */
    public void removeSession(WebSocketSession session) {
        for (String deviceId : byDevice.keySet()) {
            unsubscribe(deviceId, session);
        }
    }

    public Set<String> subscribedDeviceIds() {
        return byDevice.keySet();
    }

    /**
     * 向订阅了某台车的所有前端广播一帧数据。
     * 这里做两件防御：
     *   1) isOpen() 判断 —— 浏览器可能刚关掉，连接还没来得及触发关闭回调
     *   2) 捕获 IOException 并清理 —— 网络异常的连接必须踢掉，否则会一直重试拖慢推送
     */
    public void broadcast(String deviceId, String json) {
        CopyOnWriteArraySet<WebSocketSession> set = byDevice.get(deviceId);
        if (set == null || set.isEmpty()) {
            return;
        }
        TextMessage message = new TextMessage(json);
        for (WebSocketSession session : set) {
            if (!session.isOpen()) {
                set.remove(session);
                continue;
            }
            try {
                session.sendMessage(message);
            } catch (Exception e) {
                log.warn("[前端推送] 发送失败，移除会话: {}", e.getMessage());
                set.remove(session);
            }
        }
    }

    /**
     * 二进制视频帧广播（低延迟路径）。
     * 直接转发 JPEG 字节给订阅前端，前端用 createImageBitmap 渲染到 canvas，
     * 不走 base64/JSON/MJPEG，延迟最低。
     */
    public void broadcastBinary(String deviceId, byte[] data) {
        CopyOnWriteArraySet<WebSocketSession> set = byDevice.get(deviceId);
        if (set == null || set.isEmpty()) {
            // 无订阅者是常态（模拟器推流时可能还没有前端连接），用 debug 避免刷屏
            log.debug("[前端推送-二进制] 无订阅者 deviceId={}", deviceId);
            return;
        }
        // 高频路径（25fps）：降到 debug，避免日志 IO 拖慢推送
        log.debug("[前端推送-二进制] deviceId={}, 订阅者={}, 帧大小={}", deviceId, set.size(), data.length);
        BinaryMessage message = new BinaryMessage(data);
        for (WebSocketSession session : set) {
            if (!session.isOpen()) {
                set.remove(session);
                continue;
            }
            try {
                session.sendMessage(message);
            } catch (Exception e) {
                log.error("[前端推送-二进制] 发送失败 session={}, err={}", session.getId(), e.getMessage());
                set.remove(session);
            }
        }
    }
}
