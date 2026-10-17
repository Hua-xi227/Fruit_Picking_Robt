package org.example.fruitpickingrobt.service.telemetry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.example.fruitpickingrobt.dao.LocationRecordDAO;
import org.example.fruitpickingrobt.entity.LocationRecord;
import org.example.fruitpickingrobt.store.LocationStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 负责 MySQL 持久化、Redis 最新值和查询结果组装。 */
@Service
public class DeviceTelemetryService {
    public static final String LOCATION = "LOCATION";
    public static final String STATUS = "STATUS";
    public static final String ARM_PARAMS = "ARM_PARAMS";

    private static final Logger log = LoggerFactory.getLogger(DeviceTelemetryService.class);
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    private static final Map<String, String> CACHE_FIELDS = new LinkedHashMap<>();

    static {
        CACHE_FIELDS.put(LOCATION, "location");
        CACHE_FIELDS.put(STATUS, "status");
        CACHE_FIELDS.put(ARM_PARAMS, "armParams");
    }

    private final LocationRecordDAO locationRecordDAO;
    private final LocationStore locationStore;
    private final ObjectMapper objectMapper;

    public DeviceTelemetryService(LocationRecordDAO locationRecordDAO,
                                  LocationStore locationStore,
                                  ObjectMapper objectMapper) {
        this.locationRecordDAO = locationRecordDAO;
        this.locationStore = locationStore;
        this.objectMapper = objectMapper;
    }

    /** 先存 MySQL，再更新 Redis。 */
    public LocationRecord save(String deviceId, String type, Double latitude, Double longitude,
                               String payloadJson, LocalDateTime recordedAt) {
        LocationRecord record = new LocationRecord();
        record.setDeviceId(deviceId);
        record.setRecordType(type);
        record.setLatitude(latitude);
        record.setLongitude(longitude);
        record.setPayloadJson(payloadJson);
        record.setRecordedAt(recordedAt);

        if (locationRecordDAO.insert(record) != 1) {
            throw new IllegalStateException("设备数据写入 MySQL 失败");
        }
        cacheQuietly(deviceId, CACHE_FIELDS.get(type), payloadJson);
        return record;
    }

    public List<LocationRecord> getLocationHistory(String deviceId, int limit) {
        List<LocationRecord> records = new ArrayList<>(
                locationRecordDAO.selectLocationHistory(deviceId, limit));
        Collections.reverse(records);
        return records;
    }

    public ObjectNode asGeoJson(String deviceId, List<LocationRecord> records) {
        ObjectNode result = objectMapper.createObjectNode();
        result.put("type", "FeatureCollection");
        result.put("deviceId", deviceId);
        result.put("count", records.size());
        ArrayNode features = result.putArray("features");

        for (LocationRecord record : records) {
            ObjectNode feature = features.addObject();
            feature.put("type", "Feature");
            ObjectNode geometry = feature.putObject("geometry");
            geometry.put("type", "Point");
            geometry.putArray("coordinates")
                    .add(record.getLongitude() != null ? record.getLongitude() : record.getLng())
                    .add(record.getLatitude() != null ? record.getLatitude() : record.getLat());

            ObjectNode properties = readObject(record.getPayloadJson());
            properties.remove(List.of("lat", "lng", "latitude", "longitude"));
            if (record.getId() != null) {
                properties.put("recordId", record.getId());
            }
            properties.put("recordedAt", TIME_FORMAT.format(record.getRecordedAt()));
            feature.set("properties", properties);
        }
        return result;
    }

    public ArrayNode asCoordinateArray(List<LocationRecord> records) {
        ArrayNode result = objectMapper.createArrayNode();
        for (LocationRecord record : records) {
            Double lng = record.getLongitude() != null ? record.getLongitude() : record.getLng();
            Double lat = record.getLatitude() != null ? record.getLatitude() : record.getLat();
            if (lng != null && lat != null) {
                result.addArray().add(lng).add(lat);
            }
        }
        return result;
    }

    /** 先查 Redis，缺失时查 MySQL。 */
    public Optional<ObjectNode> getLatestSnapshot(String deviceId) {
        Map<String, String> payloads;
        try {
            // getAll 可能返回不可修改 Map，包一层 HashMap 保证可写
            payloads = new java.util.HashMap<>(locationStore.getAll(deviceId));
        } catch (RuntimeException e) {
            log.warn("读取 Redis 失败，改从 MySQL 查询: deviceId={}", deviceId);
            payloads = new java.util.HashMap<>();
        }

        for (Map.Entry<String, String> entry : CACHE_FIELDS.entrySet()) {
            String type = entry.getKey();
            String field = entry.getValue();
            if (!payloads.containsKey(field)) {
                LocationRecord record = locationRecordDAO.selectLatestByType(deviceId, type);
                if (record != null) {
                    String payload = record.getPayloadJson();
                    // 旧数据只有 lat/lng 列、payload_json 为空时，从记录字段构造 JSON
                    if (payload == null || payload.isEmpty()) {
                        payload = buildPayloadFromRecord(record);
                    }
                    if (payload != null) {
                        payloads.put(field, payload);
                        cacheQuietly(deviceId, field, payload);
                    }
                }
            }
        }

        if (payloads.isEmpty()) {
            return Optional.empty();
        }
        ObjectNode result = objectMapper.createObjectNode();
        result.put("deviceId", deviceId);
        putPayload(result, "location", payloads.get("location"));
        putPayload(result, "status", payloads.get("status"));
        putPayload(result, "armParams", payloads.get("armParams"));
        return Optional.of(result);
    }

    /** 当 payload_json 为空时，从 LocationRecord 的经纬度/方向角字段构造 JSON。 */
    private String buildPayloadFromRecord(LocationRecord record) {
        try {
            Double lat = record.getLatitude() != null ? record.getLatitude() : record.getLat();
            Double lng = record.getLongitude() != null ? record.getLongitude() : record.getLng();
            if (lat == null || lng == null) {
                return null;
            }
            ObjectNode node = objectMapper.createObjectNode();
            node.put("latitude", lat);
            node.put("longitude", lng);
            node.put("lat", lat);
            node.put("lng", lng);
            if (record.getBearing() != null) {
                node.put("bearing", record.getBearing());
            }
            if (record.getRecordedAt() != null) {
                node.put("recordedAt", TIME_FORMAT.format(record.getRecordedAt()));
            }
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            log.warn("从记录字段构造 payload 失败: recordId={}", record.getId(), e);
            return null;
        }
    }

    private void cacheQuietly(String deviceId, String field, String payloadJson) {
        try {
            locationStore.put(deviceId, field, payloadJson);
        } catch (RuntimeException e) {
            log.warn("更新 Redis 失败，数据已保存在 MySQL: deviceId={}, field={}", deviceId, field);
        }
    }

    private void putPayload(ObjectNode target, String field, String payloadJson) {
        if (payloadJson != null) {
            target.set(field, readJson(payloadJson));
        }
    }

    private JsonNode readJson(String payloadJson) {
        try {
            return objectMapper.readTree(payloadJson);
        } catch (Exception e) {
            return objectMapper.createObjectNode().put("raw", payloadJson);
        }
    }

    private ObjectNode readObject(String payloadJson) {
        JsonNode node = readJson(payloadJson);
        return node.isObject()
                ? ((ObjectNode) node).deepCopy()
                : objectMapper.createObjectNode().set("payload", node);
    }
}
