package com.videostreaming.client;

import com.videostreaming.conf.ConfigLoader;
import com.videostreaming.model.VideoMetadata;
import com.videostreaming.server.ClientState;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.*;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.scene.media.MediaView;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.awt.*;
import java.io.*;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.io.File;

public class VideoStreamingClient extends Application {
    static ConfigLoader config = new ConfigLoader(Paths.get("conf/conf.properties"));
    static String serverAddress = config.getProperty("server.address");
    static int port = Integer.parseInt(config.getProperty("server.port"));
    private static final String SERVER_HOST = serverAddress;
    private static final int SERVER_PORT = port;

    private Socket socket;
    private ObjectOutputStream out;
    private ObjectInputStream in;
    private ListView<VideoMetadata> videoList;
    private MediaView mediaView;
    private MediaPlayer mediaPlayer;
    private Slider progressBar = new Slider();


    private Path tempVideoFile;
    private BufferedOutputStream tempFileOutputStream;
    private boolean isVideoStarted = false;
    private Stage videoStage;
    private double videoDuration;
    private ListView<String> playlistListView;

    public void start(Stage primaryStage) {
        try {
            socket = new Socket(serverAddress, port);
            out = new ObjectOutputStream(socket.getOutputStream());
            in = new ObjectInputStream(socket.getInputStream());

            videoList = new ListView<>();
            List<VideoMetadata> availableVideos = (List<VideoMetadata>) in.readObject();
            Platform.runLater(() -> videoList.getItems().addAll(availableVideos));

            videoList.setOnMouseClicked(event -> {
                VideoMetadata selectedVideo = videoList.getSelectionModel().getSelectedItem();
                if (selectedVideo != null) {
                    try {
                        System.out.println("Vidéo sélectionnée actuellement : " + selectedVideo.getTitle());
                        out.writeObject("STREAM:" + selectedVideo.getId());
                        out.flush();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            });

            new Thread(this::listenForServerResponses).start();

            VBox videoListContainer = new VBox(10);
            videoListContainer.setPadding(new Insets(15));
            videoListContainer.setStyle("-fx-background-color: #f4f4f4;");
            Label videoListTitle = new Label("Vidéos disponibles");
            videoListTitle.setStyle("-fx-font-size: 18px; -fx-font-weight: bold;");
            videoListContainer.getChildren().addAll(videoListTitle, videoList);

            TextField searchField = new TextField();
            searchField.setPromptText("Rechercher une vidéo...");
            searchField.textProperty().addListener((observable, oldValue, newValue) -> {
                videoList.getItems().filtered(
                        video -> video.getTitle().toLowerCase().contains(newValue.toLowerCase())
                );
            });

            VBox playlistControls = new VBox(10);
            playlistControls.setPadding(new Insets(15));

            TextField playlistNameField = new TextField();
            playlistNameField.setPromptText("Nom de la playlist");
            Button createPlaylistButton = new Button("Créer Playlist");
            createPlaylistButton.setOnAction(e -> createPlaylist(playlistNameField.getText()));

            TextField videoIdField = new TextField();
            videoIdField.setPromptText("ID de la vidéo");
            Button addToPlaylistButton = new Button("Ajouter à la Playlist");
            addToPlaylistButton.setOnAction(e -> addToPlaylist(playlistNameField.getText(), videoIdField.getText()));

            TextField viewPlaylistNameField = new TextField();
            viewPlaylistNameField.setPromptText("Nom de la playlist à voir");
            Button viewPlaylistButton = new Button("Voir Playlist");
            viewPlaylistButton.setOnAction(e -> viewPlaylist(viewPlaylistNameField.getText()));

            playlistListView = new ListView<>();

            playlistControls.getChildren().addAll(
                    new Label("Créer une Playlist"),
                    playlistNameField,
                    createPlaylistButton,
                    new Label("Ajouter une vidéo à la Playlist"),
                    videoIdField,
                    addToPlaylistButton,
                    new Label("Voir une Playlist"),
                    viewPlaylistNameField,
                    viewPlaylistButton,
                    playlistListView
            );

            BorderPane mainLayout = new BorderPane();
            VBox leftSidebar = new VBox(10);
            leftSidebar.setPadding(new Insets(15));
            leftSidebar.getChildren().addAll(searchField, videoListContainer);
            mainLayout.setLeft(leftSidebar);
            mainLayout.setRight(playlistControls);

            Scene scene = new Scene(mainLayout, 1000, 700);
            primaryStage.setTitle("Client de Streaming Vidéo");
            primaryStage.setScene(scene);
            primaryStage.show();

        } catch (IOException | ClassNotFoundException e) {
            e.printStackTrace();
            showErrorDialog("Erreur de connexion", "Impossible de se connecter au serveur.");
        }
    }


    private void listenForServerResponses() {
        try {
            while (true) {
                Object response = in.readObject();
                if (response == null) {
                    System.err.println("Réponse nulle reçue, arrêt de la lecture.");
                    break;
                }

                if ("VIDEO_START".equals(response)) {
                    handleVideoStart();
                } else if ("VIDEO_CHUNK".equals(response)) {
                    handleVideoChunk();
                } else if ("VIDEO_END".equals(response)) {
                    handleVideoEnd();
                } else if ("VIDEO_ERROR".equals(response)) {
                    handleVideoError();
                } else if ("VIDEO_CHANGE".equals(response)) {
                    handleVideoChange();
                } else {
                    System.err.println("Message inconnu reçu : " + response);
                    break;
                }
                if (in == null || out == null) {
                    System.err.println("Erreur de connexion: les flux sont nuls.");
                    break;
                }

            }
        } catch (IOException | ClassNotFoundException e) {
            System.err.println("Erreur lors de la lecture des réponses : ");
            e.printStackTrace();
            Platform.runLater(() -> showErrorDialog("Erreur de streaming", "Une erreur est survenue."));
        }
    }

    private void handleVideoStart() throws IOException, ClassNotFoundException {
        System.out.println("Démarrage du streaming vidéo...");
        String videoId = (String) in.readObject();
        long fileSize = in.readLong();
        videoDuration = in.readDouble();
        System.out.println("Envoi : VIDEO_START");

        out.writeObject("VIDEO_START");
        out.writeObject(videoId);
        out.writeLong(fileSize);
        out.writeDouble(videoDuration);

        System.out.println("Taille : " + fileSize + " bytes, Durée : " + videoDuration + " secondes.");

        // Réinitialiser le lecteur vidéo
        Platform.runLater(this::resetMediaPlayer);

        // Supprimer l'ancien fichier temporaire
        if (tempVideoFile != null && tempVideoFile.toFile().exists()) {
            System.out.println("Fichier Temporaire supprimé");
            tempVideoFile.toFile().delete();
        }

        // Création d'un fichier temporaire pour stocker la vidéo
        tempVideoFile = Files.createTempFile("streaming", ".mp4");
        tempFileOutputStream = new BufferedOutputStream(new FileOutputStream(tempVideoFile.toFile()));
    }

    private void handleVideoChunk() throws IOException {
        if (tempVideoFile == null) {
            System.err.println("Erreur : Aucun fichier temporaire n'est initialisé pour l'écriture.");
            return; // Ignore les chunks jusqu'à ce que VIDEO_START soit reçu
        }

        int bytesRead = in.readInt();
        byte[] buffer = new byte[bytesRead];
        in.readFully(buffer, 0, bytesRead);

        double progress = in.readDouble();
        System.out.println("Chunk reçu : " + bytesRead + " bytes, Progression : " + progress + " s.");

        // Écriture dans le fichier temporaire
        if (tempFileOutputStream != null) {
            tempFileOutputStream.write(buffer, 0, bytesRead);
            tempFileOutputStream.flush();
        }

        // Mise à jour de la barre de progression / Téléchargement de la video
        // Platform.runLater(() -> progressBar.setValue((progress / videoDuration) * 100));

        // Démarrage de la lecture dès qu'une partie est disponible
        if (tempVideoFile.toFile().length() > 1000 * 1024) { // Seuil de 1MB
            if (!isVideoStarted) {
                isVideoStarted = true;
                Platform.runLater(() -> prepareMediaPlayer(tempVideoFile));
            }
        }

        // Confirmation au serveur
        out.writeObject("CHUNK_ACK");
        out.flush();
    }

    private void handleVideoEnd() throws IOException {
        System.out.println("Streaming terminé.");
        if (tempFileOutputStream != null) {
            tempFileOutputStream.close();
            System.out.println("Fichier temporaire fermé.");
        }
    }

    private void handleVideoError() {
        System.err.println("Erreur reçue du serveur !");
    }

    private void handleVideoChange() {
        System.out.println("Changement de vidéo reçu.");

        // Réinitialiser le lecteur vidéo
        Platform.runLater(() -> {
            resetMediaPlayer(); // Réinitialiser le lecteur vidéo
            // Supprimer l'ancien fichier temporaire si nécessaire
            if (tempVideoFile != null && tempVideoFile.toFile().exists()) {
                System.out.println("Fichier temporaire supprimé");
                tempVideoFile.toFile().delete();
            }

            // Réinitialiser le fichier temporaire et le flux de sortie
            tempVideoFile = null; // Réinitialiser le fichier temporaire
            tempFileOutputStream = null; // Réinitialiser le flux de sortie
        });

        // Vous pouvez également envoyer une confirmation au serveur si nécessaire
        try {
            out.writeObject("CHANGE_VIDEO");
            out.flush();
        } catch (IOException e) {
            System.err.println("Erreur lors de l'envoi de la confirmation de changement de vidéo.");
            e.printStackTrace();
        }
    }

    private void prepareMediaPlayer(Path videoFile) {
        BorderPane rootComponent = new BorderPane();
        rootComponent.setStyle("-fx-background-color: black;");

        Button playPauseButton = new Button("||");
        playPauseButton.setStyle("-fx-font-size: 12px;");
        playPauseButton.setOnAction(event -> {
            if (mediaPlayer.getStatus() == MediaPlayer.Status.PLAYING) {
                mediaPlayer.pause();
                playPauseButton.setText("||");
            } else {
                mediaPlayer.play();
                playPauseButton.setText(">");
            }
        });

        HBox controlBar = new HBox(20);
        controlBar.setAlignment(Pos.CENTER);
        controlBar.setPadding(new Insets(10));
        controlBar.setStyle("-fx-background-color: rgba(0, 0, 0, 0.7);");
        controlBar.getChildren().addAll(playPauseButton, progressBar);

        mediaView = new MediaView();
        rootComponent.setCenter(mediaView);
        rootComponent.setBottom(controlBar);

        Scene scene = new Scene(rootComponent, 1000, 800);
        videoStage = new Stage();
        videoStage.setTitle("Lecture Vidéo");
        videoStage.setScene(scene);

        Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
        videoStage.setWidth(screenSize.getWidth());
        videoStage.setHeight(screenSize.getHeight());
        videoStage.show();

        Media media = new Media(videoFile.toUri().toString());
        mediaPlayer = new MediaPlayer(media);
        mediaView.setMediaPlayer(mediaPlayer);
        mediaPlayer.setOnError(() -> {
            System.out.println("Erreur de lecture de vidéo : " + mediaPlayer.getError().getMessage());
        });

        mediaView.fitWidthProperty().bind(videoStage.widthProperty());
        mediaView.fitHeightProperty().bind(videoStage.heightProperty().subtract(100));

        setupProgressBar();
    }
    private void setupProgressBar() {
        AtomicBoolean isDragging = new AtomicBoolean(false);

        mediaPlayer.currentTimeProperty().addListener((obs, oldTime, newTime) -> {
            if (!isDragging.get()) {
                double progress = newTime.toSeconds() / mediaPlayer.getTotalDuration().toSeconds();
                progressBar.setValue(progress * 100);
            }
        });

        progressBar.setOnMousePressed(event -> isDragging.set(true));
        progressBar.setOnMouseReleased(event -> {
            isDragging.set(false);
            double newTime = progressBar.getValue() / 100 * mediaPlayer.getTotalDuration().toSeconds();
            mediaPlayer.seek(Duration.seconds(newTime));
        });

        progressBar.setOnMouseDragged(event -> {
            double newTime = progressBar.getValue() / 100 * mediaPlayer.getTotalDuration().toSeconds();
            mediaPlayer.seek(Duration.seconds(newTime));
        });

        progressBar.setMin(0);
        progressBar.setMax(100);
        progressBar.setPrefWidth(500);
        progressBar.setMaxWidth(Double.MAX_VALUE);
        progressBar.setMajorTickUnit(10);
        progressBar.setShowTickMarks(true);
        progressBar.setShowTickLabels(false);
        progressBar.prefWidthProperty().bind(videoStage.widthProperty().subtract(50));
    }

    private void resetMediaPlayer() {
        if (mediaPlayer != null) {
            mediaPlayer.stop();
            mediaPlayer.dispose();
            System.out.println("Lecteur vidéo réinitialisé.");
        }
        isVideoStarted = false; // Réinitialiser le flag
        tempVideoFile = null; // Réinitialiser le fichier temporaire
        mediaPlayer = null; // Réinitialiser le lecteur vidéo
        mediaView = null; // Réinitialiser la vue vidéo
    }


    private void createPlaylist(String playlistName) {
        try {
            out.writeObject("CREATE_PLAYLIST:" + playlistName);
            String response = (String) in.readObject();
            showInfoDialog("Résultat", response);
        } catch (IOException | ClassNotFoundException e) {
            e.printStackTrace();
        }
    }

    private void addToPlaylist(String playlistName, String videoId) {
        try {
            out.writeObject("ADD_TO_PLAYLIST:" + playlistName + ":" + videoId);
            String response = (String) in.readObject();
            showInfoDialog("Résultat", response);
        } catch (IOException | ClassNotFoundException e) {
            e.printStackTrace();
        }
    }

    private void viewPlaylist(String playlistName) {
        try {
            out.writeObject("VIEW_PLAYLIST:" + playlistName);
            Object response = in.readObject();

            if (response instanceof String) {
                showInfoDialog("Erreur", (String) response);
            } else if (response instanceof com.videostreaming.model.Playlist) {
                com.videostreaming.model.Playlist playlist = (com.videostreaming.model.Playlist) response;
                playlistListView.getItems().clear();
                playlistListView.getItems().add(playlist.toString());
            }
        } catch (IOException | ClassNotFoundException e) {
            e.printStackTrace();
        }
    }

    private void showInfoDialog(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }

    private void showErrorDialog(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
