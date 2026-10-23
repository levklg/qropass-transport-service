package com.qropass.transport;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Точка входа независимого транспортного сервиса. */
@SpringBootApplication
public class TransportApplication {
    /** Запускает транспортный сервис без подключения к платёжному контуру. */
    public static void main(String[] args) {
        SpringApplication.run(TransportApplication.class, args);
    }
}
