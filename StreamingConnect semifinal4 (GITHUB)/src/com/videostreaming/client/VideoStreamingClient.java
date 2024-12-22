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

    public void start(Stage primaryStage) {
        try {
            // Establish socket connection
            socket = new Socket(SERVER_HOST, SERVER_PORT);
            out = new ObjectOutputStream(socket.getOutputStream());
            in = new ObjectInputStream(socket.getInputStream());

            // Setup UI
            videoList = new ListView<>();
            List<VideoMetadata> availableVideos = (List<VideoMetadata>) in.readObject();
            Platform.runLater(() -> videoList.getItems().addAll(availableVideos));

            videoList.setOnMouseClicked(event -> {
                VideoMetadata selectedVideo = videoList.getSelectionModel().getSelectedItem();
                if (selectedVideo != null) {
                    try {
                        System.out.println("video selectionner actuellement : "+selectedVideo.getTitle());
                        // Réinitialiser le lecteur vidéo avant de charger une nouvelle vidéo
                        Platform.runLater(this::resetMediaPlayer);

                        // Demander au serveur de commencer à streamer la vidéo sélectionnée
                        out.writeObject("STREAM:" + selectedVideo.getId());
                        out.flush();

                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            });

            // Listen for server responses in a separate thread
            new Thread(this::listenForServerResponses).start();

            // Setup the UI layout for the video list
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

            BorderPane mainLayout = new BorderPane();
            VBox leftSidebar = new VBox(10);
            leftSidebar.setPadding(new Insets(15));
            leftSidebar.getChildren().addAll(searchField, videoListContainer);
            mainLayout.setLeft(leftSidebar);

            Scene scene = new Scene(mainLayout, 1000, 700);
            scene.getStylesheets().add(getClass().getResource("/styles/dark-theme.css").toExternalForm());

            primaryStage.setTitle("Client de Streaming Vidéo");
            primaryStage.setScene(scene);
            primaryStage.setOnCloseRequest(event -> {
                try {
                    if (out != null) {
                        out.writeObject("EXIT"); // Informer le serveur
                        out.flush();
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                } finally {
                    try {
                        if (socket != null) socket.close(); // Fermer la socket
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            });

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

        // Mise à jour de la barre de progression
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
