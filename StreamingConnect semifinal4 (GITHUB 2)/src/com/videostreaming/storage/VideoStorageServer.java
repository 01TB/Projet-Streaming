package com.videostreaming.storage;

import com.videostreaming.model.VideoMetadata;
import java.io.OutputStream;
import java.util.List;

public interface VideoStorageServer {
    List<VideoMetadata> getAvailableVideos();
    void streamVideo(String videoId, OutputStream clientOutputStream);
}