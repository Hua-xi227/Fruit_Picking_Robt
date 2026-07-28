package org.example.fruitpickingrobt.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class LocationRecord {
    Long id;
    Double lat;
    Double lng;
    Double latitude;
    Double longitude;
    Double bearing;
    String deviceId;
    String rawData;
    String payloadJson;
    String recordType;
    LocalDateTime createTime;
    LocalDateTime recordedAt;
}
