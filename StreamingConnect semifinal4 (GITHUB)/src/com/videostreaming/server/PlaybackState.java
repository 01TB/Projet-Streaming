package com.videostreaming.server;

public class PlaybackState {
    private boolean isPlaying;
    private boolean isPaused;
    private long pausedAt;

    public PlaybackState() {
        this.isPlaying = false;
        this.isPaused = false;
        this.pausedAt = 0;
    }

    public void play() {
        this.isPlaying = true;
        this.isPaused = false;
    }

    public void pause(long currentByte) {
        this.isPaused = true;
        this.isPlaying = false;
        this.pausedAt = currentByte;
    }

    public void stop() {
        this.isPlaying = false;
        this.isPaused = false;
        this.pausedAt = 0;
    }

    public boolean isPlaying() {
        return isPlaying;
    }

    public boolean isPaused() {
        return isPaused;
    }

    public long getPausedAt() {
        return pausedAt;
    }
}
