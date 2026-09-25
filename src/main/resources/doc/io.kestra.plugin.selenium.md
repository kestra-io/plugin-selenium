# How to use the Selenium plugin

Browser automation for Kestra. Connects to a Selenium Grid and runs ordered browser actions within a single WebDriver session.

## Task

`Browse` opens one session against a Selenium Grid endpoint, executes the `actions` list in order, and writes results to its output.

## Actions

Supported action types: `NAVIGATE`, `CLICK`, `TYPE`, `WAIT_FOR`, `EXTRACT_TEXT`, `SCREENSHOT`, `EXECUTE_SCRIPT`, and `DOWNLOAD`. Results from `EXTRACT_TEXT` and `EXECUTE_SCRIPT` are stored in the output under the key you set on the action.

`CLICK`, `TYPE`, and `EXTRACT_TEXT` (when `multiple` is false) wait for the target element using `waitTimeout` (default `PT10S`) and fail with a clear error if it never appears. `EXTRACT_TEXT` with `multiple: true` waits for at least one matching element but falls back to an empty list with a warning on timeout, rather than failing. `WAIT_FOR` accepts a `condition` of `PRESENT` (default), `VISIBLE`, or `CLICKABLE`. `TYPE` accepts `clear: true` to clear the field before sending keys.

## Connection

Point the task at a running Selenium Grid via its endpoint URL. For local runs, start a Grid (for example the standalone Chromium image) and use its address. If the Grid requires HTTP basic auth, set both `username` and `password` (a secret).

## Downloads

Managed downloads use the Selenium Grid `HasDownloads` API (the `se:downloadsEnabled` capability, only set on the session when a `DOWNLOAD` action is present). The plugin snapshots the Grid's file list before triggering the download, then waits for stable, non-temporary filenames that are new since that snapshot before transferring them to Kestra's internal storage. If more than one new file appears and `multiple` is not `true`, the action fails and lists the file names.
