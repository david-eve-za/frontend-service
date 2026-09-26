package com.glez.frontendservice.dtos;

import lombok.Builder;
import lombok.Getter;

import java.time.Instant;

@Getter
@Builder
public class NovelsCrawlStatus {

    public enum State {IDLE, RUNNING, COMPLETED, FAILED, CANCELLED}

    private final State state;
    private final String startPath;
    private final String currentPath;
    private final Instant startedAt;
    private final Instant finishedAt;
    private final long elapsedSeconds;
    private final long directoriesExplored;
    private final long filesFound;
    private final long filesDownloaded;
    private final long filesSkippedExisting;
    private final long filesFailed;
    private final long bytesDownloaded;
    private final String lastError;
}
