package com.videostreaming.server;

public class ClientState {
    private volatile long bytesSent;          // Nombre total de bytes envoyés
    private volatile double currentTime;      // Temps actuel de la vidéo en secondes
    private final long totalFileSize;         // Taille totale de la vidéo
    private final double totalDuration;       // Durée totale de la vidéo
    private volatile boolean isPaused;        // Indicateur de pause
    private volatile boolean isPlaying;       // Indicateur de lecture en cours

    public ClientState(long totalFileSize, double totalDuration) {
        this.bytesSent = 0;
        this.currentTime = 0.0;
        this.totalFileSize = totalFileSize;
        this.totalDuration = totalDuration;
        this.isPaused = false;
        this.isPlaying = false;
    }

    public synchronized void updateBytesSent(int bytes) {
        if (!isPaused) {  // Ne mettez à jour les bytes envoyés que si la vidéo est en lecture
            this.bytesSent += bytes;
            this.currentTime = ((double) this.bytesSent / this.totalFileSize) * this.totalDuration;
        }
    }

    public synchronized void pause() {
        this.isPaused = true;
        this.isPlaying = false;  // Stoppe la lecture lorsqu'on met en pause
    }

    public synchronized void resume() {
        this.isPaused = false;
        this.isPlaying = true;   // Reprend la lecture lorsqu'on clique sur Reprendre
    }

    public synchronized void stop() {
        this.isPlaying = false;  // Arrêt complet de la vidéo
    }

    public synchronized boolean isPaused() {
        return isPaused;
    }

    public synchronized boolean isPlaying() {
        return isPlaying;
    }

    public synchronized long getBytesSent() {
        return bytesSent;
    }

    public synchronized double getCurrentTime() {
        return currentTime;
    }

    public synchronized boolean isComplete() {
        return bytesSent >= totalFileSize;
    }
}
