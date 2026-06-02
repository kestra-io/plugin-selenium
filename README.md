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
| `DOWNLOAD` | Click a download trigger (optional) and retrieve the resulting file(s) from the Grid node into Kestra storage. |

## Connection properties

| Property | Required | Default | Description |
|---|---|---|---|
| `remoteUrl` | yes | | Selenium Grid WebDriver URL. |
| `browser` | no | `CHROME` | Browser type: `CHROME`, `FIREFOX`, `EDGE`. |
| `headless` | no | `true` | Run without a display. |
| `pageLoadTimeout` | no | `PT30S` | Maximum time to wait for page load. |
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
