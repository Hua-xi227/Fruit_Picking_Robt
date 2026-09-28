package org.example.fruitpickingrobt.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.example.fruitpickingrobt.service.control.CommandAckHandler;
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
import static org.mockito.Mockito.when;

class LocationWebSocketHandlerTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private DeviceTelemetryService telemetryService;
    private DeviceSessionRegistry deviceSessionRegistry;
    private CommandAckHandler commandAckHandler;
    private LocationWebSocketHandler handler;

    @BeforeEach
    void setUp() {
        telemetryService = mock(DeviceTelemetryService.class);
        deviceSessionRegistry = mock(DeviceSessionRegistry.class);
        commandAckHandler = mock(CommandAckHandler.class);
        handler = new LocationWebSocketHandler(
                telemetryService, objectMapper, deviceSessionRegistry, commandAckHandler);
    }

    @Test
    void handleLocationNormalizesAndSavesData() throws Exception {
        ObjectNode message = (ObjectNode) objectMapper.readTree(
                "{\"type\":\"gps\",\"device_id\":\"car-1\",\"latitude\":30.2,"
                        + "\"longitude\":120.1,\"altitude\":-2.5,\"speed\":0,\"timestamp\":\"2026-07-20T06:00:00Z\"}");
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);

        handler.handleLocation(message);

        verify(telemetryService).save(eq("car-1"), eq(DeviceTelemetryService.LOCATION),
                eq(30.2), eq(120.1), payload.capture(), any(LocalDateTime.class));
        assertThat(objectMapper.readTree(payload.getValue()).get("type").asText())
                .isEqualTo("location");
        assertThat(objectMapper.readTree(payload.getValue()).get("deviceId").asText())
                .isEqualTo("car-1");
        assertThat(objectMapper.readTree(payload.getValue()).get("altitude").asDouble()).isEqualTo(-2.5);
        assertThat(objectMapper.readTree(payload.getValue()).get("speed").asDouble()).isZero();
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
        when(sender.getId()).thenReturn("test-session-id");
        handler.handleTextMessage(sender,
                new TextMessage("{\"type\":\"command\",\"deviceId\":\"car-1\"}"));

        verifyNoInteractions(telemetryService);
    }

    @Test
    void broadcastsNormalizedGpsAfterSaving() throws Exception {
        WebSocketSession sender = mock(WebSocketSession.class);
        when(sender.getId()).thenReturn("gps-session");
        when(sender.isOpen()).thenReturn(true);
        handler.afterConnectionEstablished(sender);
        handler.handleTextMessage(sender, new TextMessage("{\"type\":\"gps\",\"device_id\":\"car-1\","
                + "\"latitude\":0,\"longitude\":0,\"altitude\":12.3,\"speed\":2.0,"
                + "\"coordinateSystem\":\"wgs-84\",\"timestamp\":1000000000000}"));
        ArgumentCaptor<TextMessage> sent = ArgumentCaptor.forClass(TextMessage.class);
        verify(sender).sendMessage(sent.capture());
        ObjectNode data = (ObjectNode) objectMapper.readTree(sent.getValue().getPayload());
        assertThat(data.path("deviceId").asText()).isEqualTo("car-1");
        assertThat(data.path("coordinateSystem").asText()).isEqualTo("WGS84");
        assertThat(data.path("type").asText()).isEqualTo("location");
        assertThat(data.path("lat").asDouble()).isZero();
        assertThat(data.path("altitude").asDouble()).isEqualTo(12.3);
        assertThat(data.path("speed").asDouble()).isEqualTo(2.0);
        assertThat(data.path("timestamp").asText()).isEqualTo("2001-09-09T09:46:40");
        verify(sender).setTextMessageSizeLimit(256 * 1024);
    }

    @Test
    void closedViewerDoesNotInterruptBroadcastToOtherViewers() throws Exception {
        WebSocketSession closing = mock(WebSocketSession.class);
        WebSocketSession viewer = mock(WebSocketSession.class);
        when(closing.getId()).thenReturn("closing");
        when(viewer.getId()).thenReturn("viewer");
        when(closing.isOpen()).thenReturn(true);
        when(viewer.isOpen()).thenReturn(true);
        org.mockito.Mockito.doThrow(new IllegalStateException("session closed"))
                .when(closing).sendMessage(any());
        handler.afterConnectionEstablished(closing);
        handler.afterConnectionEstablished(viewer);
        handler.handleTextMessage(viewer, new TextMessage("{\"type\":\"video_status\",\"enabled\":false}"));
        verify(viewer).sendMessage(any(TextMessage.class));
    }

    @Test
    void invalidGpsIsNotSentToTheMap() throws Exception {
        WebSocketSession viewer = mock(WebSocketSession.class);
        when(viewer.getId()).thenReturn("viewer");
        when(viewer.isOpen()).thenReturn(true);
        handler.afterConnectionEstablished(viewer);
        handler.handleTextMessage(viewer, new TextMessage("{\"lat\":91,\"lng\":120}"));
        org.mockito.Mockito.verify(viewer, org.mockito.Mockito.never()).sendMessage(any());
        verifyNoInteractions(telemetryService);
    }
}
