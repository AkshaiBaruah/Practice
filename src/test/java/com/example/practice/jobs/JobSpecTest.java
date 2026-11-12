package com.example.practice.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

class JobSpecTest {

	@Test
	void appliesDefaults() {
		JobSpec spec = new JobSpec("send_email", "payload");

		assertThat(spec.priority()).isEqualTo(5);
		assertThat(spec.maxRetries()).isEqualTo(3);
		assertThat(spec.retryPolicy()).isSameAs(ExponentialBackoff.DEFAULT);
	}

	@Test
	void rejectsPriorityOutsideZeroToNine() {
		assertThatIllegalArgumentException().isThrownBy(() -> new JobSpec("t", null, -1));
		assertThatIllegalArgumentException().isThrownBy(() -> new JobSpec("t", null, 10));
		assertThat(new JobSpec("t", null, 0).priority()).isZero();
		assertThat(new JobSpec("t", null, 9).priority()).isEqualTo(9);
	}

	@Test
	void rejectsNegativeMaxRetriesAndBlankType() {
		assertThatIllegalArgumentException().isThrownBy(() -> new JobSpec("t", null, 5, -1, null));
		assertThatIllegalArgumentException().isThrownBy(() -> new JobSpec(" ", null));
	}

}
