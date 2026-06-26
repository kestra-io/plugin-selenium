# How to use the Selenium plugin

Browser automation for Kestra. Connects to a Selenium Grid and runs ordered browser actions within a single WebDriver session.

## Task

`Browse` opens one session against a Selenium Grid endpoint, executes the `actions` list in order, and writes results to its output.

## Actions

Supported action types: `NAVIGATE`, `CLICK`, `TYPE`, `WAIT_FOR`, `EXTRACT_TEXT`, `SCREENSHOT`, `EXECUTE_SCRIPT`, and `DOWNLOAD`. Results from `EXTRACT_TEXT` and `EXECUTE_SCRIPT` are stored in the output under the key you set on the action.

## Connection

Point the task at a running Selenium Grid via its endpoint URL. For local runs, start a Grid (for example the standalone Chromium image) and use its address.

## Downloads

Managed downloads use the Selenium Grid `HasDownloads` API (the `se:downloadsEnabled` capability). The plugin waits for stable, non-temporary filenames before transferring files to Kestra's internal storage.
