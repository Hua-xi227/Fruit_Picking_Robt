package org.example.fruitpickingrobt.controller;

import org.example.fruitpickingrobt.entity.Result;
import org.example.fruitpickingrobt.store.LocationStore;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class LocationController {

    private static final ObjectMapper objectMapper = new ObjectMapper();
    private final LocationStore locationStore;

    public LocationController(LocationStore locationStore) {
        this.locationStore = locationStore;
    }

    @GetMapping("/history")
    public Result<List<JsonNode>> history() {
        List<JsonNode> points = locationStore.getAll().stream()
                .map(s -> {
                    try {
                        return objectMapper.readTree(s);
                    } catch (JsonProcessingException e) {
                        return objectMapper.createObjectNode().put("raw", s);
                    }
                })
                .collect(Collectors.toList());
        return Result.success(points);
    }

    @GetMapping("/latest")
    public ResponseEntity<String> latest() {
        String latest = locationStore.getLatest();
        if (latest == null) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(latest);
    }
}