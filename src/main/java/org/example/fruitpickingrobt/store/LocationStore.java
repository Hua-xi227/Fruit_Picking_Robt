package org.example.fruitpickingrobt.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.fruitpickingrobt.dao.LocationRecordDAO;
import org.example.fruitpickingrobt.entity.LocationRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.stream.Collectors;

@Component
public class LocationStore {

    private static final Logger log = LoggerFactory.getLogger(LocationStore.class);
    private static final int MAX_SIZE = 200;
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final ConcurrentLinkedDeque<String> history = new ConcurrentLinkedDeque<>();
    private final LocationRecordDAO locationRecordDAO;

    public LocationStore(LocationRecordDAO locationRecordDAO) {
        this.locationRecordDAO = locationRecordDAO;
    }

    @PostConstruct
    public void init() {
        log.info("从 MySQL 加载历史轨迹");
        List<LocationRecord> records = locationRecordDAO.selectLatestRecords(MAX_SIZE);
        if (records != null && !records.isEmpty()) {
            for (LocationRecord record : records) {
                String json = record.getRawData();
                if (json != null && !json.isEmpty()) {
                    history.add(json);
                }
            }
            log.info("从 MySQL 加载了 {} 条历史轨迹", records.size());
        }
    }

    public void add(String locationJson) {
        try {
            JsonNode json = objectMapper.readTree(locationJson);
            LocationRecord record = new LocationRecord();
            if (json.has("lat")) {
                record.setLat(json.get("lat").asDouble());
            }
            if (json.has("lng")) {
                record.setLng(json.get("lng").asDouble());
            }
            if (json.has("bearing")) {
                record.setBearing(json.get("bearing").asDouble());
            }
            if (json.has("deviceId")) {
                record.setDeviceId(json.get("deviceId").asText());
            }
            record.setRawData(locationJson);

            locationRecordDAO.insertRecord(record);

            history.addLast(locationJson);
            while (history.size() > MAX_SIZE) {
                history.removeFirst();
            }

            locationRecordDAO.deleteOldRecords(MAX_SIZE);

        } catch (Exception e) {
            log.error("存储位置数据失败", e);
        }
    }

    public List<String> getAll() {
        if (!history.isEmpty()) {
            return history.stream().collect(Collectors.toList());
        }

        List<LocationRecord> records = locationRecordDAO.selectLatestRecords(MAX_SIZE);
        if (records == null || records.isEmpty()) {
            return Collections.emptyList();
        }

        return records.stream()
                .map(record -> {
                    try {
                        return objectMapper.writeValueAsString(record);
                    } catch (Exception e) {
                        return record.getRawData();
                    }
                })
                .collect(Collectors.toList());
    }

    public String getLatest() {
        String latest = history.peekLast();
        if (latest != null) {
            return latest;
        }

        LocationRecord record = locationRecordDAO.selectLatestRecord();
        if (record == null) {
            return null;
        }

        try {
            return objectMapper.writeValueAsString(record);
        } catch (Exception e) {
            return record.getRawData();
        }
    }
}