# 下行控制引擎 — Apifox 功能测试指南

> 范围：你负责的 4 块 —— ① 设备会话 ConcurrentHashMap 维护 ② POST /api/control ③ 落库 + WS 转发 ④ 超时与 ACK
> 不依赖 H5 模拟器：用 Apifox 自带的 WebSocket 调试器即可模拟小车。
> 服务地址：`http://localhost:8082` / `ws://localhost:8082/ws/location`

---

## 0. 前置准备

1. 启动后端（任一种）：
   - IDE 跑 `FruitPickingRobtApplication`
   - 或 `.\mvnw.cmd spring-boot:run`
2. 准备一个已注册账号，拿 token：
   - **请求**：`POST {{base_url}}/api/v1/users/login`
   - **Body**：
     ```json
     { "account": "你的账号", "password": "你的密码" }
     ```
   - **响应**里取 `data.token`，后面所有 HTTP 接口都要带 `Authorization: <token>`
3. Apifox 里设两个环境变量（环境管理 → 新建环境）：
   - `base_url` = `http://localhost:8082`
   - `token` = 上面拿到的 token

---

## 功能 ①：设备会话维护（ConcurrentHashMap<String, Session>）

**目标**：确认设备连 WS 后，服务端把它登记进 `DeviceSessionRegistry`，`/api/control` 能找到它。

**Apifox 操作（WebSocket 调试器）**：
1. 新建「WebSocket」类型请求，URL = `{{base_url}}/ws/location`
2. 点「连接」
3. 连接成功后发送一条带 `deviceId` 的消息（任选其一）：
   ```json
   { "type": "register", "deviceId": "CAR-01" }
   ```
   或
   ```json
   { "type": "heartbeat", "deviceId": "CAR-01" }
   ```
   或任意上行数据（位置/状态），只要带 `deviceId` 字段都会触发登记。
4. **判定成功**：
   - 服务端控制台打印 `[会话注册] deviceId=CAR-01, 当前在线=1`
   - 后续 功能② 里 `/api/control` 返回 `success=true` 而不是「设备不在线」

**反向验证（设备离线）**：
- 在 WS 调试器里点「断开」
- 再发 `/api/control` → 应返回 `success=false, message="设备不在线"`

---

## 功能 ② + ③：POST /api/control 下发 + 落库 + WS 转发

**步骤 A — 下发指令（HTTP）**

- **请求**：`POST {{base_url}}/api/control`
- **Headers**：`Authorization: {{token}}`，`Content-Type: application/json`
- **Body**：
  ```json
  {
    "deviceId": "CAR-01",
    "command": "start_pick",
    "params": { "areaId": 1 },
    "timeoutSeconds": 30
  }
  ```
  > `command` 合法值：`start_pick` / `stop` / `pause` / `resume` / `go_home` / `emergency_stop`
  > `params`、`timeoutSeconds` 都可省略（timeout 默认 30s）

- **期望响应**（HTTP 200）：
  ```json
  {
    "success": 200,
    "data": {
      "success": true,
      "commandId": 1,
      "deviceId": "CAR-01",
      "command": "start_pick",
      "status": "PENDING",
      "message": "指令已下发，等待设备确认"
    }
  }
  ```
  ⚠️ 记下 `data.commandId`（下面查状态用）。

**步骤 B — 验证已落库（command_logs）**

- **请求**：`GET {{base_url}}/api/control/{commandId}`（把上一步的 commandId 填进去）
- **期望响应** `data` 里：
  - `status` = `PENDING`
  - `deviceId` = `CAR-01`，`command` = `start_pick`
  - `sentAt` 有值，`ackAt` / `doneAt` 为 null
- 或直接查库：
  ```sql
  SELECT id, device_id, command, status, sent_at, ack_at, done_at
  FROM command_logs WHERE id = {commandId};
  ```

**步骤 C — 验证已 WS 转发给小车**

