package io.kestra.plugin.selenium;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.plugin.selenium.Browse.Action;
import io.kestra.plugin.selenium.Browse.ActionType;
import io.kestra.plugin.selenium.Browse.WaitCondition;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
    void givenDataUrlPage_whenNavigateWaitExtractAndScreenshot_thenOutputsPopulated() throws Exception {
        var gridUrl = System.getenv("SELENIUM_GRID_URL");
        var dataUrl = "data:text/html,<html><body><h1>Kestra Selenium</h1></body></html>";

        var task = Browse.builder()
            .id("navigate-test-" + UUID.randomUUID())
            .type(Browse.class.getName())
            .remoteUrl(Property.ofValue(gridUrl))
            .actions(List.of(
                Action.builder().action(ActionType.NAVIGATE).url(Property.ofValue(dataUrl)).build(),
                Action.builder().action(ActionType.WAIT_FOR).selector(Property.ofValue("h1"))
                    .waitTimeout(Property.ofValue(Duration.ofSeconds(10))).build(),
                Action.builder().action(ActionType.EXTRACT_TEXT).id(Property.ofValue("heading"))
                    .selector(Property.ofValue("h1")).build(),
                Action.builder().action(ActionType.SCREENSHOT).name(Property.ofValue("page.png")).build()
            ))
            .build();

        var runContext = runContextFactory.of();
        var output = task.run(runContext);

        assertThat(output.getExtracted(), hasEntry("heading", "Kestra Selenium"));
        assertThat(output.getScreenshots(), hasKey("page.png"));
        assertThat(output.getScreenshots().get("page.png"), notNullValue());
    }

    @Test
    void givenFileDownloadPage_whenDownloadAction_thenFileStoredInKestra() throws Exception {
        var gridUrl = System.getenv("SELENIUM_GRID_URL");

        var task = Browse.builder()
            .id("download-test-" + UUID.randomUUID())
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
                    .waitTimeout(Property.ofValue(Duration.ofSeconds(15)))
                    .build(),
                Action.builder()
                    .action(ActionType.DOWNLOAD)
                    .selector(Property.ofValue(".example a:first-of-type"))
                    .waitTimeout(Property.ofValue(Duration.ofSeconds(30)))
                    .build()
            ))
            .build();

        var runContext = runContextFactory.of();
        var output = task.run(runContext);

        assertThat(output.getDownloads(), not(anEmptyMap()));
        output.getDownloads().forEach((name, uri) -> {
            assertThat(name, not(emptyString()));
            assertThat(uri, notNullValue());
            assertThat(uri.toString(), startsWith("kestra://"));
            assertThat(
                "Stored filename must not match a temp/in-progress pattern: " + name,
                Browse.TEMP_DOWNLOAD_PATTERN.matcher(name).find(),
                is(false)
            );
        });
    }

    @Test
    void givenClickChangingDom_whenExtractText_thenReturnsUpdatedContent() throws Exception {
        var gridUrl = System.getenv("SELENIUM_GRID_URL");
        var dataUrl = "data:text/html,<html><body>"
            + "<button id='btn' onclick=\"document.getElementById('out').innerText='clicked'\">Click me</button>"
            + "<span id='out'>initial</span>"
            + "</body></html>";

        var task = Browse.builder()
            .id("click-test-" + UUID.randomUUID())
            .type(Browse.class.getName())
            .remoteUrl(Property.ofValue(gridUrl))
            .actions(List.of(
                Action.builder().action(ActionType.NAVIGATE).url(Property.ofValue(dataUrl)).build(),
                Action.builder().action(ActionType.CLICK).selector(Property.ofValue("#btn")).build(),
                Action.builder().action(ActionType.EXTRACT_TEXT).id(Property.ofValue("out"))
                    .selector(Property.ofValue("#out")).build()
            ))
            .build();

        var runContext = runContextFactory.of();
        var output = task.run(runContext);

        assertThat(output.getExtracted(), hasEntry("out", "clicked"));
    }

    @Test
    void givenTypeWithClear_whenExecuteScript_thenInputValueMatchesTypedText() throws Exception {
        var gridUrl = System.getenv("SELENIUM_GRID_URL");
        var dataUrl = "data:text/html,<html><body><input id='field' value='prefill'/></body></html>";

        var task = Browse.builder()
            .id("type-test-" + UUID.randomUUID())
            .type(Browse.class.getName())
            .remoteUrl(Property.ofValue(gridUrl))
            .actions(List.of(
                Action.builder().action(ActionType.NAVIGATE).url(Property.ofValue(dataUrl)).build(),
                Action.builder().action(ActionType.TYPE).selector(Property.ofValue("#field"))
                    .value(Property.ofValue("hello")).clear(Property.ofValue(true)).build(),
                Action.builder().action(ActionType.EXECUTE_SCRIPT).id(Property.ofValue("value"))
                    .script(Property.ofValue("return document.getElementById('field').value;")).build()
            ))
            .build();

        var runContext = runContextFactory.of();
        var output = task.run(runContext);

        assertThat(output.getScriptResults(), hasEntry("value", "hello"));
    }

    @Test
    void givenMultipleElements_whenExtractTextMultiple_thenReturnsListOfTexts() throws Exception {
        var gridUrl = System.getenv("SELENIUM_GRID_URL");
        var dataUrl = "data:text/html,<html><body><ul>"
            + "<li class='item'>one</li><li class='item'>two</li><li class='item'>three</li>"
            + "</ul></body></html>";

        var task = Browse.builder()
            .id("multi-extract-test-" + UUID.randomUUID())
            .type(Browse.class.getName())
            .remoteUrl(Property.ofValue(gridUrl))
            .actions(List.of(
                Action.builder().action(ActionType.NAVIGATE).url(Property.ofValue(dataUrl)).build(),
                Action.builder().action(ActionType.EXTRACT_TEXT).id(Property.ofValue("items"))
                    .selector(Property.ofValue(".item")).multiple(Property.ofValue(true)).build()
            ))
            .build();

        var runContext = runContextFactory.of();
        var output = task.run(runContext);

        @SuppressWarnings("unchecked")
        var items = (List<String>) output.getExtracted().get("items");
        assertThat(items, contains("one", "two", "three"));
    }

    @Test
    void givenDelayedVisibility_whenWaitForVisible_thenConditionSucceeds() throws Exception {
        var gridUrl = System.getenv("SELENIUM_GRID_URL");
        var dataUrl = "data:text/html,<html><body>"
            + "<div id='hidden' style='display:none'>now visible</div>"
            + "<script>setTimeout(function(){document.getElementById('hidden').style.display='block';}, 1000);</script>"
            + "</body></html>";

        var task = Browse.builder()
            .id("wait-visible-test-" + UUID.randomUUID())
            .type(Browse.class.getName())
            .remoteUrl(Property.ofValue(gridUrl))
            .actions(List.of(
                Action.builder().action(ActionType.NAVIGATE).url(Property.ofValue(dataUrl)).build(),
                Action.builder().action(ActionType.WAIT_FOR).selector(Property.ofValue("#hidden"))
                    .condition(Property.ofValue(WaitCondition.VISIBLE))
                    .waitTimeout(Property.ofValue(Duration.ofSeconds(5))).build(),
                Action.builder().action(ActionType.EXTRACT_TEXT).id(Property.ofValue("text"))
                    .selector(Property.ofValue("#hidden")).build()
            ))
            .build();

        var runContext = runContextFactory.of();
        var output = task.run(runContext);

        assertThat(output.getExtracted(), hasEntry("text", "now visible"));
    }

    @Test
    void givenScriptReturningWebElement_whenExecuteScript_thenTaskFails() {
        var gridUrl = System.getenv("SELENIUM_GRID_URL");
        var dataUrl = "data:text/html,<html><body><div id='el'>x</div></body></html>";

        var task = Browse.builder()
            .id("script-webelement-test-" + UUID.randomUUID())
            .type(Browse.class.getName())
            .remoteUrl(Property.ofValue(gridUrl))
            .actions(List.of(
                Action.builder().action(ActionType.NAVIGATE).url(Property.ofValue(dataUrl)).build(),
                Action.builder().action(ActionType.EXECUTE_SCRIPT)
                    .script(Property.ofValue("return document.getElementById('el');")).build()
            ))
            .build();

        var runContext = runContextFactory.of();

        assertThrows(IllegalArgumentException.class, () -> task.run(runContext));
    }

    @Test
    void givenMissingSelector_whenExtractText_thenFailsWithinShortTimeout() {
        var gridUrl = System.getenv("SELENIUM_GRID_URL");
        var dataUrl = "data:text/html,<html><body><h1>present</h1></body></html>";

        var task = Browse.builder()
            .id("missing-selector-test-" + UUID.randomUUID())
            .type(Browse.class.getName())
            .remoteUrl(Property.ofValue(gridUrl))
            .actions(List.of(
                Action.builder().action(ActionType.NAVIGATE).url(Property.ofValue(dataUrl)).build(),
                Action.builder().action(ActionType.EXTRACT_TEXT).selector(Property.ofValue("#does-not-exist"))
                    .waitTimeout(Property.ofValue(Duration.ofSeconds(2))).build()
            ))
            .build();

        var runContext = runContextFactory.of();

        assertThrows(IllegalStateException.class, () -> task.run(runContext));
    }

    @Test
    void givenTwoUnnamedScreenshots_whenScreenshotAction_thenTwoKeysProduced() throws Exception {
        var gridUrl = System.getenv("SELENIUM_GRID_URL");
        var dataUrl = "data:text/html,<html><body><h1>shot</h1></body></html>";

        var task = Browse.builder()
            .id("screenshot-test-" + UUID.randomUUID())
            .type(Browse.class.getName())
            .remoteUrl(Property.ofValue(gridUrl))
            .actions(List.of(
                Action.builder().action(ActionType.NAVIGATE).url(Property.ofValue(dataUrl)).build(),
                Action.builder().action(ActionType.SCREENSHOT).build(),
                Action.builder().action(ActionType.SCREENSHOT).build()
            ))
            .build();

        var runContext = runContextFactory.of();
        var output = task.run(runContext);

        assertThat(output.getScreenshots().keySet(), hasSize(2));
    }
}
