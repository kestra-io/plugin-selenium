package io.kestra.plugin.selenium;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Metric;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.executions.metrics.Counter;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.JacksonMapper;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.WebDriverException;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
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
    static final Pattern TEMP_DOWNLOAD_PATTERN = Pattern.compile(
        "\\.crdownload$|\\.part$|\\.tmp$|^\\.com\\.google\\.Chrome\\.|^\\.org\\.chromium\\.Chromium\\.|^\\.download$"
    );

    private static final long DEFAULT_MAX_OUTPUT_SIZE = 1_048_576L;
    private static final int MAX_SCRIPT_RESULT_DEPTH = 32;
    private static final ObjectMapper MAPPER = JacksonMapper.ofJson();

    @Schema(title = "Actions", description = "Ordered list of browser actions to execute within a single session.")
    @NotNull
    @NotEmpty
    @PluginProperty(group = "main")
    private List<@Valid Action> actions;

    @Schema(title = "Maximum output size", description = """
        Upper bound, in bytes, on the combined JSON-serialized size of `extracted` and `scriptResults`
        accumulated so far. Checked after each EXTRACT_TEXT and EXECUTE_SCRIPT action; exceeding it fails
        the task instead of growing the output further. Defaults to 1048576 (1 MB). Use SCREENSHOT or a
        narrower selector to capture large content instead, or raise this value if larger output is expected.
        """)
    @Builder.Default
    @PluginProperty(group = "advanced")
    private Property<@Min(1) Long> maxOutputSize = Property.ofValue(DEFAULT_MAX_OUTPUT_SIZE);

    // Excluded from schema/JSON: it is runtime state (the live session), not a declarative task property.
    @JsonIgnore
    @Getter(AccessLevel.NONE)
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    @Builder.Default
    private final transient AtomicReference<RemoteWebDriver> activeDriver = new AtomicReference<>();

    @Override
    public Output run(RunContext runContext) throws Exception {
        var logger = runContext.logger();

        Map<String, Object> extracted = new HashMap<>();
        Map<String, URI> screenshots = new HashMap<>();
        Map<String, Object> scriptResults = new HashMap<>();
        Map<String, URI> downloads = new HashMap<>();
        var actionIndex = 0;
        var rMaxOutputSize = runContext.render(maxOutputSize).as(Long.class).orElse(DEFAULT_MAX_OUTPUT_SIZE);

        RemoteWebDriver driver = null;
        try {
            var downloadsEnabled = actions.stream().anyMatch(a -> a.getAction() == ActionType.DOWNLOAD);
            try {
                driver = buildDriver(runContext, downloadsEnabled);
                activeDriver.set(driver);
            } catch (WebDriverException e) {
                throw new IllegalStateException("Failed to create a browser session on the Grid: " + shortMessage(e));
            }
            for (var action : actions) {
                var actionType = action.getAction();
                logger.info("Executing action [{}]: {}", actionIndex, actionType);

                try {
                    switch (actionType) {
                        case NAVIGATE -> {
                            var rUrl = runContext.render(action.getUrl()).as(String.class).orElseThrow(
                                () -> new IllegalArgumentException("url is required for NAVIGATE")
                            );
                            driver.get(rUrl);
                        }
                        case CLICK -> {
                            var rSelector = renderSelector(runContext, action, actionType);
                            var rWaitTimeout = runContext.render(action.getWaitTimeout()).as(Duration.class).orElse(Duration.ofSeconds(10));
                            try {
                                new WebDriverWait(driver, rWaitTimeout)
                                    .until(ExpectedConditions.elementToBeClickable(By.cssSelector(rSelector)))
                                    .click();
                            } catch (TimeoutException e) {
                                throw new IllegalStateException(
                                    "CLICK: element not found for selector '" + rSelector + "' within " + rWaitTimeout
                                );
                            }
                        }
                        case TYPE -> {
                            var rSelector = renderSelector(runContext, action, actionType);
                            var rValue = runContext.render(action.getValue()).as(String.class).orElseThrow(
                                () -> new IllegalArgumentException("value is required for TYPE")
                            );
                            var rClear = runContext.render(action.getClear()).as(Boolean.class).orElse(false);
                            var rWaitTimeout = runContext.render(action.getWaitTimeout()).as(Duration.class).orElse(Duration.ofSeconds(10));
                            WebElement element;
                            try {
                                element = new WebDriverWait(driver, rWaitTimeout)
                                    .until(ExpectedConditions.elementToBeClickable(By.cssSelector(rSelector)));
                            } catch (TimeoutException e) {
                                throw new IllegalStateException(
                                    "TYPE: element not found for selector '" + rSelector + "' within " + rWaitTimeout
                                );
                            }
                            if (Boolean.TRUE.equals(rClear)) {
                                element.clear();
                            }
                            element.sendKeys(rValue);
                        }
                        case WAIT_FOR -> {
                            var rSelector = renderSelector(runContext, action, actionType);
                            var rWaitTimeout = runContext.render(action.getWaitTimeout()).as(Duration.class).orElse(Duration.ofSeconds(10));
                            var rCondition = runContext.render(action.getCondition()).as(WaitCondition.class).orElse(WaitCondition.PRESENT);
                            var locator = By.cssSelector(rSelector);
                            var wait = new WebDriverWait(driver, rWaitTimeout);
                            switch (rCondition) {
                                case PRESENT -> wait.until(ExpectedConditions.presenceOfElementLocated(locator));
                                case VISIBLE -> wait.until(ExpectedConditions.visibilityOfElementLocated(locator));
                                case CLICKABLE -> wait.until(ExpectedConditions.elementToBeClickable(locator));
                            }
                        }
                        case EXTRACT_TEXT -> {
                            var rSelector = renderSelector(runContext, action, actionType);
                            var rMultiple = runContext.render(action.getMultiple()).as(Boolean.class).orElse(false);
                            var rWaitTimeout = runContext.render(action.getWaitTimeout()).as(Duration.class).orElse(Duration.ofSeconds(10));
                            var key = outputKey(runContext, action, actionIndex, "extract");
                            warnOnKeyCollision(logger, extracted, key, actionType);
                            if (Boolean.TRUE.equals(rMultiple)) {
                                try {
                                    new WebDriverWait(driver, rWaitTimeout)
                                        .until(ExpectedConditions.presenceOfElementLocated(By.cssSelector(rSelector)));
                                } catch (TimeoutException e) {
                                    logger.warn("EXTRACT_TEXT: no elements matched selector '{}' within {}", rSelector, rWaitTimeout);
                                }
                                var elements = driver.findElements(By.cssSelector(rSelector));
                                extracted.put(key, elements.stream().map(WebElement::getText).toList());
                            } else {
                                try {
                                    var element = new WebDriverWait(driver, rWaitTimeout)
                                        .until(ExpectedConditions.visibilityOfElementLocated(By.cssSelector(rSelector)));
                                    extracted.put(key, element.getText());
                                } catch (TimeoutException e) {
                                    throw new IllegalStateException(
                                        "EXTRACT_TEXT: element not found for selector '" + rSelector + "' within " + rWaitTimeout
                                    );
                                }
                            }
                            checkOutputSize(actionType, rMaxOutputSize, extracted, scriptResults);
                        }
                        case SCREENSHOT -> {
                            var rName = runContext.render(action.getName()).as(String.class).orElse("screenshot_" + actionIndex + ".png");
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
                            checkOutputSize(actionType, rMaxOutputSize, extracted, scriptResults);
                        }
                        case DOWNLOAD -> {
                            var rWaitTimeout = runContext.render(action.getWaitTimeout()).as(Duration.class).orElse(Duration.ofSeconds(30));
                            var rMultiple = runContext.render(action.getMultiple()).as(Boolean.class).orElse(false);

                            // Clear leftovers from an earlier CLICK-triggered download before snapshotting,
                            // so this action never picks up a file it did not trigger.
                            try {
                                driver.deleteDownloadableFiles();
                            } catch (WebDriverException e) {
                                throw managedDownloadsRequired(e);
                            }

                            // Snapshot before the click so only files added by this action are fetched.
                            // A download started by an earlier CLICK could still be in flight and land here.
                            List<String> before;
                            try {
                                before = driver.getDownloadableFiles();
                            } catch (WebDriverException e) {
                                throw managedDownloadsRequired(e);
                            }

                            // Click the trigger element if a selector is provided.
                            var rSelector = runContext.render(action.getSelector()).as(String.class).orElse(null);
                            if (rSelector != null) {
                                try {
                                    new WebDriverWait(driver, rWaitTimeout)
                                        .until(ExpectedConditions.elementToBeClickable(By.cssSelector(rSelector)))
                                        .click();
                                } catch (TimeoutException e) {
                                    throw new IllegalStateException(
                                        "DOWNLOAD: element not found for selector '" + rSelector + "' within " + rWaitTimeout
                                    );
                                }
                            }

                            var newStableFiles = pollForNewStableFiles(driver, before, rWaitTimeout);
                            if (!Boolean.TRUE.equals(rMultiple) && newStableFiles.size() > 1) {
                                throw new IllegalStateException(
                                    "DOWNLOAD found " + newStableFiles.size() + " new files (" + newStableFiles
                                        + ") but multiple is false. Set multiple: true to fetch all of them."
                                );
                            }
                            var toFetch = Boolean.TRUE.equals(rMultiple) ? newStableFiles : List.of(newStableFiles.getFirst());
                            var tempDir = Files.createTempDirectory("kestra-selenium-download-");
                            try {
                                for (var fileName : toFetch) {
                                    var localFile = tempDir.resolve(fileName).normalize();
                                    // Guard against path traversal via Grid-supplied filenames.
                                    if (!localFile.startsWith(tempDir)) {
                                        throw new SecurityException("Illegal filename from Grid: " + fileName);
                                    }
                                    driver.downloadFile(fileName, tempDir);
                                    warnOnKeyCollision(logger, downloads, fileName, actionType);
                                    try (var in = Files.newInputStream(localFile)) {
                                        var uri = runContext.storage().putFile(in, fileName);
                                        downloads.put(fileName, uri);
                                        logger.info("Downloaded file '{}' -> {}", fileName, uri);
                                    }
                                }
                            } finally {
                                deleteTempDir(logger, tempDir);
                                // Clear the Grid node's download list so a subsequent DOWNLOAD action
                                // does not re-see files from this action. Must run even when a per-file
                                // op throws, otherwise the next DOWNLOAD in the same session will
                                // re-process stale entries.
                                try {
                                    driver.deleteDownloadableFiles();
                                } catch (Exception e) {
                                    logger.warn("Failed to clear Grid download list after DOWNLOAD action: {}", shortMessage(e));
                                }
                            }
                        }
                    }
                } catch (WebDriverException e) {
                    // Selenium messages append build, system and capability dumps (internal Grid IPs, session id).
                    throw new IllegalStateException(actionType + " failed at action [" + actionIndex + "]: " + shortMessage(e));
                }

                actionIndex++;
            }
        } finally {
            // Emit metric before quitting so it is always recorded, even on failure.
            runContext.metric(Counter.of("actions.count", actionIndex));
            // getAndSet(null) races kill(), which also quits via activeDriver: whichever runs first
            // wins the reference and the other becomes a no-op, so the session is quit exactly once.
            Optional.ofNullable(activeDriver.getAndSet(null)).ifPresent(RemoteWebDriver::quit);
        }

        return Output.builder()
            .extracted(extracted)
            .screenshots(screenshots)
            .scriptResults(scriptResults)
            .downloads(downloads)
            .build();
    }

    // Quits the live session so a killed or timed-out execution does not leak it on the Grid.
    // May run on a different thread than run(); see the getAndSet(null) race note in its finally block.
    @Override
    public void kill() {
        Optional.ofNullable(activeDriver.getAndSet(null)).ifPresent(driver -> {
            try {
                driver.quit();
            } catch (Exception e) {
                LoggerFactory.getLogger(Browse.class).warn("Failed to quit browser session on kill: {}", shortMessage(e));
            }
        });
    }

    // Grid's HasDownloads API has no completion event, so sleep-polling is the only way to detect a finished download.
    // Polls until the new, non-temp file set is non-empty and identical across two reads.
    private List<String> pollForNewStableFiles(RemoteWebDriver driver, List<String> before, Duration timeout) throws InterruptedException {
        var deadline = Instant.now().plus(timeout);
        List<String> previousStable = List.of();

        while (true) {
            var stable = selectNewStableFiles(before, driver.getDownloadableFiles());
            if (!stable.isEmpty() && stable.equals(previousStable)) {
                return stable;
            }
            previousStable = stable;
            if (Instant.now().isAfter(deadline)) {
                break;
            }
            Thread.sleep(500);
        }

        throw new IllegalStateException("No new stable downloadable file appeared within " + timeout);
    }

    // New, finished files only, sorted so stability does not depend on Grid listing order.
    static List<String> selectNewStableFiles(List<String> before, List<String> current) {
        var beforeSet = Set.copyOf(before);
        return current.stream()
            .filter(f -> !beforeSet.contains(f))
            .filter(f -> !TEMP_DOWNLOAD_PATTERN.matcher(f).find())
            .sorted()
            .toList();
    }

    // First line of the raw Selenium message, without its build/system/capabilities footer.
    static String shortMessage(Exception e) {
        var raw = e instanceof WebDriverException w ? w.getRawMessage() : e.getMessage();
        if (raw == null || raw.isBlank()) {
            return e.getClass().getSimpleName();
        }
        return raw.lines().findFirst().orElse(raw).strip();
    }

    private static IllegalStateException managedDownloadsRequired(WebDriverException e) {
        return new IllegalStateException(
            "DOWNLOAD requires the Grid node to have managed downloads enabled "
                + "(SE_NODE_ENABLE_MANAGED_DOWNLOADS=true): " + shortMessage(e)
        );
    }

    private void checkOutputSize(ActionType actionType, long rMaxOutputSize, Map<String, Object> extracted, Map<String, Object> scriptResults) throws Exception {
        var currentSize = (long) MAPPER.writeValueAsBytes(extracted).length + MAPPER.writeValueAsBytes(scriptResults).length;
        if (currentSize > rMaxOutputSize) {
            throw new IllegalStateException(
                actionType + ": accumulated EXTRACT_TEXT/EXECUTE_SCRIPT output is " + currentSize
                    + " bytes, exceeding maxOutputSize (" + rMaxOutputSize + " bytes). Use SCREENSHOT or a "
                    + "narrower selector to capture large content, or raise maxOutputSize."
            );
        }
    }

    private void deleteTempDir(Logger logger, Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder())
                .forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (Exception e) {
                        logger.warn("Failed to delete temp download file '{}': {}", p, e.getMessage());
                    }
                });
        } catch (Exception e) {
            logger.warn("Failed to clean up temp download directory '{}': {}", dir, e.getMessage());
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
        return containsWebElement(value, 0);
    }

    private boolean containsWebElement(Object value, int depth) {
        if (depth > MAX_SCRIPT_RESULT_DEPTH) {
            throw new IllegalArgumentException(
                "EXECUTE_SCRIPT result nested too deeply (max depth " + MAX_SCRIPT_RESULT_DEPTH + ")"
            );
        }
        if (value instanceof WebElement) return true;
        if (value instanceof List<?> l) return l.stream().anyMatch(v -> containsWebElement(v, depth + 1));
        if (value instanceof Map<?, ?> m) return m.values().stream().anyMatch(v -> containsWebElement(v, depth + 1));
        return false;
    }

    private void warnOnKeyCollision(
        Logger logger, Map<?, ?> map, String key, ActionType actionType
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
            if selector is set, waits for it to be clickable and clicks it first to trigger the download;
            requires the Grid node to have managed downloads enabled, SE_NODE_ENABLE_MANAGED_DOWNLOADS=true;
            trigger the download through this selector rather than a preceding CLICK action, since DOWNLOAD
            clears the Grid node's download list right before it snapshots it, and a download already
            started by an earlier CLICK could still be in flight and picked up inconsistently).
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
        @ToString.Exclude
        private Property<String> value;

        @Schema(title = "Clear before typing", description = "When true, TYPE clears the element's existing value before sending keys. Defaults to false.")
        @PluginProperty(group = "processing")
        private Property<Boolean> clear;

        @Schema(title = "Multiple", description = "When true, EXTRACT_TEXT returns a list of texts from all matching elements. Defaults to false.")
        @PluginProperty(group = "processing")
        private Property<Boolean> multiple;

        @Schema(title = "Screenshot filename", description = "Output filename for SCREENSHOT. Defaults to screenshot_<actionIndex>.png.")
        @PluginProperty(group = "destination")
        private Property<String> name;

        @Schema(title = "JavaScript", description = "Script body for EXECUTE_SCRIPT. The return value is captured in the output.")
        @PluginProperty(group = "main")
        private Property<String> script;

        @Schema(title = "Wait condition", description = """
            Element state to wait for in WAIT_FOR: PRESENT (exists in the DOM), VISIBLE (also
            displayed), or CLICKABLE (visible and enabled). Defaults to PRESENT.
            """)
        @PluginProperty(group = "reliability")
        private Property<WaitCondition> condition;

        @Schema(title = "Wait timeout", description = """
            Maximum time to wait for the target element or file. Applies to CLICK and TYPE
            (element clickable, default PT10S), WAIT_FOR (default PT10S), EXTRACT_TEXT (element
            visible when multiple is false, presence of at least one element when true, default
            PT10S), and DOWNLOAD (its own selector's element clickable, then the new stable file,
            both using the same value, default PT30S).
            """)
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

    public enum WaitCondition {
        PRESENT,
        VISIBLE,
        CLICKABLE
    }

    @Builder
    @Getter
    @NoArgsConstructor
    @AllArgsConstructor(access = AccessLevel.PACKAGE)
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