- 回到 功能① 里的 WS 调试器，下发指令后应**立即收到一条推送**：
  ```json
  {
    "type": "command",
    "commandId": 1,
    "command": "start_pick",
    "params": { "areaId": 1 },
    "timeoutSeconds": 30
  }
  ```
  > 这条就是服务端从 `DeviceSessionRegistry.getSession(deviceId).sendMessage(...)` 转发出去的。

---

## 功能 ④：ACK 回执 + 执行结果 + 超时

### 4.1 ACK 确认（PENDING → ACKED）

在 WS 调试器里发送：
```json
{ "type": "ack", "commandId": 1, "code": 0, "message": "received" }
```
- **查状态** `GET /api/control/1` → `status: ACKED`，`ackAt` 有值，`ackCode: 0`
- 服务端日志：`[ACK] commandId=1 code=0 ...`

> 若 `code != 0`（如 -1），状态会直接变 `FAILED`，`ackMessage` 记录拒绝原因。

### 4.2 执行结果（ACKED → DONE / FAILED）

在 WS 调试器里发送：
```json
{ "type": "result", "commandId": 1, "status": "success", "message": "采摘完成" }
```
- 查 `GET /api/control/1` → `status: DONE`，`doneAt` 有值
- 失败场景把 `status` 改成 `"failed"` 即可，状态变 `FAILED`

### 4.3 超时（PENDING → TIMEOUT）

**复现步骤**：
1. WS 连上并注册 `CAR-01`
2. `POST /api/control` 下发，但把 `timeoutSeconds` 设小，例如 `5`
3. **不要发任何 ACK**
4. 等 5 秒 + 0.5 秒扫描周期（共约 5.5s）
5. 查 `GET /api/control/{id}` → `status: TIMEOUT`，`ackMessage: "等待设备 ACK 超时"`

> 超时由 `CommandAckHandler` 的 `@Scheduled(fixedDelay=500)` 扫描 `pendingCommands` 缓存驱动，
> 与 heartbeat 扫描是两个独立定时任务。

### 4.4 心跳保活（本次补全）

**正常保活**：WS 连上后，每 < 60 秒发一次：
```json
{ "type": "heartbeat", "deviceId": "CAR-01" }
```
期间随时 `POST /api/control` 都应成功。

**僵尸清理（反向验证）**：
1. WS 连上注册 `CAR-01`，之后**完全静默**（不发任何消息）
2. 等 > 60 秒（`DeviceSessionRegistry.STALE_TIMEOUT_SECONDS`）
3. 服务端日志：`[心跳超时] 已强制清理僵尸会话 deviceId=CAR-01, 闲置约6Xs`
4. 此时 `POST /api/control` → 返回「设备不在线」
> 想快速验证可把 `STALE_TIMEOUT_SECONDS` 临时改小（如 10），重跑即可。

---

## 常见失败排查

| 现象 | 原因 | 解决 |
|---|---|---|
| `/api/control` 返回「设备不在线」 | WS 没连 / deviceId 不一致 | 确认 WS 已连且 deviceId 拼写一致；看控制台 `[会话注册]` 日志 |
| 下发后 WS 没收到 `type:command` 消息 | 设备未注册或连接已断 | 先发一次 `register`/`heartbeat` 再下发 |
| `command` 返回「不支持的指令类型」 | 不在 ALLOWED_COMMANDS | 用 `start_pick/stop/pause/resume/go_home/emergency_stop` 之一 |
| 401 Unauthorized | 没带/带错 token | Header `Authorization: <token>`，注意前面有无 `Bearer `（本项目直接原值） |
| 超时一直不触发 | timeoutSeconds 太大 | 测试时设小（5~10）；确认 `CommandAckHandler` 被 Spring 托管（@Component + @EnableScheduling 已开） |
| 心跳不清理僵尸 | 等待不足 60s | 等够 60s，或临时调小 `STALE_TIMEOUT_SECONDS` |
