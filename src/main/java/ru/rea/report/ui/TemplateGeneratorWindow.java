package ru.rea.report.ui;

import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ru.rea.report.service.TemplateConvertService;

import java.io.File;
import java.nio.file.Files;

@Component
@RequiredArgsConstructor
public class TemplateGeneratorWindow {

    private final TemplateConvertService convertService;

    public void show(Stage stage) {
        stage.setTitle("Template Generator (.odt/.ods -> .rptdesign)");

        TextField inputField = new TextField();
        inputField.setPromptText("Выберите файл шаблона (.odt/.ods)");
        inputField.setEditable(false);

        Button inputBtn = new Button("Выбрать…");

        TextField outputField = new TextField();
        outputField.setPromptText("Куда сохранить .rptdesign");
        outputField.setEditable(false);

        Button outputBtn = new Button("Сохранить как…");

        Button runBtn = new Button("Сконвертировать");
        runBtn.setDisable(true);

        Label status = new Label();
        ProgressIndicator progress = new ProgressIndicator();
        progress.setVisible(false);
        progress.setMaxSize(24, 24);
        progress.setProgress(ProgressIndicator.INDETERMINATE_PROGRESS);

        ProgressBar progressBar = new ProgressBar();
        progressBar.setVisible(false);
        progressBar.setMaxWidth(Double.MAX_VALUE);
        progressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);


        FileChooser inChooser = new FileChooser();
        inChooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("ODF templates", "*.odt", "*.ods"),
                new FileChooser.ExtensionFilter("ODT", "*.odt"),
                new FileChooser.ExtensionFilter("ODS", "*.ods")
        );

        FileChooser outChooser = new FileChooser();
        outChooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("BIRT design", "*.rptdesign")
        );

        final File[] inputFile = {null};
        final File[] outputFile = {null};

        inputBtn.setOnAction(e -> {
            File f = inChooser.showOpenDialog(stage);
            if (f != null) {
                inputFile[0] = f;
                inputField.setText(f.getAbsolutePath());

                String baseName = stripExt(f.getName());
                outChooser.setInitialFileName(baseName + ".rptdesign");

                status.setText("");
                updateRunEnabled(runBtn, inputFile[0], outputFile[0]);
            }
        });

        outputBtn.setOnAction(e -> {
            File f = outChooser.showSaveDialog(stage);
            if (f != null) {
                outputFile[0] = ensureRptdesignExt(f);
                outputField.setText(outputFile[0].getAbsolutePath());

                status.setText("");
                updateRunEnabled(runBtn, inputFile[0], outputFile[0]);
            }
        });

        // ВАЖНО: конвертацию делаем в background thread
        runBtn.setOnAction(e -> {
            if (inputFile[0] == null || outputFile[0] == null) return;

            runBtn.setDisable(true);
            inputBtn.setDisable(true);
            outputBtn.setDisable(true);

            progress.setVisible(true);
            progressBar.setVisible(true);

            status.setText("Выполняю конвертацию...");

            Task<Void> task = new Task<>() {
                @Override
                protected Void call() throws Exception {
                    byte[] rpt = convertService.convertToRptdesign(inputFile[0].toPath());
                    Files.write(outputFile[0].toPath(), rpt);
                    return null;
                }
            };

            task.setOnSucceeded(ev -> {
                progress.setVisible(false);
                progressBar.setVisible(false);

                status.setText("Готово: " + outputFile[0].getAbsolutePath());

                inputBtn.setDisable(false);
                outputBtn.setDisable(false);
                updateRunEnabled(runBtn, inputFile[0], outputFile[0]);
            });

            task.setOnFailed(ev -> {
                progress.setVisible(false);
                progressBar.setVisible(false);

                Throwable ex = task.getException();
                if (ex != null) ex.printStackTrace();

                status.setText("Ошибка: " +
                        (ex == null ? "unknown" : ex.getClass().getSimpleName() + ": " + ex.getMessage()));

                inputBtn.setDisable(false);
                outputBtn.setDisable(false);
                updateRunEnabled(runBtn, inputFile[0], outputFile[0]);
            });

            Thread t = new Thread(task, "tplgen-convert");
            t.setDaemon(true);
            t.start();
        });

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(12));

        grid.add(new Label("Шаблон:"), 0, 0);
        grid.add(inputField, 1, 0);
        grid.add(inputBtn, 2, 0);

        grid.add(new Label("Выход:"), 0, 1);
        grid.add(outputField, 1, 1);
        grid.add(outputBtn, 2, 1);

        HBox actions = new HBox(10, runBtn, progress);
        VBox root = new VBox(10, grid, actions, progressBar, status);
        root.setPadding(new Insets(12));

        HBox.setHgrow(runBtn, Priority.NEVER);
        VBox.setVgrow(grid, Priority.NEVER);


        ColumnConstraints c0 = new ColumnConstraints();
        c0.setMinWidth(70);

        ColumnConstraints c1 = new ColumnConstraints();
        c1.setHgrow(Priority.ALWAYS);

        ColumnConstraints c2 = new ColumnConstraints();
        c2.setMinWidth(120);

        grid.getColumnConstraints().addAll(c0, c1, c2);

        stage.setScene(new Scene(root, 760, 190));
        stage.show();
    }

    private static void updateRunEnabled(Button runBtn, File in, File out) {
        runBtn.setDisable(in == null || out == null);
    }

    private static String stripExt(String name) {
        int i = name.lastIndexOf('.');
        return i > 0 ? name.substring(0, i) : name;
    }

    private static File ensureRptdesignExt(File f) {
        String n = f.getName().toLowerCase();
        if (n.endsWith(".rptdesign")) return f;
        return new File(f.getParentFile(), f.getName() + ".rptdesign");
    }
}
