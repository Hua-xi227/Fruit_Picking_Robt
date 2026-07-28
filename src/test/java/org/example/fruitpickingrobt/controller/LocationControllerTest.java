package org.example.fruitpickingrobt.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.example.fruitpickingrobt.service.telemetry.DeviceTelemetryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Collections;
import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class LocationControllerTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private DeviceTelemetryService telemetryService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        telemetryService = mock(DeviceTelemetryService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(
                new LocationController(telemetryService, 1000, 5000)).build();
    }

    @Test
    void returnsGeoJsonHistory() throws Exception {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("type", "FeatureCollection");
        response.put("deviceId", "car-1");
        response.putArray("features");
        when(telemetryService.getLocationHistory("car-1", 1000))
                .thenReturn(Collections.emptyList());
        when(telemetryService.asGeoJson("car-1", Collections.emptyList())).thenReturn(response);

        mockMvc.perform(get("/api/devices/car-1/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("FeatureCollection"))
                .andExpect(jsonPath("$.deviceId").value("car-1"));
    }

    @Test
    void returnsCoordinatesAndNoContentForUnknownDevice() throws Exception {
        ArrayNode coordinates = objectMapper.createArrayNode();
        coordinates.addArray().add(120.1).add(30.2);
        when(telemetryService.getLocationHistory("car-1", 10))
                .thenReturn(Collections.emptyList());
        when(telemetryService.asCoordinateArray(Collections.emptyList())).thenReturn(coordinates);
        when(telemetryService.getLatestSnapshot("unknown")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/devices/car-1/history")
                        .param("format", "coordinates").param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0][0]").value(120.1))
                .andExpect(jsonPath("$[0][1]").value(30.2));

        mockMvc.perform(get("/api/devices/unknown/latest"))
                .andExpect(status().isNoContent());
    }
}
