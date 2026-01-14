package ru.rea.report;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import ru.rea.report.ui.TemplateGeneratorFxApp;

@SpringBootApplication
public class TemplateGeneratorApplication {

    public static void main(String[] args) {
        new SpringApplicationBuilder(TemplateGeneratorApplication.class)
                .web(WebApplicationType.NONE)
                .run(args);
        TemplateGeneratorFxApp.launchApp(args);
    }

}
