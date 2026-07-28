CREATE TABLE IF NOT EXISTS `location_records` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    `lat` DOUBLE COMMENT '纬度',
    `lng` DOUBLE COMMENT '经度',
    `latitude` DOUBLE COMMENT '纬度(新格式)',
    `longitude` DOUBLE COMMENT '经度(新格式)',
    `bearing` DOUBLE DEFAULT NULL COMMENT '方向角（度）',
    `device_id` VARCHAR(64) DEFAULT NULL COMMENT '设备ID',
    `raw_data` TEXT COMMENT '原始JSON数据',
    `payload_json` TEXT COMMENT '载荷JSON数据',
    `record_type` VARCHAR(32) DEFAULT NULL COMMENT '记录类型(LOCATION/STATUS/ARM_PARAMS)',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `recorded_at` DATETIME DEFAULT NULL COMMENT '业务记录时间',
    INDEX `idx_create_time` (`create_time`),
    INDEX `idx_device_id` (`device_id`),
    INDEX `idx_recorded_at` (`recorded_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='轨迹记录表';
