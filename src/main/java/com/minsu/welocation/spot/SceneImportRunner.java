package com.minsu.welocation.spot;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

// On in the prod profile (and locally with SCENES_IMPORT=true); off in tests, which import their own small file
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "welocation.scenes.import", havingValue = "true")
class SceneImportRunner implements ApplicationRunner {

    private final SceneImporter importer;

    @Override
    public void run(ApplicationArguments args) {
        // the site works without scenes, so a broken file is logged instead of stopping startup
        try {
            importer.importAll();
        } catch (Exception e) {
            log.error("scene import failed: {}", e.getClass().getSimpleName());
        }
    }
}
