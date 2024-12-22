package com.videostreaming.server;

import com.videostreaming.conf.ConfigLoader;
import com.videostreaming.model.VideoMetadata;
import com.videostreaming.model.Playlist;

import java.net.SocketException;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

public class VideoStreamingServer {
    static ConfigLoader config = new ConfigLoader(Paths.get("conf/conf.properties"));
    private static final int PORT = Integer.parseInt(config.getProperty("server.port"));
    private static final int CHUNK_SIZE = Integer.parseInt(config.getProperty("chuncksize")); // 64KB chunks for better streaming
    private static ConcurrentHashMap<String, ClientState> clientStates = new ConcurrentHashMap<>();
    private static ConcurrentHashMap<String, List<Playlist>> clientPlaylists = new ConcurrentHashMap<>();
    private ControlVideoServer centralServer;

    public VideoStreamingServer() {
        centralServer = new ControlVideoServer();
    }

    public void start() {
        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            System.out.println("Video Streaming Server started on port " + PORT);

            while (true) {
                Socket clientSocket = serverSocket.accept();
                System.out.println("Client connected: " + clientSocket.getInetAddress());

                // Handle client connection in a new thread
                new Thread(() -> handleClientConnection(clientSocket)).start();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void handleClientConnection(Socket clientSocket) {
        String clientAddress = clientSocket.getInetAddress().toString();
        clientPlaylists.putIfAbsent(clientAddress, new ArrayList<>());

        try (
                ObjectOutputStream out = new ObjectOutputStream(clientSocket.getOutputStream());
                ObjectInputStream in = new ObjectInputStream(clientSocket.getInputStream())
        ) {
            while (true) {
                // Envoyer la liste des vidéos disponibles immédiatement
                List<VideoMetadata> videos = centralServer.getAllAvailableVideos();
                out.writeObject(videos);
                out.flush();
                System.out.println("Liste des vidéos envoyée au client: " + videos.size() + " vidéos");

                String command = (String) in.readObject();
                if (command == null || "EXIT".equals(command)) break;

                if (command.startsWith("CREATE_PLAYLIST:")) {
                    String playlistName = command.substring(15);
                    createPlaylist(clientAddress, playlistName, out);
                }
                else if (command.startsWith("ADD_TO_PLAYLIST:")) {
                    String[] parts = command.substring(15).split(":", 2);
                    String playlistName = parts[0];
                    String videoId = parts[1];
                    addToPlaylist(clientAddress, playlistName, videoId, out);
                }
                else if (command.startsWith("VIEW_PLAYLIST:")) {
                    String playlistName = command.substring(14);
                    viewPlaylist(clientAddress, playlistName, out);
                }
                else if (command.startsWith("STREAM:")) {
                    resetClientState(clientAddress);
                    String videoId = command.substring(7);
                    streamVideoToClient(videoId, out, clientAddress, in);
                }
                else if ("PAUSE".equals(command)) {
                    clientStates.get(clientAddress).pause();
                    out.writeObject("VIDEO_PAUSED");
                    out.flush();
                }
                else if ("RESUME".equals(command)) {
                    clientStates.get(clientAddress).resume();
                    out.writeObject("VIDEO_RESUMED");
                    out.flush();
                }
                else if ("STOP".equals(command)) {
                    clientStates.get(clientAddress).stop();
                    out.writeObject("VIDEO_STOPPED");
                    out.flush();
                }
            }
        } catch (IOException | ClassNotFoundException e) {
            e.printStackTrace();
        } finally {
            try {
                clientSocket.close();
                clientPlaylists.remove(clientAddress);
                clientStates.remove(clientAddress);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private void resetClientState(String clientAddress) {
        ClientState state = clientStates.get(clientAddress);
        if (state != null) {
            state.stop();  // Arrêter la vidéo
            state = new ClientState(0, 0); // Réinitialiser l'état au lieu de le supprimer
            clientStates.put(clientAddress, state);
        }
    }


    private void streamVideoToClient(String videoId, ObjectOutputStream out, String clientAddress,ObjectInputStream in) throws IOException {
        // Récupérer les métadonnées de la vidéo demandée
        VideoMetadata video = centralServer.getAllAvailableVideos().stream()
                .filter(v -> v.getId().equals(videoId))
                .findFirst()
                .orElse(null);

        if (video == null) {
            out.writeObject("VIDEO_ERROR");
            out.flush();
            System.err.println("Vidéo non trouvée : " + videoId);
            return;
        }

        // Assurer que le client a un état pour cette vidéo
        // Arrêter tout streaming précédent et réinitialiser l'état du client

        clientStates.putIfAbsent(clientAddress, new ClientState(video.getFileSize(), video.getDuration()));
        ClientState state = clientStates.get(clientAddress);

        // Log du début du streaming
        System.out.println("Début du streaming pour " + videoId + " vers " + clientAddress);
        System.out.println("Ttle " + clientAddress);



        try (BufferedInputStream fileInputStream = new BufferedInputStream(new FileInputStream(video.getFilePath()))) {
            long fileSize = video.getFileSize();    // Taille totale du fichier vidéo
            int chunkSize = CHUNK_SIZE;             // Taille de chaque chunk
            int totalChunks = (int) Math.ceil((double) fileSize / chunkSize);  // Nombre total de chunks à envoyer
            int chunksSent = 0;

            // Envoi des métadonnées au client
            out.writeObject("VIDEO_START");
            out.writeObject(videoId);
            out.writeLong(fileSize);
            out.writeDouble(video.getDuration());
            out.flush();
            System.out.println("Métadonnées envoyées.");

            byte[] buffer = new byte[chunkSize];
            int bytesRead;

            // Boucle pour envoyer les chunks
            while (true) {
                // Lecture et envoi des chunks
                while ((bytesRead = fileInputStream.read(buffer)) != -1) {
                    if (state.isPaused()) {
                        System.out.println("Streaming en pause pour " + clientAddress);
                        break;
                    }

                    // Mise à jour de la progression
                    state.updateBytesSent(bytesRead);
                    double progress = (double) state.getBytesSent() / fileSize * video.getDuration();  // Calcul de la progression

                    // Envoi du chunk au client
                    out.writeObject("VIDEO_CHUNK");
                    out.writeInt(bytesRead);
                    out.write(buffer, 0, bytesRead);
                    out.writeDouble(progress);
                    out.flush();

                    chunksSent++;
                    System.out.println("Envoi du chunk " + chunksSent + " / " + totalChunks + ", " + bytesRead + " bytes, progression : " + progress + " s.");

                    // Délai optionnel pour ajuster le débit
                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }

                    if (state.isComplete()) {
                        System.out.println("Streaming terminé pour " + clientAddress);
                        break;
                    }
                }

                // Vérification du changement de vidéo
                while (true) {
                    if (checkForVideoChange(in)) {
                        System.out.println("Changement de vidéo détecté.");
                        out.writeObject("VIDEO_CHANGE");
                        out.flush();
                        return; // Quitte proprement la méthode
                    }

                    if ((bytesRead = fileInputStream.read(buffer)) == -1) {
                        break;
                    }
                    // Continue à envoyer les chunks...
                }


                // Si tous les chunks sont envoyés, terminer
                if (chunksSent >= totalChunks) {
                    System.out.println("Streaming terminé pour la vidéo " + videoId + ".");
                    break;
                }
            }

            // Envoi du message de fin de streaming
            out.writeObject("VIDEO_END");
            out.flush();
        } catch (IOException e) {
            System.err.println("Erreur lors du streaming de la vidéo : " + videoId + " vers " + clientAddress);
            e.printStackTrace();
            out.writeObject("VIDEO_ERROR");
            out.flush();
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    // Méthode pour vérifier si un changement de vidéo est demandé par le client
    private boolean checkForVideoChange(ObjectInputStream in) throws IOException, ClassNotFoundException {
        if (in.available() > 0) { // Vérifie si un signal du client est arrivé
            Object request = in.readObject();
            if ("CHANGE_VIDEO".equals(request)) {
                return true;
            }
        }
        return false;
    }

    private void createPlaylist(String clientAddress, String playlistName, ObjectOutputStream out) throws IOException {
        List<Playlist> playlists = clientPlaylists.get(clientAddress);
        boolean exists = playlists.stream().anyMatch(p -> p.getName().equals(playlistName));
        if (exists) {
            out.writeObject("PLAYLIST_ALREADY_EXISTS");
        } else {
            playlists.add(new Playlist(playlistName));
            out.writeObject("PLAYLIST_CREATED:" + playlistName);
        }
    }


    private void addToPlaylist(String clientAddress, String playlistName, String videoId, ObjectOutputStream out) throws IOException {
        List<Playlist> playlists = clientPlaylists.get(clientAddress);
        Playlist playlist = playlists.stream()
                .filter(p -> p.getName().equals(playlistName))
                .findFirst()
                .orElse(null);

        if (playlist != null) {
            VideoMetadata video = centralServer.getAllAvailableVideos().stream()
                    .filter(v -> v.getId().equals(videoId))
                    .findFirst()
                    .orElse(null);

            if (video != null) {
                playlist.addVideo(video);
                out.writeObject("VIDEO_ADDED_TO_PLAYLIST");
            } else {
                out.writeObject("VIDEO_NOT_FOUND");
            }
        } else {
            out.writeObject("PLAYLIST_NOT_FOUND");
        }
    }

    private void viewPlaylist(String clientAddress, String playlistName, ObjectOutputStream out) throws IOException {
        List<Playlist> playlists = clientPlaylists.get(clientAddress);
        Playlist playlist = playlists.stream()
                .filter(p -> p.getName().equals(playlistName))
                .findFirst()
                .orElse(null);

        if (playlist != null) {
            out.writeObject(playlist);
        } else {
            out.writeObject("PLAYLIST_NOT_FOUND");
        }
    }


    public static void main(String[] args) {
        new VideoStreamingServer().start();
    }
}
