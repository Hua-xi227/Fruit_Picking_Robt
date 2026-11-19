package org.example.fruitpickingrobt.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.example.fruitpickingrobt.service.control.CommandAckHandler;
import org.example.fruitpickingrobt.service.telemetry.DeviceTelemetryService;
import org.example.fruitpickingrobt.websocket.DeviceSessionRegistry;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/** 接收设备上报，处理后端 A 的三类数据，并保留原有 WebSocket 广播。 */
@Component
public class LocationWebSocketHandler extends TextWebSocketHandler {
    private static final Logger log = LoggerFactory.getLogger(LocationWebSocketHandler.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final CopyOnWriteArraySet<WebSocketSession> sessions = new CopyOnWriteArraySet<>();
    private final ConcurrentHashMap<String, String> sessionSubscriptions = new ConcurrentHashMap<>(); // 保存网页会话订阅的设备，避免多设备数据串流。
    /** sessionId -> deviceId，用于连接关闭时清理注册表（下行控制用） */
    private final ConcurrentHashMap<String, String> sessionDeviceIds = new ConcurrentHashMap<>();
    private final DeviceTelemetryService telemetryService;
    private final ObjectMapper objectMapper;
    private final DeviceSessionRegistry deviceSessionRegistry;
    private final CommandAckHandler commandAckHandler;

    public LocationWebSocketHandler(DeviceTelemetryService telemetryService,
                                    ObjectMapper objectMapper,
                                    DeviceSessionRegistry deviceSessionRegistry,
                                    CommandAckHandler commandAckHandler) {
        this.telemetryService = telemetryService;
        this.objectMapper = objectMapper;
        this.deviceSessionRegistry = deviceSessionRegistry;
        this.commandAckHandler = commandAckHandler;
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
        ObjectNode parsedMessage = null; // 保存解析后的对象，供后续设备范围过滤使用。
        try {
            JsonNode node = objectMapper.readTree(payload);
            if (!node.isObject()) {
                throw new IllegalArgumentException("消息必须是 JSON 对象");
            }
            parsedMessage = (ObjectNode) node; // 缓存对象消息，避免广播阶段重复解析文本。
            String type = node.path("type").asText("");
            if ("subscribe".equalsIgnoreCase(type)) { // 兼容网页端独立订阅通道的握手消息。
                String subscribedDevice = deviceId((ObjectNode) node); // 校验并读取网页要订阅的设备。
                sessionSubscriptions.put(sender.getId(), subscribedDevice); // 记录当前网页连接的设备订阅。
                ObjectNode reply = objectMapper.createObjectNode(); // 创建订阅确认消息。
                reply.put("type", "subscribed"); // 标记订阅确认类型。
                reply.put("deviceId", subscribedDevice); // 回传实际订阅设备。
                sender.sendMessage(new TextMessage(reply.toString())); // 只向当前网页连接发送确认。
                return; // 订阅控制消息不参与设备数据广播和落库。
            } // 结束网页订阅握手分支。
            // —— 下行控制：登记设备会话，指令需要精准下发到具体设备 ——
            String deviceId = null;
            if (!sessionSubscriptions.containsKey(sender.getId())) { // 网页订阅会话不能覆盖真实机器人会话注册。
                try { // 设备连接消息才进入下行控制注册表。
                    deviceId = deviceId((ObjectNode) node); // 读取并校验机器人设备 ID。
                    deviceSessionRegistry.register(deviceId, sender); // 记录机器人当前 WebSocket 会话。
                    sessionDeviceIds.put(sender.getId(), deviceId); // 保存连接关闭时的清理关系。
                } catch (IllegalArgumentException ignored) { // 纯 ACK 或结果消息没有合法设备 ID 时不登记。
                    // 没有合法 deviceId 的消息不进入设备会话注册表。
                } // 结束机器人会话注册异常处理。
            } // 结束网页订阅会话保护分支。
            // —— 心跳保活：任何带 deviceId 的合法消息都刷新存活时间 ——
            if ("heartbeat".equalsIgnoreCase(type) && deviceId != null) {
                deviceSessionRegistry.recordHeartbeat(deviceId);
                log.debug("[心跳] deviceId={} 已刷新", deviceId);
            }
            // —— 下行控制：把指令 ACK / 执行结果路由给 ACK 处理器 ——
            if ("ack".equalsIgnoreCase(type)) {
                commandAckHandler.onAck(node);
            } else if ("result".equalsIgnoreCase(type)) {
                commandAckHandler.onResult(node);
            }
            dispatch((ObjectNode) node);
        } catch (IllegalArgumentException e) {
            log.warn("设备上报参数无效: {}", e.getMessage());
        } catch (Exception e) {
            log.error("设备上报解析或存储失败: {}", e.getMessage(), e);
        }

        String broadcastDeviceId = null; // 提取当前广播消息的设备范围。
        if (parsedMessage != null) { // 只有合法对象消息才进入设备范围过滤。
            try { // 无设备字段的广播消息仍保持原有全局广播行为。
                broadcastDeviceId = deviceId(parsedMessage); // 读取当前消息所属设备。
            } catch (IllegalArgumentException ignored) { // 纯协议消息没有设备 ID 时不做设备过滤。
                // 保持广播给所有未订阅设备的会话。
            } // 结束设备 ID 提取异常处理。
        } // 结束合法对象消息的设备范围过滤。
        for (WebSocketSession session : sessions) {
            String subscription = sessionSubscriptions.get(session.getId()); // 读取网页会话的订阅设备。
            if (subscription != null && broadcastDeviceId != null && !subscription.equals(broadcastDeviceId)) { // 过滤其它设备的遥测消息。
                continue; // 当前消息不属于该网页订阅范围。
            } // 结束网页设备订阅过滤分支。
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
        if (!message.hasNonNull("status") && !message.hasNonNull("phase") // 允许任务状态和导航阶段作为状态消息。
                && !message.hasNonNull("taskStatus") && !message.hasNonNull("controlMode") // 允许遥测和底盘状态统一落库。
                && !message.has("cameraConnected") && !message.has("armConnected") // 接收设备连接状态遥测。
                && !message.has("chassisConnected") && !message.has("lastMessage")) { // 接收日志和设备链路状态遥测。
            throw new IllegalArgumentException("状态消息缺少 status、phase、taskStatus 或 controlMode 字段"); // 给出明确的协议错误。
        } // 结束状态消息字段校验。
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
            if (message.has("phase") || message.has("taskStatus") || message.has("controlMode")) { // 识别没有显式 type 的遥测对象。
                return "status"; // 将遥测对象按状态记录保存。
            } // 结束无 type 遥测识别分支。
            return "";
        }

        String normalized = type.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        if (normalized.equals("location") || normalized.equals("position") || normalized.equals("gps")) {
            return "location";
        }
        if (normalized.equals("status") || normalized.equals("devicestatus")) {
            return "status";
        }
        if (normalized.equals("telemetry") && message.has("armParams")) { // 机械臂遥测沿用原有机械臂参数存储类型。
            return "armParams"; // 让最新机械臂状态可从后端查询。
        } // 结束机械臂遥测识别分支。
        if (normalized.equals("telemetry") || normalized.equals("chassisstate")) { // 兼容机器人端和网页端使用的实时状态类型。
            return "status"; // 状态类遥测进入统一状态存储。
        } // 结束通用状态遥测识别分支。
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
        sessionSubscriptions.remove(session.getId()); // 清理网页订阅，避免连接关闭后残留设备过滤条件。
        // —— 下行控制：清理设备会话登记 ——
        String deviceId = sessionDeviceIds.remove(session.getId());
        if (deviceId != null) {
            deviceSessionRegistry.unregister(deviceId);
        }
        log.info("客户端断开: id={}, 当前在线={}", session.getId(), sessions.size());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        sessions.remove(session);
        sessionSubscriptions.remove(session.getId()); // 传输异常时同步清理网页订阅状态。
        log.error("WebSocket 传输错误: id={}", session.getId(), exception);
    }
}
