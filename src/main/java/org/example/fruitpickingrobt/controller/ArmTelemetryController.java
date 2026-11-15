package org.example.fruitpickingrobt.controller;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 机械臂遥测接口
 *
 * 数据来源：MySQL 表 arm_telemetry（6 个关节角 + TCP 位姿）
 * 前端面板：机械臂动态信息 / TCP 位姿（每 1 秒拉取一次）
 *
 * 说明：跨域由全局 WebMvcConfig#corsFilter 统一处理，此处无需 @CrossOrigin
 *      （前端通过 nginx 同源访问 /fruitpicking/api/...，本身不跨域）
 *
 * 单位说明：
 *   joint   关节角，度(deg)          —— 数据库原样
 *   pose.x/y/z  TCP 位置，毫米(mm)   —— 数据库原样
 *   pose.rx/ry/rz TCP 姿态，度(deg)  —— 数据库存的是弧度，这里转换成度
 */
@RestController
@RequestMapping("/api/arm-telemetry")
public class ArmTelemetryController {

    @Resource
    private JdbcTemplate jdbc;

    /** 最新一帧：GET /api/arm-telemetry/latest */
    @GetMapping("/latest")
    public Map<String, Object> latest() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM arm_telemetry ORDER BY id DESC LIMIT 1");
        return result(rows.isEmpty() ? null : toDto(rows.get(0)));
    }

    /**
     * 列表/增量：GET /api/arm-telemetry?afterId=0&limit=200
     *   afterId > 0 时只返回 id 大于该值的新数据（前端增量拉取）
     */
    @GetMapping
    public Map<String, Object> list(@RequestParam(value = "afterId", defaultValue = "0") long afterId,
                                    @RequestParam(value = "limit", defaultValue = "200") int limit) {
        int n = Math.min(Math.max(limit, 1), 1000);
        List<Map<String, Object>> rows = afterId > 0
                ? jdbc.queryForList("SELECT * FROM arm_telemetry WHERE id > ? ORDER BY id LIMIT ?", afterId, n)
                : jdbc.queryForList("SELECT * FROM arm_telemetry ORDER BY id DESC LIMIT ?", n);
        List<Map<String, Object>> data = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            data.add(toDto(r));
        }
        return result(data);
    }

    private Map<String, Object> result(Object data) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", 200);
        resp.put("errorMsg", null);
        resp.put("data", data);
        return resp;
    }

    private Map<String, Object> toDto(Map<String, Object> r) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("id", r.get("id"));
        Object ts = r.get("ts");
        d.put("ts", ts == null ? null : ts.toString());
        d.put("joint", Arrays.asList(
                r.get("j1"), r.get("j2"), r.get("j3"),
                r.get("j4"), r.get("j5"), r.get("j6")));
        Map<String, Object> pose = new LinkedHashMap<>();
        pose.put("x", r.get("x_mm"));
        pose.put("y", r.get("y_mm"));
        pose.put("z", r.get("z_mm"));
        // 数据库存弧度，前端面板按“度”显示
        pose.put("rx", toDeg(r.get("rx")));
        pose.put("ry", toDeg(r.get("ry")));
        pose.put("rz", toDeg(r.get("rz")));
        d.put("pose", pose);
        return d;
    }

    private Double toDeg(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number) {
            return Math.toDegrees(((Number) v).doubleValue());
        }
        try {
            return Math.toDegrees(Double.parseDouble(v.toString()));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
