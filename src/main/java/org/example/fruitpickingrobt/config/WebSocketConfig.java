package org.example.fruitpickingrobt.config;

import org.example.fruitpickingrobt.service.control.CommandAckHandler;
import org.example.fruitpickingrobt.websocket.DeviceSessionRegistry;
import org.example.fruitpickingrobt.websocket.FrontendWebSocketHandler;
import org.example.fruitpickingrobt.websocket.LocationWebSocketHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final LocationWebSocketHandler locationHandler;
    private final FrontendWebSocketHandler frontendHandler;
    private final DeviceSessionRegistry deviceSessionRegistry;
    private final CommandAckHandler commandAckHandler;

    public WebSocketConfig(LocationWebSocketHandler locationHandler,
                           FrontendWebSocketHandler frontendHandler,
                           DeviceSessionRegistry deviceSessionRegistry,
                           CommandAckHandler commandAckHandler) {
        this.locationHandler = locationHandler;
        this.frontendHandler = frontendHandler;
        this.deviceSessionRegistry = deviceSessionRegistry;
        this.commandAckHandler = commandAckHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // 任务2: 注册端点，监听 /ws/location
        // 任务6: setAllowedOrigins("*") 允许跨域
        registry.addHandler(locationHandler, "/ws/location")
                .setAllowedOrigins("*");
        registry.addHandler(locationHandler, "/ws-location") // 兼容机器人端使用的云端入口路径。
                .setAllowedOrigins("*"); // 允许前后端分离部署时的跨域 WebSocket 握手。
        registry.addHandler(locationHandler, "/ws-frontend") // 兼容网页端实时订阅入口路径。
                .setAllowedOrigins("*"); // 允许网页从独立静态域名建立订阅连接。

        // 前端专用（订阅 20Hz 推送 + 下发运动指令）
        registry.addHandler(frontendHandler, "/ws/frontend")
                .setAllowedOrigins("*");
    }

    /**
     * WebSocket 单条消息缓冲区大小。
     * 默认只有 8KB，一帧 480×360 的 JPEG 转 base64 后约 40KB，
     * 会被服务端直接断连，导致 Dashboard 收不到任何视频帧（黑屏）。
     * 这里设为 1MB，足够 1080P 质量 0.8 的 JPEG（约 200KB base64）通过。
     */
    @Bean
    public ServletServerContainerFactoryBean createWebSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(1024 * 1024);       // 文本帧 1MB
        container.setMaxBinaryMessageBufferSize(4 * 1024 * 1024); // 二进制帧 4MB（720p WebM 关键帧）
        return container;
    }
}
