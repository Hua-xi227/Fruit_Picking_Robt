package org.example.fruitpickingrobt.dto.chassis;

import lombok.Data;
/**
 * 前端下发的运动控制报文（走 WebSocket，延迟最低）。
 *
 * 关键设计：这里传的是「目标速度」不是「一次性命令」。
 *   前端按住"前进"期间，每 50ms 发一次 {"type":"motion","linear":0.5,"angular":0}
 *   松手时发一次 {"type":"motion","linear":0,"angular":0}
 *
 * 为什么必须持续发：底盘有安全机制——500ms 内收不到新指令就自动把速度归零。
 * 车载程序还有一层保险（CMD_TIMEOUT_SEC=1s 未收到指令也自动归零），
 * 双保险防止"网络断了小车还全速前进"这种最危险的情况。
 */

@Data
public class MotionCommandDTO {
    private String type;        // 固定 "motion"
    private String deviceId;
    private Double linear;  //目标线速度 m/s，正=前进 负=后退，建议不超过 1.67（底盘最高速度）
    private Double angular; //目标角速度 rad/s，正=逆时针 负=顺时针
}
