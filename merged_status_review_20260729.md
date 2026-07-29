# 合并后状态审查 — 2026-07-29

## 结论
- **我之前的下行控制引擎代码全部完好**：编译通过，测试通过，无需改动。
- **你列的 6 大功能里有 4 个完整、2 个有缺口**，需要补。

## 我的代码是否需要改
不需要。`WebSocketConfig` / `LocationWebSocketHandler` / `DeviceSessionRegistry` / `CommandService` / `CommandAckHandler` / `CommandController` / `CommandLogDAO` 等都被合并保留，且与新加入的视频流代码无冲突。`mvnw compile` + `mvnw test` 全绿。

## 6 项需求 vs 实际

| 需求 | 状态 | 落点 | 缺口 |
|---|---|---|---|
| 设备 WS 长连接 | ✅ | `LocationWebSocketHandler` 监听 `ws://localhost:8082/ws/location` | — |
| **心跳保活** | ⚠️ 部分 | 处理器**接受** `type:heartbeat` 但只做了会话登记，**服务端没有"心跳超时剔除"逻辑** | 加一个 `@Scheduled` 扫描 `DeviceSessionRegistry`，清理 N 秒没心跳的僵尸会话 |
| **断线重连** | ⚠️ 客户端侧 | 我写的 `device-simulator.mjs` 自带自动重连 | 服务端**没有离线指令队列**：设备离线时下发的指令直接 `FAILED`，重连后也不会补发。需要"挂起 + 重连补推"机制 |
| 上行 GPS / 状态 / 机械臂 | ✅ | `handleLocation` / `handleStatus` / `handleArmParams` 落 MySQL | — |
| 下行 采摘/停止/回家/急停 | ✅ | `CommandService.ALLOWED_COMMANDS = {start_pick, stop, pause, resume, go_home, emergency_stop}`，POST `/api/control` | — |
| 位置存 MySQL 画轨迹 | ✅ | `LocationController.history` 返回 GeoJSON | — |
| **最新状态存 Redis 实时展示** | ✅ | `LocationStore` 用 `ConcurrentHashMap<deviceId, Map<field, payload>>` 缓存；`DeviceTelemetryService.getLatestSnapshot` 先读 Redis、缺失回查 MySQL | — |
| REST API 给前端 | ✅ | `GET /api/devices/{id}/history`、`GET /api/devices/{id}/latest` | — |
| **视频流 MJPEG 代理** | ✅ | `VideoStreamController` (`/api/video/stream` MJPEG)、`VideoUploadController` (`POST /api/video/upload`)、`VideoFrameStore` | 小车端上传帧**无身份校验**，任何人 `curl` 都能上传 → 上传时校验"该 deviceId 已在 WS 注册表里" |
| **H5 页面或 Python 脚本模拟** | ❌ | 根目录有我写的 `device-simulator.mjs`（Node 22 脚本），但 `/simulator.html` 在 ignore-paths 里而 `src/main/resources/static/` 是空目录 | 写一份真正的 H5 页面放到 `src/main/resources/static/simulator.html`，浏览器打开就能当"假小车"用 |

## 其他小问题（不影响运行）
- `application.yml` 把 MySQL 改成了 `localhost:3306`，但 Redis 还是 `fruitpicking-redis`（Docker 服务名）。如果你本地没跑 Docker Redis，需要在 yml 里改成本机 `localhost:6379` 或启动 Redis 容器。
- `mybatis.type-aliases-package` 仍是 `com.FruitPickingRobt.entity`（旧包名），实际代码在 `org.example.fruitpickingrobt.entity`。注解式 Mapper 不依赖这个配置，无影响，启动时会有 INFO 警告。
- `src/main/resources/db/command_logs.sql` 已合并进来，内容和我之前写的字段一致（status 5 个状态、`DATETIME(3)`、InnoDB / utf8mb4、复合索引）。如果还没建表，跑一次即可。

## 建议补的功能（按优先级）

### P1：H5 模拟器页面
缺这个，所有"小车"的活测试都得依赖 Node + 自己开终端。把 H5 放到 `src/main/resources/static/simulator.html`，浏览器 `http://localhost:8082/simulator.html` 直接连 WS、收指令、模拟位置/状态上报 + ACK 回执。

### P2：离线指令队列
当前 `CommandService.sendCommand` 在设备不在线时直接 `CommandResult.fail("设备不在线")`。生产场景里更合理的是：
- 离线时 → 写库 `status=QUEUED`（CommandLog 加一个状态），接口仍返回 200 + commandId；
- `DeviceSessionRegistry.register` 时 → 扫描 `command_logs WHERE device_id=? AND status='QUEUED'`，逐条 WS 推送 + 状态切到 `PENDING` 走正常 ACK 流程。

### P3：心跳超时剔除
`DeviceSessionRegistry` 加 `lastHeartbeatAt: ConcurrentHashMap<String, Long>`；
- 收到 `type:heartbeat` 时更新；
- `@Scheduled(fixedDelay=5000)` 扫描，超 60 秒未心跳 → 主动 `session.close()` + `unregister`。

### P4：视频上传身份校验
`VideoUploadController.uploadFrame` 增加：若 `deviceId` 非空，必须 `deviceSessionRegistry.isOnline(deviceId)` 才接受帧；否则 403。

---

如果上面 P1+P2 想一起做，告诉我，我接着改。