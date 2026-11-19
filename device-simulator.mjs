// 零依赖小车模拟器（Node >= 22，使用内置 WebSocket 全局对象）
// 用法: node device-simulator.mjs
// 作用: 连接 /ws/location -> 注册 deviceId=CAR-01 -> 收到指令自动回 ACK + RESULT
// 若提示 WebSocket is not defined，改用: node --experimental-websocket device-simulator.mjs

const WS_URL = "ws://localhost:8082/ws/location";
const DEVICE_ID = "CAR-01";

const ws = new WebSocket(WS_URL);

ws.addEventListener("open", () => {
    console.log(`[SIM] 已连接 ${WS_URL}，发送注册消息 deviceId=${DEVICE_ID}`);
    ws.send(JSON.stringify({ type: "register", deviceId: DEVICE_ID }));
});

ws.addEventListener("message", (event) => {
    let msg;
    try {
        msg = JSON.parse(event.data);
    } catch {
        return; // 非 JSON 忽略
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
    // 其他消息（如服务器广播回显）忽略
});

ws.addEventListener("error", (e) => console.error("[SIM] WebSocket 错误:", e.message ?? e));
ws.addEventListener("close", () => console.log("[SIM] 连接已关闭"));

process.on("SIGINT", () => {
    try { ws.close(); } catch {}
    process.exit(0);
});
