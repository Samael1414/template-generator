package ru.rea.report.ui;

import javafx.application.Application;
import javafx.stage.Stage;
import org.springframework.context.ConfigurableApplicationContext;
import ru.rea.report.ui.SpringContextHolder;

public class TemplateGeneratorFxApp extends Application {

    public static void launchApp(String[] args) {
        Application.launch(args);
    }

    @Override
    public void start(Stage stage) {
        ConfigurableApplicationContext ctx = SpringContextHolder.getContext();
        TemplateGeneratorWindow window = ctx.getBean(TemplateGeneratorWindow.class);
        window.show(stage);
    }
}
