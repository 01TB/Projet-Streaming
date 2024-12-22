package com.videostreaming.server;

import com.videostreaming.conf.ConfigLoader;
import com.videostreaming.model.VideoMetadata;

import java.nio.file.Paths;
import java.util.*;
import java.util.stream.*;

public class ControlVideoServer {
    static ConfigLoader config = new ConfigLoader(Paths.get("conf/conf.properties"));
    private List<VideoStorageServer> childServers;

    public ControlVideoServer() {
        childServers = new ArrayList<>();
        // Initialiser les serveurs filles sur différents répertoires
        childServers.add(new VideoStorage(Paths.get(config.getProperty("storage1"))));
        childServers.add(new VideoStorage(Paths.get(config.getProperty("storage2"))));
    }

    public List<VideoMetadata> getAllAvailableVideos() {
        return childServers.stream()
                .flatMap(server -> server.getAvailableVideos().stream())
                .collect(Collectors.toList());
    }
}