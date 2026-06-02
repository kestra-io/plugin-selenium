package io.kestra.plugin.selenium;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Metric;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.executions.metrics.Counter;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.Output;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;
import org.openqa.selenium.By;
import org.openqa.selenium.HasDownloads;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Automate a browser session",
    description = """
        Opens a single WebDriver session against a Selenium Grid, executes a list of actions
        sequentially, then closes the session. Supports navigation, clicking, typing,
        waiting for elements, extracting text, taking screenshots, running JavaScript,
        and downloading files via Selenium Grid managed downloads.
        """
)
@Plugin(
    examples = {
        @Example(
            title = "Navigate to example.com and extract the heading text.",
            full = true,
            code = """
                id: selenium_browse
                namespace: company.team

                tasks:
                  - id: browse
                    type: io.kestra.plugin.selenium.Browse
                    remoteUrl: "{{ secret('SELENIUM_GRID_URL') }}"
                    actions:
                      - action: NAVIGATE
                        url: "https://example.com"
                      - action: EXTRACT_TEXT
                        id: heading
                        selector: "h1"
                      - action: SCREENSHOT
                        name: "result.png"
                """
        ),
        @Example(
            title = "Download a file by clicking a link and storing it in Kestra internal storage.",
            full = true,
            code = """
                id: selenium_download
                namespace: company.team

                tasks:
                  - id: browse
                    type: io.kestra.plugin.selenium.Browse
                    remoteUrl: "{{ secret('SELENIUM_GRID_URL') }}"
                    actions:
                      - action: NAVIGATE
                        url: "https://the-internet.herokuapp.com/download"
                      - action: WAIT_FOR
                        selector: ".example a"
                      - action: DOWNLOAD
                        selector: ".example a:first-of-type"
                """
        )
    }
)
@Metric(name = "actions.count", type = "counter", description = "Total number of actions executed.")
public class Browse extends AbstractSeleniumTask implements RunnableTask<Browse.Output> {

    // Matches in-progress download markers: Chromium (.crdownload, .com.google.Chrome.*, .org.chromium.Chromium.*),
    // Firefox (.part, .tmp), Edge (.download).
    private static final Pattern TEMP_DOWNLOAD_PATTERN = Pattern.compile(
        "\\.crdownload$|\\.part$|\\.tmp$|^\\.com\\.google\\.Chrome\\.|^\\.org\\.chromium\\.Chromium\\.|^\\.download$"
    );

    // Plain List, not Property<List>, to avoid Jackson polymorphism issues with Action subtypes.
    @Schema(title = "Actions", description = "Ordered list of browser actions to execute within a single session.")
    @NotNull
    @NotEmpty
    @PluginProperty(group = "main")
    private List<Action> actions;

