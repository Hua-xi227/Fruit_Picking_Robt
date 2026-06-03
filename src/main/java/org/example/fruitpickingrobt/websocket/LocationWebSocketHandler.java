package org.example.fruitpickingrobt.websocket;

import org.example.fruitpickingrobt.store.LocationStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.concurrent.CopyOnWriteArraySet;

@Component
public class LocationWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(LocationWebSocketHandler.class);

    // 所有已连接的会话
    private final CopyOnWriteArraySet<WebSocketSession> sessions = new CopyOnWriteArraySet<>();

    // 轨迹存储
    private final LocationStore locationStore;

    public LocationWebSocketHandler(LocationStore locationStore) {
        this.locationStore = locationStore;
    }

    // 任务5: 连接建立，打印日志
    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.add(session);
        log.info("客户端连接: id={}, remote={}, 当前在线: {}",
                session.getId(), session.getRemoteAddress(), sessions.size());
    }

    // 任务3+4: 接收模拟器发送的 JSON 位置消息 {lat, lng, bearing}，广播给所有前端
    @Override
    protected void handleTextMessage(WebSocketSession sender, TextMessage message) {
        String payload = message.getPayload();
        log.info("收到消息: id={}, payload={}", sender.getId(), payload);

        // 存入轨迹
        locationStore.add(payload);

        // 广播给所有连接的客户端
        for (WebSocketSession session : sessions) {
            if (session.isOpen()) {
                try {
                    session.sendMessage(message);
                } catch (IOException e) {
                    log.error("发送失败: id={}, error={}", session.getId(), e.getMessage());
                }
            }
        }
    }

    // 任务5: 连接断开，打印日志
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session);
        log.info("客户端断开: id={}, status={}, 当前在线: {}",
                session.getId(), status, sessions.size());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.error("传输错误: id={}, error={}", session.getId(), exception.getMessage());
        sessions.remove(session);
    }
}
