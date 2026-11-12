package com.example.practice.jobs;

@FunctionalInterface
public interface JobHandler {

	/** Executes one job. A thrown exception is treated by the {@link Worker} as a transient failure. */
	Result run(Object payload) throws Exception;

}
