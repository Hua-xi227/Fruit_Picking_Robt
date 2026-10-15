package org.example.fruitpickingrobt.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.example.fruitpickingrobt.dto.chassis.ChassisTelemetryDTO;
import org.example.fruitpickingrobt.service.chassis.ChassisTelemetryService;
import org.example.fruitpickingrobt.service.control.CommandAckHandler;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * 统一设备端点 /ws/location：接收设备上行数据，并负责指令下行。
 *
 * 原来这里只处理三类低频数据（location / status / armParams），
 * 现在新增底盘 50Hz 遥测（type=chassis），同时修掉三个历史问题：
 *
 * 1) 【新增】chassis 快速路径
 *    底盘帧必须在 dispatch() 的「类型猜测」之前拦截。
 *    因为 messageType() 里有兜底逻辑：没写 type 时，只要带 lat+lng 就判成 location。
 *    而底盘帧恰好带 latitude/longitude，不拦截就会被误判、被经纬度校验卡住、静默丢弃。
 *
 * 2) 【修复】删掉无条件广播
 *    旧代码在方法末尾把每条上行消息原样广播给所有连接（**含发送者自己**）。
 *    1Hz 时无所谓，50Hz 时设备每秒会收回自己 50 条包，嵌入式板子直接被打满。
 *    现在改成：只推给「订阅了该设备的前端」（走 /ws/frontend），设备之间不再互推。
 *
 * 3) 【修复】错误不再静默
 *    旧代码 catch 后只打日志，设备端完全不知道自己发的消息被拒了。
 *    现在会给设备回一条 {"type":"error","message":"..."}，调试时能立刻定位。
 */
@Component
public class LocationWebSocketHandler extends TextWebSocketHandler {
    private static final Logger log = LoggerFactory.getLogger(LocationWebSocketHandler.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** 仅用于统计在线连接数，不再用于广播 */
    private final CopyOnWriteArraySet<WebSocketSession> sessions = new CopyOnWriteArraySet<>();
    /** sessionId -> deviceId，用于连接关闭时清理注册表（下行控制用） */
    private final ConcurrentHashMap<String, String> sessionDeviceIds = new ConcurrentHashMap<>();
    private final DeviceTelemetryService telemetryService;
    private final ObjectMapper objectMapper;
    private final DeviceSessionRegistry deviceSessionRegistry;
    private final CommandAckHandler commandAckHandler;
    private final ChassisTelemetryService chassisTelemetryService;
    private final FrontendSessionRegistry frontendRegistry;

    public LocationWebSocketHandler(DeviceTelemetryService telemetryService,
                                    ObjectMapper objectMapper,
                                    DeviceSessionRegistry deviceSessionRegistry,
                                    CommandAckHandler commandAckHandler,
                                    ChassisTelemetryService chassisTelemetryService,
                                    FrontendSessionRegistry frontendRegistry) {
        this.telemetryService = telemetryService;
        this.objectMapper = objectMapper;
        this.deviceSessionRegistry = deviceSessionRegistry;
        this.commandAckHandler = commandAckHandler;
        this.chassisTelemetryService = chassisTelemetryService;
        this.frontendRegistry = frontendRegistry;
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
        String deviceId = null;
        try {
            JsonNode node = objectMapper.readTree(payload);
            if (!node.isObject()) {
                throw new IllegalArgumentException("消息必须是 JSON 对象");
            }
            String type = node.path("type").asText("");

            // ★ 底盘遥测 50Hz 快速路径：必须放在 dispatch() 之前
            if ("chassis".equalsIgnoreCase(type)) {
                handleChassis(sender, (ObjectNode) node);
                return;
            }

            // —— 下行控制：登记设备会话，指令需要精准下发到具体设备 ——
            try {
                deviceId = deviceId((ObjectNode) node);
                deviceSessionRegistry.register(deviceId, sender);
                sessionDeviceIds.put(sender.getId(), deviceId);
            } catch (IllegalArgumentException ignored) {
                // 没有合法 deviceId 的消息（如纯 ack / result）不登记
            }

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

            // 低频数据（位置/状态/机械臂/视频帧）落库成功后，转发给订阅了该设备的前端。
            // 替代原来的无条件广播：设备之间不再互推，设备也不会收到自己发的包。
            if (deviceId != null) {
                frontendRegistry.broadcast(deviceId, payload);
            }
        } catch (IllegalArgumentException e) {
            log.warn("设备上报参数无效: {}", e.getMessage());
            sendError(sender, e.getMessage());
        } catch (Exception e) {
            log.error("设备上报解析或存储失败: {}", e.getMessage(), e);
            sendError(sender, "消息处理失败: " + e.getMessage());
        }
    }

    /**
     * 底盘遥测帧（50Hz）—— 这是最高频的路径，必须快。
     * 只做三件事：登记会话、刷新心跳、交给 ChassisTelemetryService。
     * 它内部会更新内存快照 + Redis 最新态 + 采样入队，真正的写库由定时线程批量完成。
     */
    private void handleChassis(WebSocketSession sender, ObjectNode node) throws Exception {
        ChassisTelemetryDTO dto = objectMapper.treeToValue(node, ChassisTelemetryDTO.class);
        String deviceId = dto.getDeviceId();
        if (deviceId == null || deviceId.isBlank()) {
            throw new IllegalArgumentException("底盘帧缺少 deviceId");
        }
        // 首次收到数据也顺便登记会话（有的小车可能不发 register 就直接推数据）
        if (!sessionDeviceIds.containsKey(sender.getId())) {
            deviceSessionRegistry.register(deviceId, sender);
            sessionDeviceIds.put(sender.getId(), deviceId);
        }
        // 50Hz 遥测本身就证明设备活着，不用再单独发心跳
        deviceSessionRegistry.recordHeartbeat(deviceId);
        chassisTelemetryService.onTelemetry(dto);
    }

    /** 把错误回给设备端，避免"发了数据却查不到、只能翻服务端日志"的尴尬 */
    private void sendError(WebSocketSession session, String msg) {
        if (session == null || !session.isOpen()) {
            return;
        }
        try {
            ObjectNode n = objectMapper.createObjectNode();
            n.put("type", "error");
            n.put("message", msg);
            n.put("ts", System.currentTimeMillis());
            session.sendMessage(new TextMessage(n.toString()));
        } catch (IOException ignored) {
            // 连接已断，无需处理
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
                // 其他消息只落库外转发，不额外处理。
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
        String deviceId = sessionDeviceIds.remove(session.getId());
        if (deviceId != null) {
            // ★ 必须带 session 做条件移除：只有登记的会话确实是当前这个时才注销，
            //   否则真车和模拟器共用 deviceId 时，一端断线会把另一端一起注销掉。
            deviceSessionRegistry.unregister(deviceId, session);
            // 清理内存快照，避免推送线程继续推已离线设备的陈旧数据
            chassisTelemetryService.evict(deviceId);
        }
        log.info("客户端断开: id={}, 当前在线={}", session.getId(), sessions.size());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        sessions.remove(session);
        log.error("WebSocket 传输错误: id={}", session.getId(), exception);
    }
}
