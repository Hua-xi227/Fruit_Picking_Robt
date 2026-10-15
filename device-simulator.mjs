// 零依赖小车模拟器（Node >= 22，使用内置 WebSocket 全局对象）
// 用法: node device-simulator.mjs
// 作用: 连接 /ws/location -> 注册 deviceId=CAR-01 -> 收到指令自动回 ACK + RESULT
// 若提示 WebSocket is not defined，改用: node --experimental-websocket device-simulator.mjs

const WS_URL = "ws://localhost:8082/ws/location";
const DEVICE_ID = "CAR-01";

const ws = new WebSocket(WS_URL);
let heartbeatTimer = null;

ws.addEventListener("open", () => {
    console.log(`[SIM] 已连接 ${WS_URL}，发送注册消息 deviceId=${DEVICE_ID}`);
    ws.send(JSON.stringify({ type: "register", deviceId: DEVICE_ID }));

    // 服务端启用了僵尸会话清理（60 秒没任何消息会被强制断开）。
    // 这个模拟器平时不发数据，只等指令，所以必须自己定时保活。
    // 真车不用操心这个——50Hz 遥测本身就在不断刷新存活时间。
    heartbeatTimer = setInterval(() => {
        if (ws.readyState === WebSocket.OPEN) {
            ws.send(JSON.stringify({ type: "heartbeat", deviceId: DEVICE_ID }));
            console.log("[SIM] 已发送 heartbeat");
        }
    }, 30000);
});

ws.addEventListener("message", (event) => {
    let msg;
    try {
        msg = JSON.parse(event.data);
    } catch {
        return; // 非 JSON 忽略
    }

    // 服务端返回的错误回执（改造后新增，以前消息被拒是静默的）
    if (msg.type === "error") {
        console.error(`[SIM] 服务端拒绝: ${msg.message}`);
        return;
    }

    if (msg.type === "command") {
        const cid = msg.commandId;
        console.log(`[SIM] 收到指令 commandId=${cid} command=${msg.command} params=${JSON.stringify(msg.params ?? {})}`);
        // 1) 回执 ACK（code=0 表示已接收）
        ws.send(JSON.stringify({ type: "ack", commandId: cid, code: 0, message: "received" }));
        console.log(`[SIM] 已发送 ACK commandId=${cid}`);
        // 2) 模拟执行完成，0.3s 后回 RESULT
        setTimeout(() => {
            ws.send(JSON.stringify({ type: "result", commandId: cid, success: true }));
            console.log(`[SIM] 已发送 RESULT commandId=${cid} success=true`);
        }, 300);
    }
    // 其他消息（如 motion 目标速度）忽略：本模拟器只演示指令回执流程
});

ws.addEventListener("error", (e) => console.error("[SIM] WebSocket 错误:", e.message ?? e));
ws.addEventListener("close", () => {
    if (heartbeatTimer) clearInterval(heartbeatTimer);
    console.log("[SIM] 连接已关闭");
});

process.on("SIGINT", () => {
    if (heartbeatTimer) clearInterval(heartbeatTimer);
    try { ws.close(); } catch {}
    process.exit(0);
});
