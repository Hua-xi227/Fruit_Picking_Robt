package org.example.fruitpickingrobt.controller;

import com.fasterxml.jackson.databind.JsonNode;
import org.example.fruitpickingrobt.entity.LocationRecord;
import org.example.fruitpickingrobt.service.telemetry.DeviceTelemetryService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/** 后端 A 的设备轨迹和最新状态查询接口。 */
@RestController
@RequestMapping("/api/devices")
public class LocationController {
    private final DeviceTelemetryService telemetryService;
    private final int defaultLimit;
    private final int maxLimit;

    public LocationController(DeviceTelemetryService telemetryService,
                              @Value("${app.history-default-limit:1000}") int defaultLimit,
                              @Value("${app.history-max-limit:5000}") int maxLimit) {
        this.telemetryService = telemetryService;
        this.defaultLimit = defaultLimit;
        this.maxLimit = maxLimit;
    }

    /** 默认返回 GeoJSON；format=coordinates 时返回 [[经度, 纬度], ...]。 */
    @GetMapping("/{deviceId}/history")
    public JsonNode history(@PathVariable String deviceId,
                            @RequestParam(required = false) Integer limit,
                            @RequestParam(defaultValue = "geojson") String format) {
        int queryLimit = limit == null ? defaultLimit : limit;
        if (queryLimit < 1 || queryLimit > maxLimit) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "limit 必须在 1 到 " + maxLimit + " 之间");
        }

        List<LocationRecord> records = telemetryService.getLocationHistory(deviceId, queryLimit);
        if ("geojson".equalsIgnoreCase(format)) {
            return telemetryService.asGeoJson(deviceId, records);
        }
        if ("coordinates".equalsIgnoreCase(format)) {
            return telemetryService.asCoordinateArray(records);
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "format 只支持 geojson 或 coordinates");
    }

    @GetMapping("/{deviceId}/latest")
    public ResponseEntity<JsonNode> latest(@PathVariable String deviceId) {
        return telemetryService.getLatestSnapshot(deviceId)
                .<ResponseEntity<JsonNode>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }
}
