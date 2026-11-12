package com.example.practice.jobs;

import java.time.Instant;

public record DlqEntry(Job job, String reason, Instant failedAt) {
}
