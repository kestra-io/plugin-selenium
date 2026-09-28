# How to use the Selenium plugin

Browser automation for Kestra. Connects to a Selenium Grid and runs ordered browser actions within a single WebDriver session.

## Authentication

Set `remoteUrl` (required) to the Selenium Grid or WebDriver endpoint, e.g. `http://localhost:4444`. Only `http` and `https` schemes are accepted.

If the Grid requires HTTP basic auth, set both `username` and `password` (or neither); `password` is masked as a secret in logs and the UI. Store it in a [secret](https://kestra.io/docs/concepts/secret) and reference it with `{{ secret('SELENIUM_GRID_PASSWORD') }}`. A locally started Grid typically needs neither; hosted Grids from commercial providers typically need both `remoteUrl` and `username`/`password`.

You can set `remoteUrl`, `username`, and `password` once via [plugin defaults](https://kestra.io/docs/workflow-components/plugin-defaults) instead of repeating them on every task.

## Tasks

`Browse` opens one session against the Grid, executes the `actions` list in order, and writes results to its output.

Supported action types: `NAVIGATE`, `CLICK`, `TYPE`, `WAIT_FOR`, `EXTRACT_TEXT`, `SCREENSHOT`, `EXECUTE_SCRIPT`, and `DOWNLOAD`. Results from `EXTRACT_TEXT` and `EXECUTE_SCRIPT` are stored in the output under the key you set on the action.

`CLICK`, `TYPE`, and `EXTRACT_TEXT` (when `multiple` is false) wait for the target element using `waitTimeout` (default `PT10S`) and fail with a clear error if it never appears. `EXTRACT_TEXT` with `multiple: true` waits for at least one matching element but falls back to an empty list with a warning on timeout, rather than failing. `WAIT_FOR` accepts a `condition` of `PRESENT` (default), `VISIBLE`, or `CLICKABLE`. `TYPE` accepts `clear: true` to clear the field before sending keys.

`maxOutputSize` (default 1048576 bytes, 1 MB) caps the combined JSON-serialized size of `extracted` and `scriptResults` accumulated across actions; exceeding it fails the task instead of growing the output further. Use `SCREENSHOT` or a narrower selector to capture large content instead, or raise the limit.

Killing the execution (or a worker timeout) quits the live browser session on the Grid instead of leaving it running until it times out on its own.

### Downloads

Managed downloads use the Selenium Grid `HasDownloads` API (the `se:downloadsEnabled` capability, only set on the session when a `DOWNLOAD` action is present). `DOWNLOAD` clears the Grid node's downloadable-files list and takes a fresh snapshot immediately before triggering the download, waits for its own `selector` to be clickable before clicking it, then waits for stable, non-temporary filenames that are new since that snapshot before transferring them to Kestra's internal storage. If more than one new file appears and `multiple` is not `true`, the action fails and lists the file names.

Trigger the download through `DOWNLOAD`'s own `selector` rather than a preceding `CLICK` action: a download already started by an earlier click may still be in flight and get picked up inconsistently.

Selenium loads the whole downloaded file into memory before it reaches Kestra storage; there is no size cap on it, so avoid `DOWNLOAD` for very large files.

## Browser

`browser` selects `CHROME` (default), `FIREFOX`, or `EDGE`. Chrome runs with `--no-sandbox`, required to launch in most containerized Grid nodes.
