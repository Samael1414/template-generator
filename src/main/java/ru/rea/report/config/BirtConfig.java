package ru.rea.report.config;

import org.eclipse.birt.core.framework.Platform;
import org.eclipse.birt.report.model.api.DesignConfig;
import org.eclipse.birt.report.model.api.IDesignEngine;
import org.eclipse.birt.report.model.api.IDesignEngineFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class BirtConfig {

    @Bean(destroyMethod = "shutdown")
    public BirtPlatformLifecycle birtPlatformLifecycle() throws Exception {
        Platform.startup(new DesignConfig());
        return new BirtPlatformLifecycle();
    }

    @Bean
    public IDesignEngine designEngine(BirtPlatformLifecycle lifecycle) {
        IDesignEngineFactory factory = (IDesignEngineFactory) Platform
                .createFactoryObject(IDesignEngineFactory.EXTENSION_DESIGN_ENGINE_FACTORY);
        return factory.createDesignEngine(new DesignConfig());
    }

    public static final class BirtPlatformLifecycle {
        public void shutdown() {
            try {
                Platform.shutdown();
            } catch (Exception ignored) {
            }
        }
    }
}