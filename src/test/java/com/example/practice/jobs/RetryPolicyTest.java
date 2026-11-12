package com.example.practice.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.random.RandomGenerator;

import org.junit.jupiter.api.Test;

class RetryPolicyTest {

	/** Always picks the top of the jitter range, exposing the un-jittered ceiling. */
	private static final RandomGenerator MAX = new RandomGenerator() {
		@Override
		public long nextLong() {
			return Long.MAX_VALUE;
		}

		@Override
		public long nextLong(long bound) {
			return bound - 1;
		}
	};

	@Test
	void exponentialDoublesUpToCap() {
		RetryPolicy policy = new ExponentialBackoff(Duration.ofSeconds(1), Duration.ofSeconds(10), MAX);

		assertThat(policy.delayFor(1)).isEqualTo(Duration.ofSeconds(1));
		assertThat(policy.delayFor(2)).isEqualTo(Duration.ofSeconds(2));
		assertThat(policy.delayFor(3)).isEqualTo(Duration.ofSeconds(4));
		assertThat(policy.delayFor(4)).isEqualTo(Duration.ofSeconds(8));
		assertThat(policy.delayFor(5)).isEqualTo(Duration.ofSeconds(10));
		assertThat(policy.delayFor(1000)).isEqualTo(Duration.ofSeconds(10));
	}

	@Test
	void linearGrowsByStepUpToCap() {
		RetryPolicy policy = new LinearBackoff(Duration.ofSeconds(3), Duration.ofSeconds(10), MAX);

		assertThat(policy.delayFor(1)).isEqualTo(Duration.ofSeconds(3));
		assertThat(policy.delayFor(2)).isEqualTo(Duration.ofSeconds(6));
		assertThat(policy.delayFor(3)).isEqualTo(Duration.ofSeconds(9));
		assertThat(policy.delayFor(4)).isEqualTo(Duration.ofSeconds(10));
	}

	@Test
	void jitterStaysWithinZeroAndCeiling() {
		RandomGenerator seeded = new java.util.Random(42);
		RetryPolicy exponential = new ExponentialBackoff(Duration.ofSeconds(1), Duration.ofMinutes(5), seeded);
		RetryPolicy linear = new LinearBackoff(Duration.ofSeconds(1), Duration.ofMinutes(5), seeded);

		for (int i = 0; i < 1_000; i++) {
			assertThat(exponential.delayFor(3)).isBetween(Duration.ZERO, Duration.ofSeconds(4));
			assertThat(linear.delayFor(3)).isBetween(Duration.ZERO, Duration.ofSeconds(3));
		}
	}

}
