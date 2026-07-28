package org.example.fruitpickingrobt.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.example.fruitpickingrobt.service.telemetry.DeviceTelemetryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class LocationWebSocketHandlerTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private DeviceTelemetryService telemetryService;
    private LocationWebSocketHandler handler;

    @BeforeEach
    void setUp() {
        telemetryService = mock(DeviceTelemetryService.class);
        handler = new LocationWebSocketHandler(telemetryService, objectMapper);
    }

    @Test
    void handleLocationNormalizesAndSavesData() throws Exception {
        ObjectNode message = (ObjectNode) objectMapper.readTree(
                "{\"type\":\"gps\",\"device_id\":\"car-1\",\"latitude\":30.2,"
                        + "\"longitude\":120.1,\"timestamp\":\"2026-07-20T06:00:00Z\"}");
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);

        handler.handleLocation(message);

        verify(telemetryService).save(eq("car-1"), eq(DeviceTelemetryService.LOCATION),
                eq(30.2), eq(120.1), payload.capture(), any(LocalDateTime.class));
        assertThat(objectMapper.readTree(payload.getValue()).get("type").asText())
                .isEqualTo("location");
        assertThat(objectMapper.readTree(payload.getValue()).get("deviceId").asText())
                .isEqualTo("car-1");
    }

    @Test
    void handlesStatusAndArmParams() throws Exception {
        handler.handleStatus((ObjectNode) objectMapper.readTree(
                "{\"deviceId\":\"car-1\",\"status\":\"ONLINE\"}"));
        handler.handleArmParams((ObjectNode) objectMapper.readTree(
                "{\"deviceId\":\"car-1\",\"armParams\":{\"joint1\":12.5}}"));

        verify(telemetryService).save(eq("car-1"), eq(DeviceTelemetryService.STATUS),
                isNull(), isNull(), anyString(), any(LocalDateTime.class));
        verify(telemetryService).save(eq("car-1"), eq(DeviceTelemetryService.ARM_PARAMS),
                isNull(), isNull(), anyString(), any(LocalDateTime.class));
    }

    @Test
    void rejectsOutOfRangeCoordinates() throws Exception {
        ObjectNode message = (ObjectNode) objectMapper.readTree(
                "{\"deviceId\":\"car-1\",\"lat\":91,\"lng\":120}");

        assertThatThrownBy(() -> handler.handleLocation(message))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("-90");
    }

    @Test
    void ignoresControlMessages() {
        WebSocketSession sender = mock(WebSocketSession.class);
        handler.handleTextMessage(sender,
                new TextMessage("{\"type\":\"command\",\"deviceId\":\"car-1\"}"));

        verifyNoInteractions(telemetryService);
    }
}
