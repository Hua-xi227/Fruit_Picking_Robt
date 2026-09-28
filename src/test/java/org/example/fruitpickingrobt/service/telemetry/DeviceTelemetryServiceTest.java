package org.example.fruitpickingrobt.service.telemetry;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.example.fruitpickingrobt.dao.LocationRecordDAO;
import org.example.fruitpickingrobt.entity.LocationRecord;
import org.example.fruitpickingrobt.store.LocationStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeviceTelemetryServiceTest {
    private LocationRecordDAO locationRecordDAO;
    private LocationStore locationStore;
    private DeviceTelemetryService service;

    @BeforeEach
    void setUp() {
        locationRecordDAO = mock(LocationRecordDAO.class);
        locationStore = mock(LocationStore.class);
        service = new DeviceTelemetryService(locationRecordDAO, locationStore, new ObjectMapper());
    }

    @Test
    void keepsMysqlRecordWhenRedisFails() {
        when(locationRecordDAO.insert(any(LocationRecord.class))).thenReturn(1);
        doThrow(new RuntimeException("redis unavailable"))
                .when(locationStore).put("car-1", "status", "{\"status\":\"ONLINE\"}");

        LocationRecord saved = service.save("car-1", DeviceTelemetryService.STATUS,
                null, null, "{\"status\":\"ONLINE\"}",
                LocalDateTime.of(2026, 7, 20, 14, 0));

        assertThat(saved.getDeviceId()).isEqualTo("car-1");
        assertThat(saved.getRecordType()).isEqualTo(DeviceTelemetryService.STATUS);
        verify(locationRecordDAO).insert(saved);
    }

    @Test
    void latestFallsBackToMysqlAndBackfillsRedis() {
        when(locationStore.getAll("car-1")).thenReturn(new HashMap<>());
        LocationRecord location = record(DeviceTelemetryService.LOCATION,
                "{\"type\":\"location\",\"lat\":30.2,\"lng\":120.1}");
        when(locationRecordDAO.selectLatestByType("car-1", DeviceTelemetryService.LOCATION))
                .thenReturn(location);

        Optional<ObjectNode> snapshot = service.getLatestSnapshot("car-1");

        assertThat(snapshot).isPresent();
        assertThat(snapshot.get().get("location").get("lng").asDouble()).isEqualTo(120.1);
        verify(locationStore).put("car-1", "location", location.getPayloadJson());
    }

    @Test
    void geoJsonUsesLongitudeLatitudeOrder() {
        LocationRecord record = record(DeviceTelemetryService.LOCATION,
                "{\"type\":\"location\",\"deviceId\":\"car-1\",\"lat\":30.2,\"lng\":120.1}");
        record.setId(9L);
        record.setLatitude(30.2);
        record.setLongitude(120.1);

        ObjectNode geoJson = service.asGeoJson("car-1", Collections.singletonList(record));

        assertThat(geoJson.at("/features/0/geometry/coordinates/0").asDouble()).isEqualTo(120.1);
        assertThat(geoJson.at("/features/0/geometry/coordinates/1").asDouble()).isEqualTo(30.2);
        assertThat(geoJson.at("/features/0/properties/recordId").asLong()).isEqualTo(9L);
    }

    private LocationRecord record(String type, String payload) {
        LocationRecord record = new LocationRecord();
        record.setDeviceId("car-1");
        record.setRecordType(type);
        record.setPayloadJson(payload);
        record.setRecordedAt(LocalDateTime.of(2026, 7, 20, 14, 0));
        return record;
    }
}
