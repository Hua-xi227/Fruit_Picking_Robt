package org.example.fruitpickingrobt.entity.command;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 指令日志实体，对应 MySQL 表 command_logs。
 *
 * 状态机（下行控制）：
 *   PENDING（已下发，待确认）
 *     -> ACKED（设备已回执确认收到）
 *     -> DONE（设备执行完成）
 *   或 PENDING -> FAILED（设备拒绝 / 下发失败）
 *   或 PENDING -> TIMEOUT（超过 timeout_seconds 仍未收到 ACK）
 */
@Data
public class CommandLog {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_ACKED   = "ACKED";
    public static final String STATUS_DONE    = "DONE";
    public static final String STATUS_FAILED  = "FAILED";
    public static final String STATUS_TIMEOUT = "TIMEOUT";

    private Long id;
    private String deviceId;
    private String command;
    private String paramsJson;
    private String status;
    private LocalDateTime sentAt;
    private LocalDateTime ackAt;
    private LocalDateTime doneAt;
    private Integer ackCode;
    private String ackMessage;
    private Integer timeoutSeconds;
    private Long operatorId;
}
