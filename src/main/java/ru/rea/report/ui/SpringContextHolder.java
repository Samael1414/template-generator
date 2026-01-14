package ru.rea.report.ui;

import lombok.Getter;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

@Component
public class SpringContextHolder {
    @Getter
    private static ConfigurableApplicationContext context;

    public SpringContextHolder(ConfigurableApplicationContext context) {
        SpringContextHolder.context = context;
    }

}
