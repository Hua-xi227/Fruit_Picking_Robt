package org.example.fruitpickingrobt.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.fruitpickingrobt.dao.LocationRecordDAO;
import org.example.fruitpickingrobt.entity.LocationRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.stream.Collectors;

@Component
public class LocationStore {

    private static final Logger log = LoggerFactory.getLogger(LocationStore.class);
    private static final int MAX_SIZE = 200;
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final ConcurrentLinkedDeque<String> history = new ConcurrentLinkedDeque<>();
    private final ConcurrentHashMap<String, Map<String, String>> deviceCache = new ConcurrentHashMap<>();
    private final LocationRecordDAO locationRecordDAO;

    public LocationStore(LocationRecordDAO locationRecordDAO) {
        this.locationRecordDAO = locationRecordDAO;
    }

    @PostConstruct
    public void init() {
        log.info("从 MySQL 加载历史轨迹");
        List<LocationRecord> records = locationRecordDAO.selectLatestRecords(MAX_SIZE);
        if (records != null && !records.isEmpty()) {
            int loaded = 0;
            for (LocationRecord record : records) {
                // ★ 修复：save() 只写 payload_json，raw_data 是老字段通常为空。
                //   原来只读 raw_data，导致启动时一条都加载不进来（history 恒为空）。
                String json = record.getPayloadJson();
                if (json == null || json.isEmpty()) {
                    json = record.getRawData();
                }
                if (json != null && !json.isEmpty()) {
                    history.add(json);
                    loaded++;
                }
            }
            log.info("从 MySQL 加载了 {} 条历史轨迹（共 {} 条记录）", loaded, records.size());
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

    /** 按设备 ID 返回 {field -> payloadJson} 映射 */
    public Map<String, String> getAll(String deviceId) {
        Map<String, String> cached = deviceCache.get(deviceId);
        if (cached != null) {
            return Collections.unmodifiableMap(cached);
        }
        return new HashMap<>();
    }

    /** 把某个 field 的 payloadJson 写入该设备缓存 */
    public void put(String deviceId, String field, String payloadJson) {
        if (deviceId == null || field == null) return;
        deviceCache.computeIfAbsent(deviceId, k -> new ConcurrentHashMap<>())
                .put(field, payloadJson);
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
