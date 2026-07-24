package org.example.fruitpickingrobt.entity;

import lombok.Data;

import java.time.LocalDateTime;

/** MySQL 中的一条设备上报记录。 */
@Data
public class LocationRecord {
    private Long id;
    private String deviceId;
    private String recordType;
    private Double latitude;
    private Double longitude;
    private String payloadJson;
    private LocalDateTime recordedAt;
    private LocalDateTime createdAt;
}
