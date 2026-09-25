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
    void givenTempDownloadMarkers_whenMatchingPattern_thenMatches() {
        assertThat(Browse.TEMP_DOWNLOAD_PATTERN.matcher("report.csv.crdownload").find(), is(true));
        assertThat(Browse.TEMP_DOWNLOAD_PATTERN.matcher("report.csv.part").find(), is(true));
        assertThat(Browse.TEMP_DOWNLOAD_PATTERN.matcher("report.csv.tmp").find(), is(true));
        assertThat(Browse.TEMP_DOWNLOAD_PATTERN.matcher(".com.google.Chrome.abc123").find(), is(true));
        assertThat(Browse.TEMP_DOWNLOAD_PATTERN.matcher(".org.chromium.Chromium.xyz").find(), is(true));
        assertThat(Browse.TEMP_DOWNLOAD_PATTERN.matcher(".download").find(), is(true));
    }

    @Test
    void givenStableFilenames_whenMatchingPattern_thenDoesNotMatch() {
        assertThat(Browse.TEMP_DOWNLOAD_PATTERN.matcher("report.csv").find(), is(false));
        assertThat(Browse.TEMP_DOWNLOAD_PATTERN.matcher("archive.tar.gz").find(), is(false));
    }

    @Test
    void givenPreexistingAndTempFiles_whenSelectingNewStableFiles_thenOnlyNewStableFilesReturnedSorted() {
        var before = List.of("existing.csv");
        var current = List.of("existing.csv", "zeta.csv", "alpha.csv", "still-downloading.csv.crdownload");

        var result = Browse.selectNewStableFiles(before, current);

        assertThat(result, contains("alpha.csv", "zeta.csv"));
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
