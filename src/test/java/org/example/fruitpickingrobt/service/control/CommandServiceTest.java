package org.example.fruitpickingrobt.service.control;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.fruitpickingrobt.dao.command.CommandLogDAO;
import org.example.fruitpickingrobt.dto.command.CommandRequest;
import org.example.fruitpickingrobt.entity.command.CommandLog;
import org.example.fruitpickingrobt.websocket.DeviceSessionRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CommandServiceTest {
    private final CommandLogDAO dao = mock(CommandLogDAO.class);
    private final DeviceSessionRegistry registry = mock(DeviceSessionRegistry.class);
    private final CommandAckHandler ack = mock(CommandAckHandler.class);
    private final CommandService service = new CommandService(dao, registry, ack, new ObjectMapper());

    private CommandRequest request() {
        CommandRequest request = new CommandRequest();
        request.setDeviceId("CAR-01");
        request.setCommand("start_pick");
        request.setParams(Map.of("areaId", 1));
        return request;
    }

    private WebSocketSession online() {
        WebSocketSession session = mock(WebSocketSession.class);
        when(registry.isOnline("CAR-01")).thenReturn(true);
        when(registry.getSession("CAR-01")).thenReturn(session);
        when(session.isOpen()).thenReturn(true);
        when(dao.insert(any())).thenAnswer(call -> {
            ((CommandLog) call.getArgument(0)).setId(7L);
            return 1;
        });
        return session;
    }

    @Test
    void rejectsUnsupportedCommand() {
        var request = request();
        request.setCommand("unsupported");
        assertThat(service.sendCommand(request, 1L).isSuccess()).isFalse();
        verifyNoInteractions(registry, dao, ack);
    }

    @Test
    void registersAckBeforeSendingAndPreservesCommandParameters() throws Exception {
        var session = online();
        assertThat(service.sendCommand(request(), 1L).isSuccess()).isTrue();
        var order = inOrder(dao, ack, session);
        order.verify(dao).insert(any());
        order.verify(ack).register(7L, "CAR-01", 30);
        order.verify(session).sendMessage(argThat(message -> message instanceof TextMessage
                && message.getPayload().toString().contains("\"areaId\":1")
                && message.getPayload().toString().contains("\"command\":\"start_pick\"")));
    }

    @Test
    void failedSendCancelsTimeoutAndReturnsFailure() throws Exception {
        var session = online();
        doThrow(new IOException("disconnected")).when(session).sendMessage(any());
        assertThat(service.sendCommand(request(), 1L).isSuccess()).isFalse();
        verify(ack).cancel(7L);
        verify(dao).markStatus(eq(7L), eq(CommandLog.STATUS_FAILED), anyString());
    }

    @Test
    void closingSessionCancelsTimeoutAndReturnsFailure() throws Exception {
        var session = online();
        doThrow(new IllegalStateException("session closed")).when(session).sendMessage(any());
        assertThat(service.sendCommand(request(), 1L).isSuccess()).isFalse();
        verify(ack).cancel(7L);
        verify(dao).markStatus(eq(7L), eq(CommandLog.STATUS_FAILED), anyString());
    }

    @Test
    void offlineDeviceDoesNotReceiveACommand() {
        assertThat(service.sendCommand(request(), 1L).isSuccess()).isFalse();
        verifyNoInteractions(dao, ack);
    }
}
