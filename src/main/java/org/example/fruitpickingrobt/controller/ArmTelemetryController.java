package org.example.fruitpickingrobt.controller;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
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

    /**
     * 上传一帧：POST /api/arm-telemetry
     *
     * 请求体：
     * {
     *   "ts": "2026-11-20 10:00:00.123",     // 可选，缺省用服务器时间
     *   "joint": [j1,j2,j3,j4,j5,j6],        // 度(deg)
     *   "pose": {"x":..,"y":..,"z":..,        // 毫米(mm)
     *            "rx":..,"ry":..,"rz":..}     // 度(deg)，入库时转弧度
     * }
     */
    @PostMapping
    public Map<String, Object> upload(@RequestBody Map<String, Object> body) {
        try {
            Object jointObj = body.get("joint");
            if (!(jointObj instanceof List) || ((List<?>) jointObj).size() < 6) {
                throw new IllegalArgumentException("joint 必须是含 6 个数的数组");
            }
            List<?> j = (List<?>) jointObj;

            Map<?, ?> pose = (body.get("pose") instanceof Map)
                    ? (Map<?, ?>) body.get("pose")
                    : java.util.Collections.emptyMap();

            LocalDateTime ts = parseTs(body.get("ts") == null ? null : body.get("ts").toString());

            jdbc.update("INSERT INTO arm_telemetry "
                            + "(ts,j1,j2,j3,j4,j5,j6,x_mm,y_mm,z_mm,rx,ry,rz) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    ts,
                    num(j.get(0)), num(j.get(1)), num(j.get(2)),
                    num(j.get(3)), num(j.get(4)), num(j.get(5)),
                    num(pose.get("x")), num(pose.get("y")), num(pose.get("z")),
                    Math.toRadians(num(pose.get("rx"))),
                    Math.toRadians(num(pose.get("ry"))),
                    Math.toRadians(num(pose.get("rz"))));

            Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("id", id);
            data.put("ts", ts.toString());
            return result(data);
        } catch (Exception e) {
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("success", 400);
            resp.put("errorMsg", e.getMessage());
            resp.put("data", null);
            return resp;
        }
    }

    private double num(Object v) {
        if (v == null) {
            return 0d;
        }
        if (v instanceof Number) {
            return ((Number) v).doubleValue();
        }
        return Double.parseDouble(v.toString());
    }

    private LocalDateTime parseTs(String s) {
        if (s == null || s.isBlank()) {
            return LocalDateTime.now();
        }
        String t = s.trim().replace('T', ' ');
        if (t.endsWith("Z")) {
            t = t.substring(0, t.length() - 1);
        }
        int plus = t.indexOf('+');
        if (plus > 0) {
            t = t.substring(0, plus);
        }
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss[.SSS][.SS][.S]");
        return LocalDateTime.parse(t, fmt);
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
