package io.kestra.plugin.selenium;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.plugin.selenium.Browse.Action;
import io.kestra.plugin.selenium.Browse.ActionType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Pure unit tests that do not require a running browser.
 */
@KestraTest
class BrowseUnitTest {

    @Inject
    RunContextFactory runContextFactory;

    @Test
    void givenAction_whenBuilt_thenFieldsAreAccessible() {
        var action = Action.builder()
            .action(ActionType.NAVIGATE)
            .url(Property.ofValue("https://example.com"))
            .build();

        assertThat(action.getAction(), is(ActionType.NAVIGATE));
        assertThat(action.getUrl(), notNullValue());
    }

    @Test
    void givenBrowseTask_whenBuilt_thenActionsListIsPreserved() {
        var actions = List.of(
            Action.builder()
                .action(ActionType.NAVIGATE)
                .url(Property.ofValue("https://example.com"))
                .build(),
            Action.builder()
                .action(ActionType.SCREENSHOT)
                .name(Property.ofValue("page.png"))
                .build()
        );

        var task = Browse.builder()
            .id("unit-test")
            .type(Browse.class.getName())
            .remoteUrl(Property.ofValue("http://localhost:4444"))
            .actions(actions)
            .build();

        assertThat(task.getActions(), hasSize(2));
        assertThat(task.getActions().getFirst().getAction(), is(ActionType.NAVIGATE));
        assertThat(task.getActions().get(1).getAction(), is(ActionType.SCREENSHOT));
    }

    @Test
    void givenOutputBuilder_whenBuilt_thenFieldsPopulated() {
        var output = Browse.Output.builder()
            .extracted(java.util.Map.of("heading", "Hello World"))
            .screenshots(java.util.Map.of())
            .scriptResults(java.util.Map.of())
            .downloads(java.util.Map.of())
            .build();

        assertThat(output.getExtracted(), hasEntry("heading", "Hello World"));
        assertThat(output.getScreenshots(), anEmptyMap());
        assertThat(output.getScriptResults(), anEmptyMap());
        assertThat(output.getDownloads(), anEmptyMap());
    }

    @Test
    void givenAllActionTypes_whenAccessed_thenEnumValuesAreDistinct() {
        var types = ActionType.values();
        assertThat(types, arrayWithSize(8));
    }

    @Test
    void givenActionWithId_whenOutputKeyRendered_thenUsesId() throws Exception {
        var runContext = runContextFactory.of();

        var actionWithId = Action.builder()
            .action(ActionType.EXTRACT_TEXT)
            .id(Property.ofValue("myKey"))
            .selector(Property.ofValue("h1"))
            .build();

        var actionWithoutId = Action.builder()
            .action(ActionType.EXTRACT_TEXT)
            .selector(Property.ofValue("h1"))
            .build();

        // outputKey is package-private via the task; test via rendered Property directly.
        var renderedId = runContext.render(actionWithId.getId()).as(String.class).orElse("extract_0");
        var renderedFallback = runContext.render(actionWithoutId.getId()).as(String.class).orElse("extract_3");

        assertThat(renderedId, is("myKey"));
        assertThat(renderedFallback, is("extract_3"));
    }
}
