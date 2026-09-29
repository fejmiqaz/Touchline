package atrck.attendancetracker.config;

import atrck.attendancetracker.service.DemoDataService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.demo.owner")
@RequiredArgsConstructor
public class DemoDataRunner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(DemoDataRunner.class);
    private final DemoDataService demoData;
    private final ConfigurableApplicationContext context;
    @Value("${app.demo.owner}") private String owner;
    @Value("${app.demo.exit-after-seeding:false}") private boolean exitAfterSeeding;

    @Override
    public void run(ApplicationArguments args) {
        int created = demoData.seed(owner);
        log.info("Sample data ready: {} new generations, {} players, {} training sessions.", created, created * 12, created * 11);
        if (exitAfterSeeding) SpringApplication.exit(context, () -> 0);
    }
}
