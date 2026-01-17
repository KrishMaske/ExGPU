package com.exgpu.exgpu;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Scheduling is enabled for {@link com.exgpu.exgpu.scheduler.AccessLeaseScheduler}, which
 * opens and closes compute access as rental windows arrive and pass. Its work is expressed
 * as idempotent conditional updates, so a missed or repeated tick is harmless.
 */
@SpringBootApplication
@EnableScheduling
public class ExgpuApplication {

	public static void main(String[] args) {
		SpringApplication.run(ExgpuApplication.class, args);
	}

}
