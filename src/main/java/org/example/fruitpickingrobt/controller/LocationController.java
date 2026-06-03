package org.example.fruitpickingrobt.controller;

import org.example.fruitpickingrobt.store.LocationStore;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class LocationController {

    private final LocationStore locationStore;

    public LocationController(LocationStore locationStore) {
        this.locationStore = locationStore;
    }

    // GET /api/history 返回所有历史点
    @GetMapping("/history")
    public List<String> history() {
        return locationStore.getAll();
    }

    // GET /api/latest 返回最新位置
    @GetMapping("/latest")
    public ResponseEntity<String> latest() {
        String latest = locationStore.getLatest();
        if (latest == null) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(latest);
    }
}
