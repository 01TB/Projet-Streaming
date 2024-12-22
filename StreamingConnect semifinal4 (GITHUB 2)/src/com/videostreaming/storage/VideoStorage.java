package com.videostreaming.storage;

import com.videostreaming.model.VideoMetadata;
import com.videostreaming.storage.VideoStorageServer;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

public class VideoStorage implements VideoStorageServer {
    private List<VideoMetadata> videos;
    private Path storageDirectory;

    public VideoStorage(Path storageDirectory) {
        this.storageDirectory = storageDirectory;
        this.videos = scanVideosInDirectory();
    }

    private List<VideoMetadata> scanVideosInDirectory() {
        List<VideoMetadata> foundVideos = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(storageDirectory)) {
            foundVideos = paths
                    .filter(path -> path.toString().toLowerCase().endsWith(".mp4"))
                    .map(VideoMetadata::new)
                    .collect(Collectors.toList());
        } catch (IOException e) {
            e.printStackTrace();
        }
        return foundVideos;
    }

    @Override
    public List<VideoMetadata> getAvailableVideos() {
        return videos;
    }

    @Override
    public void streamVideo(String videoId, OutputStream clientOutputStream) {
        VideoMetadata video = videos.stream()
                .filter(v -> v.getId().equals(videoId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Vidéo non trouvée"));

        try (BufferedInputStream inputStream = new BufferedInputStream(new FileInputStream(video.getFilePath()))) {
            byte[] buffer = new byte[4096];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                clientOutputStream.write(buffer, 0, bytesRead);
                clientOutputStream.flush();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}