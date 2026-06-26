package io.kestra.plugin.selenium;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.plugin.selenium.Browse.Action;
import io.kestra.plugin.selenium.Browse.ActionType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.List;
import java.util.regex.Pattern;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Integration test that requires a running Selenium Grid.
 * Set SELENIUM_GRID_URL (e.g. http://localhost:4444) to enable.
 */
@KestraTest
@EnabledIfEnvironmentVariable(named = "SELENIUM_GRID_URL", matches = ".+")
class BrowseIntegrationTest {

    @Inject
    RunContextFactory runContextFactory;

    @Test
    void givenExampleCom_whenNavigateExtractAndScreenshot_thenOutputsPopulated() throws Exception {
        var gridUrl = System.getenv("SELENIUM_GRID_URL");

        var task = Browse.builder()
            .id("integration-test")
            .type(Browse.class.getName())
            .remoteUrl(Property.ofValue(gridUrl))
            .actions(List.of(
                Action.builder()
                    .action(ActionType.NAVIGATE)
                    .url(Property.ofValue("https://example.com"))
                    .build(),
                Action.builder()
                    .action(ActionType.WAIT_FOR)
                    .selector(Property.ofValue("h1"))
                    .waitTimeout(Property.ofValue(java.time.Duration.ofSeconds(10)))
                    .build(),
                Action.builder()
                    .action(ActionType.EXTRACT_TEXT)
                    .id(Property.ofValue("heading"))
                    .selector(Property.ofValue("h1"))
                    .build(),
                Action.builder()
                    .action(ActionType.SCREENSHOT)
                    .name(Property.ofValue("example.png"))
                    .build()
            ))
            .build();

        var runContext = runContextFactory.of();
        var output = task.run(runContext);

        assertThat(output.getExtracted(), hasKey("heading"));
        assertThat((String) output.getExtracted().get("heading"), not(emptyString()));
        assertThat(output.getScreenshots(), hasKey("example.png"));
        assertThat(output.getScreenshots().get("example.png"), notNullValue());
    }

    @Test
    void givenFileDownloadPage_whenDownloadAction_thenFileStoredInKestra() throws Exception {
        var gridUrl = System.getenv("SELENIUM_GRID_URL");

        var task = Browse.builder()
            .id("download-test")
            .type(Browse.class.getName())
            .remoteUrl(Property.ofValue(gridUrl))
            .actions(List.of(
                Action.builder()
                    .action(ActionType.NAVIGATE)
                    .url(Property.ofValue("https://the-internet.herokuapp.com/download"))
                    .build(),
                Action.builder()
                    .action(ActionType.WAIT_FOR)
                    .selector(Property.ofValue(".example a"))
                    .waitTimeout(Property.ofValue(java.time.Duration.ofSeconds(15)))
                    .build(),
                Action.builder()
                    .action(ActionType.DOWNLOAD)
                    .selector(Property.ofValue(".example a:first-of-type"))
                    .waitTimeout(Property.ofValue(java.time.Duration.ofSeconds(30)))
                    .build()
            ))
            .build();

        var runContext = runContextFactory.of();
        var output = task.run(runContext);

        // Verify that the BLOCKER-1 fix prevents temp/in-progress markers from being stored.
        var tempPattern = Pattern.compile(
            "\\.crdownload$|\\.part$|\\.tmp$|^\\.com\\.google\\.Chrome\\.|^\\.org\\.chromium\\.Chromium\\.|^\\.download$"
        );

        assertThat(output.getDownloads(), not(anEmptyMap()));
        output.getDownloads().forEach((name, uri) -> {
            assertThat(name, not(emptyString()));
            assertThat(uri, notNullValue());
            assertThat(uri.toString(), startsWith("kestra://"));
            assertThat(
                "Stored filename must not match a temp/in-progress pattern: " + name,
                tempPattern.matcher(name).find(),
                is(false)
            );
        });
    }

    @Test
    void givenDataUrl_whenExtractText_thenReturnsExpectedContent() throws Exception {
        var gridUrl = System.getenv("SELENIUM_GRID_URL");

        // data: URI avoids any network call, making the test fully self-contained
        var dataUrl = "data:text/html,<html><body><h1 id='title'>Kestra Selenium</h1></body></html>";

        var task = Browse.builder()
            .id("data-url-test")
            .type(Browse.class.getName())
            .remoteUrl(Property.ofValue(gridUrl))
            .actions(List.of(
                Action.builder()
                    .action(ActionType.NAVIGATE)
                    .url(Property.ofValue(dataUrl))
                    .build(),
                Action.builder()
                    .action(ActionType.EXTRACT_TEXT)
                    .id(Property.ofValue("title"))
                    .selector(Property.ofValue("h1"))
                    .build()
            ))
            .build();

        var runContext = runContextFactory.of();
        var output = task.run(runContext);

        assertThat(output.getExtracted(), hasKey("title"));
        assertThat((String) output.getExtracted().get("title"), is("Kestra Selenium"));
    }
}
