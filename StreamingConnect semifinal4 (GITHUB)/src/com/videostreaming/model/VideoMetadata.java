package com.videostreaming.model;
import java.io.Serializable;
import java.nio.file.Path;
import java.util.UUID;
import java.io.BufferedReader;
import java.io.InputStreamReader;


public class VideoMetadata implements Serializable {
    private String id;
    private String title;
    private String filePath;
    private long fileSize;
    private long duration; // en secondes, ou autre unité appropriée

    public VideoMetadata() {
        this.id = UUID.randomUUID().toString();
    }

    // Constructeur avec extraction de la taille et durée
    public VideoMetadata(Path videoPath) {
        this.id = UUID.randomUUID().toString();
        this.title = videoPath.getFileName().toString();
        this.filePath = videoPath.toString();
        try {
            this.fileSize = java.nio.file.Files.size(videoPath);
            this.duration = extractVideoDuration(videoPath);
        } catch (java.io.IOException e) {
            this.fileSize = -1;
            this.duration = 0; // Durée non trouvée
        }
    }

    // Méthode pour extraire la durée de la vidéo
    private long extractVideoDuration(Path videoPath) {
        long videoDuration = 0;
        try {
            // Utilisation de FFmpeg pour extraire la durée (ou une bibliothèque équivalente)
            ProcessBuilder pb = new ProcessBuilder(
                    "ffprobe", "-v", "error", "-show_entries", "format=duration",
                    "-of", "default=noprint_wrappers=1:nokey=1", videoPath.toString()
            );
            Process process = pb.start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String durationOutput = reader.readLine();
            if (durationOutput != null) {
                videoDuration = (long) Double.parseDouble(durationOutput); // En secondes
            }
            process.waitFor();
        } catch (Exception e) {
            System.err.println("Impossible d'extraire la durée de la vidéo : " + e.getMessage());
        }
        return videoDuration;
    }

    // Getters et setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }
    public long getFileSize() { return fileSize; }
    public void setFileSize(long fileSize) { this.fileSize = fileSize; }
    public long getDuration() { return duration; }
    public void setDuration(long duration) { this.duration = duration; }

    @Override
    public String toString() {
        return title + " (Durée: " + duration + " secondes)";
    }
}
