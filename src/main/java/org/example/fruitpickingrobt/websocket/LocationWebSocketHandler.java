package org.example.fruitpickingrobt.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.example.fruitpickingrobt.service.telemetry.DeviceTelemetryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArraySet;

/** 接收设备上报，处理后端 A 的三类数据，并保留原有 WebSocket 广播。 */
@Component
public class LocationWebSocketHandler extends TextWebSocketHandler {
    private static final Logger log = LoggerFactory.getLogger(LocationWebSocketHandler.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final CopyOnWriteArraySet<WebSocketSession> sessions = new CopyOnWriteArraySet<>();
    private final DeviceTelemetryService telemetryService;
    private final ObjectMapper objectMapper;

    public LocationWebSocketHandler(DeviceTelemetryService telemetryService, ObjectMapper objectMapper) {
        this.telemetryService = telemetryService;
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.add(session);
        log.info("客户端连接: id={}, remote={}, 当前在线={}",
                session.getId(), session.getRemoteAddress(), sessions.size());
    }

    @Override
    protected void handleTextMessage(WebSocketSession sender, TextMessage message) {
        String payload = message.getPayload();
        try {
            JsonNode node = objectMapper.readTree(payload);
            if (!node.isObject()) {
                throw new IllegalArgumentException("消息必须是 JSON 对象");
            }
            dispatch((ObjectNode) node);
        } catch (IllegalArgumentException e) {
            log.warn("设备上报参数无效: {}", e.getMessage());
        } catch (Exception e) {
            log.error("设备上报解析或存储失败: {}", e.getMessage(), e);
        }

        for (WebSocketSession session : sessions) {
            if (session.isOpen()) {
                try {
                    session.sendMessage(message);
                } catch (IOException e) {
                    log.error("WebSocket 发送失败: id={}", session.getId(), e);
                }
            }
        }
    }

    private void dispatch(ObjectNode message) {
        switch (messageType(message)) {
            case "location":
                handleLocation(message);
                break;
            case "status":
                handleStatus(message);
                break;
            case "armParams":
                handleArmParams(message);
                break;
            default:
                // 其他消息只广播。
        }
    }

    public void handleLocation(ObjectNode message) {
        double latitude = requiredNumber(message, "lat", "latitude");
        double longitude = requiredNumber(message, "lng", "longitude", "lon");
        if (latitude < -90 || latitude > 90) {
            throw new IllegalArgumentException("纬度必须在 -90 到 90 之间");
        }
        if (longitude < -180 || longitude > 180) {
            throw new IllegalArgumentException("经度必须在 -180 到 180 之间");
        }
        save(message, DeviceTelemetryService.LOCATION, "location", latitude, longitude);
    }

    public void handleStatus(ObjectNode message) {
        if (!message.hasNonNull("status")) {
            throw new IllegalArgumentException("状态消息缺少 status 字段");
        }
        save(message, DeviceTelemetryService.STATUS, "status", null, null);
    }

    public void handleArmParams(ObjectNode message) {
        JsonNode params = first(message, "armParams", "params", "parameters");
        if (params == null || params.isNull() || (params.isContainerNode() && params.size() == 0)) {
            throw new IllegalArgumentException("机械臂消息缺少参数");
        }
        save(message, DeviceTelemetryService.ARM_PARAMS, "armParams", null, null);
    }

    private void save(ObjectNode message, String recordType, String messageType,
                      Double latitude, Double longitude) {
        String deviceId = deviceId(message);
        LocalDateTime recordedAt = recordedAt(message);
        ObjectNode payload = message.deepCopy();
        payload.put("type", messageType);
        payload.put("deviceId", deviceId);
        payload.remove("device_id");
        payload.put("timestamp", recordedAt.toString());
        if (latitude != null) {
            payload.put("lat", latitude);
            payload.put("lng", longitude);
        }

        try {
            telemetryService.save(deviceId, recordType, latitude, longitude,
                    objectMapper.writeValueAsString(payload), recordedAt);
        } catch (IOException e) {
            throw new IllegalStateException("设备消息转为 JSON 失败", e);
        }
    }

    private String messageType(ObjectNode message) {
        String type = message.path("type").asText("");
        if (type.isBlank()) {
            if (first(message, "lat", "latitude") != null
                    && first(message, "lng", "longitude", "lon") != null) {
                return "location";
            }
            if (message.has("armParams")) {
                return "armParams";
            }
            if (message.has("status")) {
                return "status";
            }
            return "";
        }

        String normalized = type.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        if (normalized.equals("location") || normalized.equals("position") || normalized.equals("gps")) {
            return "location";
        }
        if (normalized.equals("status") || normalized.equals("devicestatus")) {
            return "status";
        }
        if (normalized.equals("armparams") || normalized.equals("armparameters")) {
            return "armParams";
        }
        return "";
    }

    private String deviceId(ObjectNode message) {
        JsonNode node = first(message, "deviceId", "device_id");
        String deviceId = node == null ? "default" : node.asText().trim();
        if (deviceId.isEmpty()) {
            deviceId = "default";
        }
        if (deviceId.length() > 64 || !deviceId.matches("[A-Za-z0-9._:-]+")) {
            throw new IllegalArgumentException("deviceId 格式无效");
        }
        return deviceId;
    }

    private double requiredNumber(ObjectNode message, String... fields) {
        JsonNode node = first(message, fields);
        if (node == null || (!node.isNumber() && !node.isTextual())) {
            throw new IllegalArgumentException(String.join("/", fields) + " 必须是数字");
        }
        try {
            double value = node.isNumber() ? node.doubleValue() : Double.parseDouble(node.asText());
            if (!Double.isFinite(value)) {
                throw new NumberFormatException();
            }
            return value;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(String.join("/", fields) + " 必须是数字");
        }
    }

    private LocalDateTime recordedAt(ObjectNode message) {
        JsonNode node = first(message, "timestamp", "recordedAt", "ts");
        if (node == null || node.isNull() || node.asText().isBlank()) {
            return LocalDateTime.now(ZONE);
        }
        if (node.isNumber()) {
            long value = node.longValue();
            long millis = Math.abs(value) < 100_000_000_000L ? value * 1000 : value;
            return Instant.ofEpochMilli(millis).atZone(ZONE).toLocalDateTime();
        }
        String value = node.asText().trim();
        try {
            return OffsetDateTime.parse(value).atZoneSameInstant(ZONE).toLocalDateTime();
        } catch (DateTimeParseException ignored) {
            try {
                return LocalDateTime.parse(value);
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException("timestamp 必须是 ISO-8601 时间");
            }
        }
    }

    private JsonNode first(JsonNode message, String... fields) {
        for (String field : fields) {
            JsonNode value = message.get(field);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session);
        log.info("客户端断开: id={}, 当前在线={}", session.getId(), sessions.size());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        sessions.remove(session);
        log.error("WebSocket 传输错误: id={}", session.getId(), exception);
    }
}
