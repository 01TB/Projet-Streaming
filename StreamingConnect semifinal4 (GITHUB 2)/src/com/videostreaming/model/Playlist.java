package com.videostreaming.model;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

public class Playlist implements Serializable {
    private String name;
    private List<VideoMetadata> videos;

    public Playlist(String name) {
        this.name = name;
        this.videos = new ArrayList<>();
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public List<VideoMetadata> getVideos() {
        return videos;
    }

    public void addVideo(VideoMetadata video) {
        if (!videos.contains(video)) {
            videos.add(video);
        }
    }

    public void removeVideo(VideoMetadata video) {
        videos.remove(video);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("Playlist: ").append(name).append("\n");
        for (VideoMetadata video : videos) {
            sb.append("- ").append(video.getTitle()).append("\n");
        }
        return sb.toString();
    }
}
