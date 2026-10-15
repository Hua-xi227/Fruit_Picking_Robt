package org.example.fruitpickingrobt.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.example.fruitpickingrobt.service.control.ChassisCommandService;
import org.example.fruitpickingrobt.store.ChassisStateCache;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * 前端专用端点 /ws/frontend。
 *
 * 前端在这里做两件事：
 *   1) 订阅某台车：{"type":"subscribe","deviceId":"CAR-01"}
 *      → 之后服务端按 20Hz 主动推遥测（不用前端轮询！这就是"即时性"的来源）
 *   2) 下发运动指令：{"type":"motion","deviceId":"CAR-01","linear":0.5,"angular":0}
 *      → 走 WebSocket 而不是 HTTP，省掉 HTTP 的握手开销，延迟最低
 *
 * 为什么指令要走 WS 而不是 REST：
 *   按住前进时前端每 50ms 刷一次目标速度。如果走 HTTP，
 *   每秒 20 次完整的 HTTP 请求（含 header、鉴权），开销大且延迟高；
 *   WS 是长连接，发一条小消息即可，延迟能低一个数量级。
 *   REST 接口仍然保留（见 ChassisController），作为自动化脚本/兜底调用途径。
 */
@Component
@Slf4j
public class FrontendWebSocketHandler extends TextWebSocketHandler {

    /**
     * 该前端当前订阅的设备（一个会话可订阅多台）。
     *
     * ★ 存在 session.getAttributes() 里，不能用实例字段：
     *   Spring 的 WebSocketHandler 默认是**单例**，所有浏览器会话共用这一个实例。
     *   如果用实例字段，A 页面关闭时 clear() 会把 B 页面的订阅记录一起清掉
     *   （实际推送走的是 FrontendSessionRegistry，所以表现为"记录错乱"）。
     *   WebSocketSession 的 attributes 是每个连接独立的，放这里才对。
     */
    private static final String ATTR_SUBSCRIBED = "subscribedDevices";

    @SuppressWarnings("unchecked")
    private Set<String> subscribed(WebSocketSession session) {
        return (Set<String>) session.getAttributes()
                .computeIfAbsent(ATTR_SUBSCRIBED, k -> new CopyOnWriteArraySet<String>());
    }

    private final FrontendSessionRegistry frontendRegistry;
    private final ChassisCommandService commandService;
    private final ChassisStateCache cache;
    private final ObjectMapper objectMapper;

    public FrontendWebSocketHandler(FrontendSessionRegistry frontendRegistry,
                                    ChassisCommandService commandService,
                                    ChassisStateCache cache,
                                    ObjectMapper objectMapper) {
        this.frontendRegistry = frontendRegistry;
        this.commandService = commandService;
        this.cache = cache;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        try {
            JsonNode node = objectMapper.readTree(message.getPayload());
            String type = node.path("type").asText("");
            String deviceId = node.path("deviceId").asText("").trim();

            switch (type) {
                case "subscribe" -> {
                    if (deviceId.isEmpty()) {
                        return;
                    }
                    frontendRegistry.subscribe(deviceId, session);
                    subscribed(session).add(deviceId);

                    // 订阅成功的那一刻，立刻回推一帧当前最新状态。
                    // 作用：前端打开页面马上就有数据，不用等下一个推送周期（最多 50ms 后才有）
                    ObjectNode ack = objectMapper.createObjectNode();
                    ack.put("type", "subscribed");
                    ack.put("deviceId", deviceId);
                    cache.readLatest(deviceId).ifPresent(n -> ack.set("latest", n));
                    session.sendMessage(new TextMessage(ack.toString()));
                }
                case "unsubscribe" -> {
                    frontendRegistry.unsubscribe(deviceId, session);
                    subscribed(session).remove(deviceId);
                }
                case "motion" -> {
                    // 高频目标速度刷新（按住期间 20Hz，松手发 0）
                    double linear = node.path("linear").asDouble(0.0);
                    double angular = node.path("angular").asDouble(0.0);
                    // 安全钳制：绝不允许超过底盘最高速度 1.67 m/s
                    linear = Math.max(-1.67, Math.min(1.67, linear));
                    commandService.forwardMotion(deviceId, linear, angular, "WS");
                }
                case "action" -> {
                    // 一次性动作：enable / clear_error / emergency_stop / stop
                    String action = node.path("action").asText("");
                    commandService.sendAction(deviceId, action, null, null, "WS");
                }
                default -> log.debug("[前端] 未知消息类型: {}", type);
            }
        } catch (Exception e) {
            log.error("[前端] 处理消息失败: {}", e.getMessage());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        // 会话关闭时必须从所有订阅列表里摘掉，否则推送线程会一直往死连接发数据
        frontendRegistry.removeSession(session);
        subscribed(session).clear();
    }
}
