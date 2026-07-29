package org.example.fruitpickingrobt.config;

import org.example.fruitpickingrobt.service.control.CommandAckHandler;
import org.example.fruitpickingrobt.websocket.DeviceSessionRegistry;
import org.example.fruitpickingrobt.websocket.LocationWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final LocationWebSocketHandler locationHandler;
    private final DeviceSessionRegistry deviceSessionRegistry;
    private final CommandAckHandler commandAckHandler;

    public WebSocketConfig(LocationWebSocketHandler locationHandler,
                           DeviceSessionRegistry deviceSessionRegistry,
                           CommandAckHandler commandAckHandler) {
        this.locationHandler = locationHandler;
        this.deviceSessionRegistry = deviceSessionRegistry;
        this.commandAckHandler = commandAckHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // 任务2: 注册端点，监听 /ws/location
        // 任务6: setAllowedOrigins("*") 允许跨域
        registry.addHandler(locationHandler, "/ws/location")
                .setAllowedOrigins("*");
    }
}
