package com.example.pong

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
class PongApplication

fun main(args: Array<String>) {
	runApplication<PongApplication>(*args)
}
