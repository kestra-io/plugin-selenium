# Kestra Selenium Plugin

## What

Browser automation plugin for Kestra. Connects to a Selenium Grid and runs ordered browser actions
within a single WebDriver session.

Single task class: `io.kestra.plugin.selenium.Browse`.

8 supported action types: `NAVIGATE`, `CLICK`, `TYPE`, `WAIT_FOR`, `EXTRACT_TEXT`, `SCREENSHOT`,
`EXECUTE_SCRIPT`, `DOWNLOAD`.

## Why

Teams need to automate browser interactions (scraping, form submission, file downloads, UI testing)
from Kestra flows without maintaining separate scripting infrastructure. This plugin keeps browser
steps in the same flow as upstream data preparation, approvals, and downstream processing.

## How

### Architecture

Single-module Gradle plugin. All classes under `io.kestra.plugin.selenium`.

The `AbstractSeleniumTask` base class builds the `RemoteWebDriver` against a Selenium Grid endpoint.
`Browse` extends it, iterates the `actions` list, and writes results to its `Output`.

Managed downloads use the Selenium Grid `HasDownloads` API (`se:downloadsEnabled` capability). The
plugin polls for stable (non-temp) filenames before transferring files to Kestra storage.

### Key classes

- `io.kestra.plugin.selenium.AbstractSeleniumTask`: driver construction, connection properties.
- `io.kestra.plugin.selenium.Browse`: the task; contains `Action`, `ActionType`, `Output`.

### Project structure

```
plugin-selenium/
├── src/main/java/io/kestra/plugin/selenium/
│   ├── AbstractSeleniumTask.java
│   ├── Browse.java
│   └── package-info.java
├── src/test/java/io/kestra/plugin/selenium/
│   ├── BrowseIntegrationTest.java   (requires SELENIUM_GRID_URL env var)
│   └── BrowseUnitTest.java          (no browser required)
├── docker-compose-ci.yml            (standalone-chromium for CI)
├── build.gradle
└── README.md
```

### Running tests

```bash
# Unit tests only (no browser needed)
./gradlew test

# With integration tests
export SELENIUM_GRID_URL=http://localhost:4444
docker compose -f docker-compose-ci.yml up -d
./gradlew test --rerun-tasks
```

## References

- https://kestra.io/docs/plugin-developer-guide
- https://kestra.io/docs/plugin-developer-guide/contribution-guidelines
