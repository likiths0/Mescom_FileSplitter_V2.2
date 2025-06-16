package ftpfilesdownloadapp;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;

import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;

public class FTPfilesDownloadApp extends Application {

    private TextArea logArea;
    private ComboBox<String> daySelector;
    private Label statusLabel;
    private Button encryptedDownloadBtn, zReportDownloadBtn, cancelDownloadBtn;
    private volatile boolean cancelDownload = false;
    private FTPClient currentFTPClient; // Track active FTP connection

    private final String FTP_SERVER = "ftp.cedlabs.in";
    private final int FTP_PORT = 21;
    private final String FTP_USER = "MESCOM@cedlabs.in";
    private final String FTP_PASS = "Cedlabs@123";
    private final String FTP_REMOTE_DIR = "/MescomOutput/";
    private final String LOCAL_DOWNLOAD_DIR = "D:/DownloadedFiles";
    private Task<Void> currentTask;

    @Override
    public void start(Stage primaryStage) {
        encryptedDownloadBtn = new Button("Download Enc Files");
        zReportDownloadBtn    = new Button("Download Z Report");
        cancelDownloadBtn     = new Button("Cancel Download");
        cancelDownloadBtn.setDisable(true);
        
        logArea = new TextArea();
        logArea.setPrefSize(400, 200);
        logArea.setEditable(false);

        statusLabel = new Label("");
        statusLabel.setPrefWidth(350);
        statusLabel.setAlignment(Pos.CENTER);

        daySelector = new ComboBox<>();
        for (int i = 1; i <= 27; i++) {
            daySelector.getItems().add(String.format("2L%02d", i));
        }
        daySelector.setPromptText("Select Day");

        HBox topBar = new HBox(daySelector);
        topBar.setPadding(new Insets(20, 10, 0, 10));
        topBar.setAlignment(Pos.TOP_RIGHT);

        VBox centerBox = new VBox(10,
                encryptedDownloadBtn,
                zReportDownloadBtn,
                logArea,
                statusLabel,
                cancelDownloadBtn);
        centerBox.setPadding(new Insets(20, 20, 20, 20));
        centerBox.setAlignment(Pos.CENTER);

        Scene scene = new Scene(new BorderPane(centerBox, topBar, null, null, null), 800, 450);
        primaryStage.setTitle("FTP Files Download");
        primaryStage.setScene(scene);
        primaryStage.setResizable(false);
        primaryStage.centerOnScreen();
        primaryStage.show();

        encryptedDownloadBtn.setOnAction(e -> downloadFilesWithPrefix("E_"));
        zReportDownloadBtn.setOnAction(e -> downloadFilesWithPrefix("Z_"));
        cancelDownloadBtn.setOnAction(e -> {
            cancelDownload = true;
            if (currentTask != null && currentFTPClient.isConnected()) {
                try {
                    currentFTPClient.abort(); // Abort ongoing transfer
                    appendLog("Download cancelled"); // Abort ongoing transfer
                } catch (IOException ex) {
                    appendLog("Abort error: " + ex.getMessage());
                }
            }
        });
    }

    private void appendLog(String text) {
        Platform.runLater(() -> logArea.appendText(text + "\n"));
    }

    private void resetButtons() {
        encryptedDownloadBtn.setDisable(false);
        zReportDownloadBtn.setDisable(false);
        cancelDownloadBtn.setDisable(true);
    }

