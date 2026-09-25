<p align="center">
  <a href="https://www.kestra.io">
    <img src="https://kestra.io/banner.png" alt="Kestra workflow orchestrator" />
  </a>
</p>

<h1 align="center" style="border-bottom: none">
    Kestra Selenium Plugin
</h1>

<div align="center">
 <a href="https://github.com/kestra-io/kestra/releases"><img src="https://img.shields.io/github/tag-pre/kestra-io/kestra.svg?color=blueviolet" alt="Last Version" /></a>
  <a href="https://github.com/kestra-io/kestra/blob/develop/LICENSE"><img src="https://img.shields.io/github/license/kestra-io/kestra?color=blueviolet" alt="License" /></a>
  <a href="https://github.com/kestra-io/kestra/stargazers"><img src="https://img.shields.io/github/stars/kestra-io/kestra?color=blueviolet&logo=github" alt="Github star" /></a>
</div>

# Kestra Selenium Plugin

Browser automation via Selenium Grid over the WebDriver protocol. Connects to a remote Selenium Grid, runs a sequence of browser actions, and stores outputs (text, screenshots, downloaded files) in Kestra internal storage.

## Task: Browse

Single task with 8 supported actions:

| Action | Description |
|---|---|
| `NAVIGATE` | Open a URL in the browser. |
| `CLICK` | Click an element by CSS selector. |
| `TYPE` | Send keystrokes to an element. |
| `WAIT_FOR` | Wait until an element matching a CSS selector is present. |
| `EXTRACT_TEXT` | Read text from one or more elements. |
| `SCREENSHOT` | Capture the viewport and store the PNG in Kestra storage. |
| `EXECUTE_SCRIPT` | Run JavaScript and capture the return value. |
| `DOWNLOAD` | Click a download trigger (optional) and retrieve the resulting file(s) from the Grid node into Kestra storage. Requires the Grid node to have managed downloads enabled (`SE_NODE_ENABLE_MANAGED_DOWNLOADS=true`). |

`CLICK`, `TYPE`, and `EXTRACT_TEXT` (single element) wait for the target element using `waitTimeout`
(default `PT10S`) before failing with a clear error. `EXTRACT_TEXT` with `multiple: true` waits for
at least one matching element but falls back to an empty list with a warning if none appear in time.
`WAIT_FOR` accepts a `condition` (`PRESENT`, `VISIBLE`, `CLICKABLE`, default `PRESENT`). `TYPE`
accepts `clear: true` to clear the field before sending keys.

`DOWNLOAD` only considers files that appear after the action starts, so a stale file already on the
Grid node is never picked up. If more than one new file appears and `multiple` is not set to `true`,
the task fails and lists the file names.

## Connection properties

| Property | Required | Default | Description |
|---|---|---|---|
| `remoteUrl` | yes | | Selenium Grid WebDriver URL. |
| `browser` | no | `CHROME` | Browser type: `CHROME`, `FIREFOX`, `EDGE`. |
| `headless` | no | `true` | Run without a display. |
| `pageLoadTimeout` | no | `PT30S` | Maximum time to wait for page load. |
| `username` / `password` | no | | HTTP basic auth for the Grid endpoint. Both must be set together, or neither. `password` is masked as a secret. |
| `capabilities` | no | | Extra capabilities merged into browser options. Values are passed unvalidated; only set from trusted sources. |

## Examples

Navigate to a page and extract text:

```yaml
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
```

Download a file and store it in Kestra:

```yaml
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
```

## Security notes

**SSRF**: The Grid node opens any URL passed to `NAVIGATE`, including addresses reachable only from
the Grid host's network (internal services, cloud metadata endpoints, etc.). Restrict Grid egress
with network policy when running in shared or multi-tenant environments.

**Capabilities**: values in `capabilities` are passed unvalidated to the browser and Grid. Only set
them from trusted sources.

**TYPE value**: the `value` field for TYPE actions is marked `secret = true` and will be masked in
logs and the Kestra UI. Use it for passwords and keys.

## Running integration tests

Start the Grid:

```bash
docker compose -f docker-compose-ci.yml up -d
```

Run tests against it:

```bash
export SELENIUM_GRID_URL=http://localhost:4444
./gradlew test --rerun-tasks
```

Integration tests are gated on the `SELENIUM_GRID_URL` environment variable and are skipped when it
is not set.

## License

Apache 2.0 © [Kestra Technologies](https://kestra.io)
