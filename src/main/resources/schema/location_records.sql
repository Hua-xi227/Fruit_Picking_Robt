CREATE TABLE IF NOT EXISTS `location_records` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    `lat` DOUBLE NOT NULL COMMENT '纬度',
    `lng` DOUBLE NOT NULL COMMENT '经度',
    `bearing` DOUBLE DEFAULT NULL COMMENT '方向角（度）',
    `device_id` VARCHAR(64) DEFAULT NULL COMMENT '设备ID',
    `raw_data` TEXT COMMENT '原始JSON数据',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX `idx_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='轨迹记录表';