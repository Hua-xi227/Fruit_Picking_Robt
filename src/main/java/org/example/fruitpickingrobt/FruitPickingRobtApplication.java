package org.example.fruitpickingrobt;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling // 启用定时任务：指令 ACK 超时扫描
public class FruitPickingRobtApplication {

    public static void main(String[] args) {
        SpringApplication.run(FruitPickingRobtApplication.class, args);
    }

}
