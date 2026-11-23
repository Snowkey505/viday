package com.vidayapi

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication(scanBasePackages = ["com.vidayapi"])
class VidayApplication

fun main(args: Array<String>) {
    runApplication<VidayApplication>(*args)
}
