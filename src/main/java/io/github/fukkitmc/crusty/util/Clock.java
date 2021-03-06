package io.github.fukkitmc.crusty.util;


import org.gradle.api.logging.Logger;

public class Clock implements AutoCloseable {
	private final Logger logger;
	private final long start;
	public String message;

	public Clock(String message, Logger logger) {
		this.message = message;
		this.logger = logger;
		this.start = System.currentTimeMillis();
	}

	@Override
	public void close() {
		if (this.logger != null) {
			this.logger.lifecycle(String.format(this.message, System.currentTimeMillis() - this.start));
		}
	}
}