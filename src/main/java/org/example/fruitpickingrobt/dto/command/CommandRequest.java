package org.example.fruitpickingrobt.dto.command;

import lombok.Data;

import java.util.Map;

/**
 * POST /api/control 的请求体：
 * {
 *   "deviceId": "CAR-01",
 *   "command": "start_pick",
 *   "params": { "areaId": 1 },
 *   "timeoutSeconds": 30
 * }
 */
@Data
public class CommandRequest {
    /** 目标设备ID（必填） */
    private String deviceId;
    /** 指令类型（必填），如 start_pick / stop / go_home / emergency_stop */
    private String command;
    /** 指令参数（可选，透传给小车） */
    private Map<String, Object> params;
    /** 等待 ACK 的超时秒数（可选，默认 30） */
    private Integer timeoutSeconds;
}