    private void downloadFilesWithPrefix(String prefix) {
        String selectedDay = daySelector.getValue();
        if (selectedDay == null) {
            showAlert("Please select a day first.");
            return;
        }

        cancelDownload = false;
        cancelDownloadBtn.setDisable(false);
        encryptedDownloadBtn.setDisable(true);
        zReportDownloadBtn.setDisable(true);

        Task<Void> task = new Task<Void>() {
            FTPClient ftp; // Accessible for cancellation

            @Override
            protected Void call() throws Exception {
                ftp = new FTPClient();
                try {
                    appendLog("Connecting to FTP server...");
                    ftp.connect(FTP_SERVER, FTP_PORT);
                    ftp.login(FTP_USER, FTP_PASS);
                    ftp.enterLocalPassiveMode();
                    ftp.setFileType(FTP.BINARY_FILE_TYPE);
                    ftp.changeWorkingDirectory(FTP_REMOTE_DIR);
                    appendLog("Connected successfully.");

                    String[] allFiles = ftp.listNames();
                    if (allFiles == null || allFiles.length == 0) {
                        appendLog("No files found on server.");
                        return null;
                    }

                    // Filter matching files
                    final String[] filesToDownload = java.util.Arrays.stream(allFiles)
                        .filter(f -> f.startsWith(prefix) && f.contains(selectedDay))
                        .toArray(String[]::new);

                    final int total = filesToDownload.length;
                    if (total == 0) {
                        appendLog("No matching files for prefix " + prefix + " and day " + selectedDay);
                        return null;
                    }

                    appendLog("Found " + total + " files to download.");

//                    String targetDir = LOCAL_DOWNLOAD_DIR + "/Output Files/DAY" + selectedDay.substring(2);
//                    new File(targetDir).mkdirs();
                    
                    // Determine target directory based on file type
                    String dayNumber = selectedDay.substring(2);
                    String targetDir = "";
                    
                    if ("E_".equals(prefix)) {
                        targetDir = LOCAL_DOWNLOAD_DIR + "/Output Files/DAY" + dayNumber;
                    } else if ("Z_".equals(prefix)) {
                        targetDir = LOCAL_DOWNLOAD_DIR + "/Z reports/DAY" + dayNumber;
                    }
                    
                    // Create directory if it doesn't exist
                    File dir = new File(targetDir);
                    if (!dir.exists()) {
                        if (dir.mkdirs()) {
                            appendLog("Created directory: " + targetDir);
                        } else {
                            appendLog("Failed to create directory: " + targetDir);
                            return null;
                        }
                        
                    }

                    int done = 0;
                    for (String f : filesToDownload) {
                        if (cancelDownload || isCancelled()) {
                            appendLog("Download cancelled by user.");
                            break;
                        }
                        appendLog("Downloading [" + (done+1) + "/" + total + "]: " + f);

                        File localFile = new File(targetDir, f);
                        try (OutputStream os = new FileOutputStream(localFile)) {
                            boolean success = ftp.retrieveFile(f, os);
                            
                            if (success) {
                                done++;
                                appendLog("✔ Downloaded: " + f);
                            } else {
                                appendLog("✖ Failed: " + f);
                                // Complete pending command only on failure
                                if (ftp.completePendingCommand()) {
                                    appendLog("Completed pending command after failure.");
                                }
                            }
                        } catch (IOException ex) {
                            appendLog("❗ Error downloading " + f + ": " + ex.getMessage());
                        }
                    }
                    appendLog("Downloaded " + done + "/" + total + " files.");
                } catch (IOException ex) {
                    appendLog("❗ FTP error: " + ex.getMessage());
                } finally {
                    try {
                        if (ftp.isConnected()) {
                            ftp.logout();
                            ftp.disconnect();
                        }
                    } catch (IOException ignored) {}
                }
                return null;
            }
        };

        // Set current task reference
        currentTask = task;

        task.setOnSucceeded(e -> {
            appendLog("All downloads completed.");
            resetButtons();
            currentTask = null;
        });
        task.setOnCancelled(e -> {
            appendLog("Download task cancelled.");
            resetButtons();
            currentTask = null;
        });
        task.setOnFailed(e -> {
            appendLog("Download task failed: " + task.getException().getMessage());
            resetButtons();
            currentTask = null;
        });

        new Thread(task).start();
    }
           
    private void showAlert(String msg) {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.INFORMATION, msg, ButtonType.OK);
            alert.showAndWait();
        });
    }

    public static void main(String[] args) {
        launch(args);
    }
}