    @Override
    public Output run(RunContext runContext) throws Exception {
        var logger = runContext.logger();
        var driver = buildDriver(runContext);

        Map<String, Object> extracted = new HashMap<>();
        Map<String, URI> screenshots = new HashMap<>();
        Map<String, Object> scriptResults = new HashMap<>();
        Map<String, URI> downloads = new HashMap<>();
        int actionIndex = 0;

        try {
            for (var action : actions) {
                var actionType = action.getAction();
                logger.info("Executing action [{}]: {}", actionIndex, actionType);

                switch (actionType) {
                    case NAVIGATE -> {
                        var rUrl = runContext.render(action.getUrl()).as(String.class).orElseThrow(
                            () -> new IllegalArgumentException("url is required for NAVIGATE")
                        );
                        driver.get(rUrl);
                    }
                    case CLICK -> {
                        var rSelector = renderSelector(runContext, action, actionType);
                        driver.findElement(By.cssSelector(rSelector)).click();
                    }
                    case TYPE -> {
                        var rSelector = renderSelector(runContext, action, actionType);
                        var rValue = runContext.render(action.getValue()).as(String.class).orElseThrow(
                            () -> new IllegalArgumentException("value is required for TYPE")
                        );
                        driver.findElement(By.cssSelector(rSelector)).sendKeys(rValue);
                    }
                    case WAIT_FOR -> {
                        var rSelector = renderSelector(runContext, action, actionType);
                        var rWaitTimeout = runContext.render(action.getWaitTimeout()).as(Duration.class).orElse(Duration.ofSeconds(10));
                        new WebDriverWait(driver, rWaitTimeout)
                            .until(ExpectedConditions.presenceOfElementLocated(By.cssSelector(rSelector)));
                    }
                    case EXTRACT_TEXT -> {
                        var rSelector = renderSelector(runContext, action, actionType);
                        var rMultiple = runContext.render(action.getMultiple()).as(Boolean.class).orElse(false);
                        var key = outputKey(runContext, action, actionIndex, "extract");
                        warnOnKeyCollision(logger, extracted, key, actionType);
                        if (Boolean.TRUE.equals(rMultiple)) {
                            var elements = driver.findElements(By.cssSelector(rSelector));
                            if (elements.isEmpty()) {
                                logger.warn("EXTRACT_TEXT: no elements matched selector '{}'", rSelector);
                            }
                            extracted.put(key, elements.stream().map(WebElement::getText).toList());
                        } else {
                            try {
                                extracted.put(key, driver.findElement(By.cssSelector(rSelector)).getText());
                            } catch (NoSuchElementException e) {
                                throw new IllegalStateException(
                                    "EXTRACT_TEXT: element not found for selector '" + rSelector + "'", e
                                );
                            }
                        }
                    }
                    case SCREENSHOT -> {
                        var rName = runContext.render(action.getName()).as(String.class).orElse("screenshot.png");
                        warnOnKeyCollision(logger, screenshots, rName, actionType);
                        var bytes = ((TakesScreenshot) driver).getScreenshotAs(OutputType.BYTES);
                        var uri = runContext.storage().putFile(new ByteArrayInputStream(bytes), rName);
                        screenshots.put(rName, uri);
                    }
                    case EXECUTE_SCRIPT -> {
                        var rScript = runContext.render(action.getScript()).as(String.class).orElseThrow(
                            () -> new IllegalArgumentException("script is required for EXECUTE_SCRIPT")
                        );
                        var key = outputKey(runContext, action, actionIndex, "script");
                        warnOnKeyCollision(logger, scriptResults, key, actionType);
                        var result = ((JavascriptExecutor) driver).executeScript(rScript);
                        assertSerializable(result);
                        scriptResults.put(key, result);
                    }
                    case DOWNLOAD -> {
                        var rWaitTimeout = runContext.render(action.getWaitTimeout()).as(Duration.class).orElse(Duration.ofSeconds(30));
                        var rMultiple = runContext.render(action.getMultiple()).as(Boolean.class).orElse(false);

                        // Click the trigger element if a selector is provided.
                        var rSelector = runContext.render(action.getSelector()).as(String.class).orElse(null);
                        if (rSelector != null) {
                            driver.findElement(By.cssSelector(rSelector)).click();
                        }

                        var hasDownloads = (HasDownloads) driver;
                        var stableFiles = pollForStableFiles(hasDownloads, rWaitTimeout);

                        var toFetch = Boolean.TRUE.equals(rMultiple) ? stableFiles : List.of(stableFiles.getLast());
                        var tempDir = Files.createTempDirectory("kestra-selenium-download-");
                        try {
                            for (var fileName : toFetch) {
                                var localFile = tempDir.resolve(fileName).normalize();
                                // Guard against path traversal via Grid-supplied filenames.
                                if (!localFile.startsWith(tempDir)) {
                                    throw new SecurityException("Illegal filename from Grid: " + fileName);
                                }
                                hasDownloads.downloadFile(fileName, tempDir);
                                warnOnKeyCollision(logger, downloads, fileName, actionType);
                                try (var in = Files.newInputStream(localFile)) {
                                    var uri = runContext.storage().putFile(in, fileName);
                                    downloads.put(fileName, uri);
                                    logger.info("Downloaded file '{}' -> {}", fileName, uri);
                                }
                            }
                        } finally {
                            deleteTempDir(tempDir);
                            // Clear the Grid node's download list so a subsequent DOWNLOAD action
                            // does not re-see files from this action. Must run even when a per-file
                            // op throws, otherwise the next DOWNLOAD in the same session will
                            // re-process stale entries.
                            try {
                                hasDownloads.deleteDownloadableFiles();
                            } catch (Exception e) {
                                logger.warn("Failed to clear Grid download list after DOWNLOAD action", e);
                            }
                        }
                    }
                }

                actionIndex++;
            }
        } finally {
            // Emit metric before quitting so it is always recorded, even on failure.
            runContext.metric(Counter.of("actions.count", actionIndex));
            driver.quit();
        }

        return Output.builder()
            .extracted(extracted)
            .screenshots(screenshots)
            .scriptResults(scriptResults)
            .downloads(downloads)
            .build();
    }

    /**
     * Polls until the Grid reports at least one non-temp file that is stable across two
     * consecutive reads. The deadline is checked after each sleep so a file that stabilizes
     * near the boundary is not missed. Throws if no stable result is found after the deadline.
     */
    private List<String> pollForStableFiles(HasDownloads hasDownloads, Duration timeout) throws InterruptedException {
        var deadline = Instant.now().plus(timeout);
        List<String> previousStable = List.of();

        while (true) {
            var stable = hasDownloads.getDownloadableFiles().stream()
                .filter(f -> !TEMP_DOWNLOAD_PATTERN.matcher(f).find())
                .toList();
            if (!stable.isEmpty() && stable.equals(previousStable)) {
                return stable;
            }
            previousStable = stable;
            if (Instant.now().isAfter(deadline)) {
                break;
            }
            Thread.sleep(500);
        }

        throw new IllegalStateException("No stable downloadable files appeared within " + timeout);
    }

