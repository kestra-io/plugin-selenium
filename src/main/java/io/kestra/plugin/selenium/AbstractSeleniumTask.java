package io.kestra.plugin.selenium;

import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.runners.RunContext;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;
import org.openqa.selenium.MutableCapabilities;
import org.openqa.selenium.UsernameAndPassword;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.edge.EdgeOptions;
import org.openqa.selenium.firefox.FirefoxOptions;
import org.openqa.selenium.remote.HttpCommandExecutor;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.remote.http.ClientConfig;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
public abstract class AbstractSeleniumTask extends Task {

    @Schema(
        title = "Selenium Grid or WebDriver endpoint",
        description = """
            URL of the remote WebDriver, e.g. http://localhost:4444.

            Security note: the Grid node will open any URL passed to a NAVIGATE action, including
            addresses reachable only from the Grid host's network (internal services, cloud metadata
            endpoints, etc.). Restrict Grid egress with network policy when running in shared or
            multi-tenant environments.
            """
    )
    @NotNull
    @PluginProperty(group = "connection")
    private Property<String> remoteUrl;

    @Schema(title = "Browser", description = "Browser to use. Defaults to CHROME.")
    @PluginProperty(group = "connection")
    private Property<BrowserType> browser;

    @Schema(title = "Headless", description = "Run browser without a display. Defaults to true.")
    @PluginProperty(group = "connection")
    private Property<Boolean> headless;

    @Schema(title = "Page load timeout", description = "Maximum time to wait for a page to load. Defaults to PT30S.")
    @PluginProperty(group = "connection")
    private Property<Duration> pageLoadTimeout;

    @Schema(title = "Grid username", description = "Username for HTTP basic auth against the Grid endpoint, if required. Must be set together with password.")
    @PluginProperty(group = "connection")
    private Property<String> username;

    @Schema(title = "Grid password", description = "Password for HTTP basic auth against the Grid endpoint, if required. Must be set together with username.")
    @PluginProperty(group = "connection", secret = true)
    @ToString.Exclude
    private Property<String> password;

    @Schema(
        title = "Extra capabilities",
        description = """
            Additional browser capabilities merged into the options before the session is created.
            Values are passed unvalidated to the browser and Grid; only set these from trusted sources.
            """
    )
    @PluginProperty(group = "advanced")
    private Property<Map<String, Object>> capabilities;

    protected RemoteWebDriver buildDriver(RunContext runContext, boolean downloadsEnabled) throws Exception {
        var rUrl = runContext.render(remoteUrl).as(String.class).orElseThrow(
            () -> new IllegalArgumentException("remoteUrl is required")
        );
        var rBrowser = runContext.render(browser).as(BrowserType.class).orElse(BrowserType.CHROME);
        var rHeadless = runContext.render(headless).as(Boolean.class).orElse(true);
        var rTimeout = runContext.render(pageLoadTimeout).as(Duration.class).orElse(Duration.ofSeconds(30));
        var rCaps = runContext.render(capabilities).asMap(String.class, Object.class);
        var rUsername = runContext.render(username).as(String.class).orElse(null);
        var rPassword = runContext.render(password).as(String.class).orElse(null);
        if ((rUsername == null) != (rPassword == null)) {
            throw new IllegalArgumentException("Both username and password must be set for Grid basic auth, or neither.");
        }

        var gridUri = URI.create(rUrl);
        var clientConfig = ClientConfig.defaultConfig().baseUri(gridUri);
        if (rUsername != null) {
            clientConfig = clientConfig.authenticateAs(new UsernameAndPassword(rUsername, rPassword));
        }

        MutableCapabilities opts = switch (rBrowser) {
            case CHROME -> {
                var o = new ChromeOptions();
                if (rHeadless) {
                    o.addArguments("--headless=new");
                }
                o.addArguments("--no-sandbox", "--disable-dev-shm-usage");
                yield o;
            }
            case FIREFOX -> {
                var o = new FirefoxOptions();
                if (rHeadless) {
                    o.addArguments("-headless");
                }
                yield o;
            }
            case EDGE -> {
                var o = new EdgeOptions();
                if (rHeadless) {
                    o.addArguments("--headless=new");
                }
                yield o;
            }
        };

        if (!rCaps.isEmpty()) {
            rCaps.forEach(opts::setCapability);
        }
        if (downloadsEnabled) {
            // Required for Selenium Grid managed downloads: the node streams the file back to the client
            // via the Grid relay instead of writing to the container filesystem. Only set when a
            // DOWNLOAD action is present: some bare WebDriver endpoints reject unknown capabilities.
            opts.setCapability("se:downloadsEnabled", true);
        }
        // Disable BiDi/CDP websocket: the builder's augmentation opens a websocket to the node's
        // advertised address (often an internal Docker IP) which is unreachable from the host.
        // Using HttpCommandExecutor directly bypasses that augmentation entirely.
        opts.setCapability("webSocketUrl", false);
        var executor = new HttpCommandExecutor(clientConfig);
        var driver = new RemoteWebDriver(executor, opts);
        // The session is live once the constructor returns; quit it if any further
        // setup fails so we never leak a session on the Grid.
        try {
            driver.manage().timeouts().pageLoadTimeout(rTimeout);
        } catch (Exception e) {
            driver.quit();
            throw e;
        }
        return driver;
    }

    public enum BrowserType {
        CHROME,
        FIREFOX,
        EDGE
    }
}
