-- 下行控制：指令日志表 command_logs
CREATE TABLE IF NOT EXISTS command_logs (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    device_id       VARCHAR(64)     NOT NULL COMMENT '目标设备ID',
    command         VARCHAR(32)     NOT NULL COMMENT '指令类型：start_pick/stop/pause/resume/go_home/emergency_stop',
    params_json     JSON            NULL     COMMENT '指令参数（透传给小车）',
    status          VARCHAR(16)     NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/ACKED/DONE/FAILED/TIMEOUT',
    sent_at         DATETIME(3)     NOT NULL COMMENT '服务端下发时间',
    ack_at          DATETIME(3)     NULL     COMMENT '设备回执时间',
    done_at         DATETIME(3)     NULL     COMMENT '设备执行完成时间',
    ack_code        INT             NULL     COMMENT '回执码：0=已接收，非0=拒绝',
    ack_message     VARCHAR(256)    NULL     COMMENT '回执/失败描述',
    timeout_seconds INT             NOT NULL DEFAULT 30 COMMENT '等待ACK超时秒数',
    operator_id     BIGINT          NULL     COMMENT '操作者用户ID',
    created_at      TIMESTAMP(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    INDEX idx_command_logs_device_status (device_id, status),
    INDEX idx_command_logs_sent (sent_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