    private void deleteTempDir(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder())
                .forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (Exception ignored) {
                    }
                });
        } catch (Exception ignored) {
        }
    }

    private void assertSerializable(Object result) {
        if (containsWebElement(result)) {
            throw new IllegalArgumentException(
                "EXECUTE_SCRIPT must return a JSON-serializable value, not a WebElement"
            );
        }
    }

    private boolean containsWebElement(Object value) {
        if (value instanceof WebElement) return true;
        if (value instanceof List<?> l) return l.stream().anyMatch(this::containsWebElement);
        if (value instanceof Map<?, ?> m) return m.values().stream().anyMatch(this::containsWebElement);
        return false;
    }

    private void warnOnKeyCollision(
        org.slf4j.Logger logger, Map<?, ?> map, String key, ActionType actionType
    ) {
        if (map.containsKey(key)) {
            logger.warn("{}: output key '{}' already exists and will be overwritten", actionType, key);
        }
    }

    private String renderSelector(RunContext runContext, Action action, ActionType actionType) throws Exception {
        return runContext.render(action.getSelector()).as(String.class).orElseThrow(
            () -> new IllegalArgumentException("selector is required for " + actionType)
        );
    }

    private String outputKey(RunContext runContext, Action action, int index, String prefix) throws Exception {
        return runContext.render(action.getId()).as(String.class).orElse(prefix + "_" + index);
    }

    @Getter
    @Builder
    @ToString
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Action {

        @Schema(title = "Action type", description = """
            The browser action to perform. One of:
            NAVIGATE (go to a URL),
            CLICK (click an element by CSS selector),
            TYPE (type text into an element),
            WAIT_FOR (wait until an element is present),
            EXTRACT_TEXT (read text from element(s)),
            SCREENSHOT (capture the viewport),
            EXECUTE_SCRIPT (run JavaScript and capture the return value),
            DOWNLOAD (fetch files from the Selenium Grid node into Kestra internal storage;
            if selector is set, clicks it first to trigger the download;
            most reliable with CHROME or EDGE, which support managed downloads natively).
            """)
        @NotNull
        @PluginProperty(group = "main")
        private ActionType action;

        @Schema(title = "Output key", description = "Key under which EXTRACT_TEXT or EXECUTE_SCRIPT results are stored in the output.")
        @PluginProperty(group = "main")
        private Property<String> id;

        @Schema(title = "URL", description = "Target URL for NAVIGATE.")
        @PluginProperty(group = "main")
        private Property<String> url;

        @Schema(title = "CSS selector", description = "CSS selector for CLICK, TYPE, WAIT_FOR, EXTRACT_TEXT, and DOWNLOAD.")
        @PluginProperty(group = "main")
        private Property<String> selector;

        @Schema(title = "Value", description = "Text to type into the element for TYPE.")
        @PluginProperty(group = "main", secret = true)
        private Property<String> value;

        @Schema(title = "Multiple", description = "When true, EXTRACT_TEXT returns a list of texts from all matching elements. Defaults to false.")
        @PluginProperty(group = "processing")
        private Property<Boolean> multiple;

        @Schema(title = "Screenshot filename", description = "Output filename for SCREENSHOT. Defaults to screenshot.png.")
        @PluginProperty(group = "destination")
        private Property<String> name;

        @Schema(title = "JavaScript", description = "Script body for EXECUTE_SCRIPT. The return value is captured in the output.")
        @PluginProperty(group = "main")
        private Property<String> script;

        @Schema(title = "Wait timeout", description = "Maximum time to wait for WAIT_FOR or DOWNLOAD. Defaults to PT10S for WAIT_FOR, PT30S for DOWNLOAD.")
        @PluginProperty(group = "reliability")
        private Property<Duration> waitTimeout;
    }

    public enum ActionType {
        NAVIGATE,
        CLICK,
        TYPE,
        WAIT_FOR,
        EXTRACT_TEXT,
        SCREENSHOT,
        EXECUTE_SCRIPT,
        DOWNLOAD
    }

    @Builder
    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Output implements io.kestra.core.models.tasks.Output {

        @Schema(title = "Extracted texts", description = "Text values captured by EXTRACT_TEXT actions, keyed by id or extract_<index>.")
        @Builder.Default
        private Map<String, Object> extracted = new HashMap<>();

        @Schema(title = "Screenshots", description = "Internal storage URIs of screenshots, keyed by filename.")
        @Builder.Default
        private Map<String, URI> screenshots = new HashMap<>();

        @Schema(title = "Script results", description = "Return values from EXECUTE_SCRIPT actions, keyed by id or script_<index>.")
        @Builder.Default
        private Map<String, Object> scriptResults = new HashMap<>();

        @Schema(title = "Downloads", description = "Internal storage URIs of files fetched by DOWNLOAD actions, keyed by the original filename.")
        @Builder.Default
        private Map<String, URI> downloads = new HashMap<>();
    }
}
