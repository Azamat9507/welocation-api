package com.minsu.welocation.spot;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SceneImportRunnerTest {

    @Test
    void aBrokenFileDoesNotStopStartup() throws Exception {
        SceneImporter importer = mock(SceneImporter.class);
        when(importer.importAll()).thenThrow(new IOException("scenes.json: unexpected end of input"));

        assertThatCode(() -> new SceneImportRunner(importer).run(null)).doesNotThrowAnyException();
    }
}
