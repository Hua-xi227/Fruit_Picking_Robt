package org.example.fruitpickingrobt.service.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.fruitpickingrobt.dao.chassis.ChassisCommandDAO;
import org.example.fruitpickingrobt.store.ChassisStateCache;
import org.example.fruitpickingrobt.websocket.DeviceSessionRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 验证急停与运动指令之间的并发竞态已被关闭：
 *   急停激活 → 非零运动被拦截（目标强制归零，不下发小车）
 *   急停激活 → (0,0) 放行（允许显式停车）
 *   clear_error 释放 → 运动恢复正常下发
 */
class ChassisCommandServiceTest {

    private DeviceSessionRegistry registry;
    private ChassisCommandDAO dao;
    private ChassisStateCache cache;
    private EmergencyStopGuard guard;
    private WebSocketSession session;
    private ChassisCommandService service;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        registry = mock(DeviceSessionRegistry.class);
        dao = mock(ChassisCommandDAO.class);
        cache = mock(ChassisStateCache.class);
        guard = new EmergencyStopGuard();
        session = mock(WebSocketSession.class);
        service = new ChassisCommandService(registry, dao, cache, objectMapper, guard);

        when(registry.getSession("car-001")).thenReturn(session);
        when(session.isOpen()).thenReturn(true);
    }

    @Test
    void motionBlockedWhileEStopActiveAndTargetForcedToZero() throws Exception {
        guard.trigger("car-001");

        service.forwardMotion("car-001", 0.5, 0.0, "WS");

        // 目标速度被强制归零
        verify(cache).saveTarget("car-001", 0.0, 0.0);
        // 运动指令没有下发到小车：根本没去取会话
        verify(registry, never()).getSession(any());
        verify(session, never()).sendMessage(any());
    }

    @Test
    void zeroMotionAllowedWhileEStopActive() throws Exception {
        guard.trigger("car-001");

        service.forwardMotion("car-001", 0.0, 0.0, "WS");

        verify(session).sendMessage(any(TextMessage.class));
    }

    @Test
    void motionRestoredAfterClearError() throws Exception {
        guard.trigger("car-001");
        guard.release("car-001"); // 模拟 clear_error

        service.forwardMotion("car-001", 0.5, 0.0, "WS");

        verify(cache).saveTarget("car-001", 0.5, 0.0);
        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(captor.capture());

        JsonNode payload = objectMapper.readTree(captor.getValue().getPayload());
        assertThat(payload.get("type").asText()).isEqualTo("motion");
        assertThat(payload.get("linear").asDouble()).isEqualTo(0.5);
        assertThat(payload.get("angular").asDouble()).isEqualTo(0.0);
    }

    @Test
    void linearSpeedIsClampedToChassisLimit() throws Exception {
        guard.release("car-001"); // 确保未激活

        service.forwardMotion("car-001", 99.0, 0.0, "WS");

        // 现状：服务端只钳制 linear（SCOUT 2.0 最高 1.67 m/s），
        // angular 未在服务端钳制（依赖前端与底盘自身限幅）——预先存在的行为。
        verify(cache).saveTarget(eq("car-001"), eq(1.67), eq(0.0));
    }

    @Test
    void offlineDeviceDoesNotSendAndDoesNotThrow() throws Exception {
        when(registry.getSession("car-offline")).thenReturn(null);

        // 不应抛异常，只是静默跳过
        service.forwardMotion("car-offline", 0.3, 0.0, "WS");
        verify(session, never()).sendMessage(any());
    }
}
