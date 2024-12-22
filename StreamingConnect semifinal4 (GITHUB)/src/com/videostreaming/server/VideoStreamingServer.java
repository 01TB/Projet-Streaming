package com.videostreaming.server;

import com.videostreaming.conf.ConfigLoader;
import com.videostreaming.model.VideoMetadata;

import java.net.SocketException;
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

        try (
                ObjectOutputStream out = new ObjectOutputStream(clientSocket.getOutputStream());
                ObjectInputStream in = new ObjectInputStream(clientSocket.getInputStream())
        ) {
            // Envoi des vidéos disponibles
            List<VideoMetadata> availableVideos = centralServer.getAllAvailableVideos();
            out.writeObject(availableVideos);
            out.flush();

            // Attente des commandes du client
            while (true) {
                String command = null;

                try {
                    command = (String) in.readObject();  // Lecture des commandes
                } catch (SocketException e) {
                    // Si une exception SocketException est levée, cela signifie que la connexion a été fermée
                    System.out.println("Client " + clientAddress + " a fermé la connexion.");
                    break;  // Quitter la boucle et fermer la connexion
                } catch (IOException e) {
                    // Gestion générale des autres erreurs IO
                    System.out.println("Erreur de communication avec le client : " + clientAddress);
                    break;
                } catch (ClassNotFoundException e) {
                    e.printStackTrace();
                    break;
                }

                if (command == null || "EXIT".equals(command)) {
                    break;  // Si le client envoie EXIT, on arrête la connexion
                }

                // Gestion des commandes
                if (command.startsWith("STREAM:")) {
                    // Réinitialiser l'état du client pour une nouvelle vidéo
                    resetClientState(clientAddress);
                    String videoId = command.substring(7);
                    streamVideoToClient(videoId, out, clientAddress,in);  // Streaming de la vidéo
                }

                if ("PAUSE".equals(command)) {
                    clientStates.get(clientAddress).pause();
                    out.writeObject("VIDEO_PAUSED");
                    out.flush();
                }
                if ("RESUME".equals(command)) {
                    clientStates.get(clientAddress).resume();
                    out.writeObject("VIDEO_RESUMED");
                    out.flush();
                }
                if ("STOP".equals(command)) {
                    clientStates.get(clientAddress).stop();
                    out.writeObject("VIDEO_STOPPED");
                    out.flush();
                }
                if ("CHANGE_VIDEO".equals(command)) {
                    System.out.println("Changement de vidéo demandé par " + clientAddress);

                    // Réinitialiser l'état du client et commencer le streaming de la nouvelle vidéo
                    resetClientState(clientAddress);

                    // Si le client veut changer de vidéo, vous devez peut-être lui envoyer une nouvelle vidéo à streamer
                    String newVideoId = "nouvelle_video_id"; // Définir l'ID de la nouvelle vidéo à streamer
                    streamVideoToClient(newVideoId, out, clientAddress,in);  // Commencer à streamer la nouvelle vidéo
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            // Fermeture propre de la connexion et nettoyage
            try {
                clientSocket.close();
                clientStates.remove(clientAddress);  // Supprimer l'état du client
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private void resetClientState(String clientAddress) {
        // Réinitialiser l'état du client lorsqu'il change de vidéo
        ClientState state = clientStates.get(clientAddress);
        if (state != null) {
            state.stop();  // Arrêter toute vidéo précédente en cours
            clientStates.remove(clientAddress);  // Supprimer l'ancien état
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


    public static void main(String[] args) {
        new VideoStreamingServer().start();
    }
}
