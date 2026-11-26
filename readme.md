<br />
<img src="assets/images/logo-transparent.png" width="80"/>

### Bruno - Opensource IDE for exploring and testing APIs.

[![GitHub version](https://badge.fury.io/gh/usebruno%2Fbruno.svg)](https://badge.fury.io/gh/usebruno%2Fbruno)
[![CI](https://github.com/usebruno/bruno/actions/workflows/tests.yml/badge.svg?branch=main)](https://github.com/usebruno/bruno/actions/workflows/tests.yml)
[![Commit Activity](https://img.shields.io/github/commit-activity/m/usebruno/bruno)](https://github.com/usebruno/bruno/pulse)
[![X](https://img.shields.io/twitter/follow/use_bruno?style=social&logo=x)](https://twitter.com/use_bruno)
[![Website](https://img.shields.io/badge/Website-Visit-blue)](https://www.usebruno.com)
[![Download](https://img.shields.io/badge/Download-Latest-brightgreen)](https://www.usebruno.com/downloads)

**English**
| [Українська](docs/readme/readme_ua.md)
| [Русский](docs/readme/readme_ru.md)
| [Türkçe](docs/readme/readme_tr.md)
| [Deutsch](docs/readme/readme_de.md)
| [Français](docs/readme/readme_fr.md)
| [Português (BR)](docs/readme/readme_pt_br.md)
| [한국어](docs/readme/readme_kr.md)
| [বাংলা](docs/readme/readme_bn.md)
| [Español](docs/readme/readme_es.md)
| [Italiano](docs/readme/readme_it.md)
| [Română](docs/readme/readme_ro.md)
| [Polski](docs/readme/readme_pl.md)
| [简体中文](docs/readme/readme_cn.md)
| [正體中文](docs/readme/readme_zhtw.md)
| [العربية](docs/readme/readme_ar.md)
| [日本語](docs/readme/readme_ja.md)
| [ქართული](docs/readme/readme_ka.md)
| [Nederlands](docs/readme/readme_nl.md)
| [فارسی](docs/readme/readme_fa.md)
| [हिन्दी](docs/readme/readme_hi.md)

Bruno is a new and innovative API client, aimed at revolutionizing the status quo represented by Postman and similar tools out there.

Bruno stores your collections directly in a folder on your filesystem. We use a plain text markup language, Bru, to save information about API requests.

You can use Git or any version control of your choice to collaborate over your API collections.

Bruno is offline-only. There are no plans to add cloud-sync to Bruno, ever. We value your data privacy and believe it should stay on your device. Read our long-term vision [here](https://github.com/usebruno/bruno/discussions/269)

[Download Bruno](https://www.usebruno.com/downloads)

📢 Watch our recent talk at India FOSS 3.0 Conference [here](https://www.youtube.com/watch?v=7bSMFpbcPiY)

![bruno](assets/images/landing-2-dark.png#gh-light-mode-only)
![bruno](assets/images/landing-2-light.png#gh-dark-mode-only) <br /><br />

## Commercial Versions ✨

Majority of our features are free and open source.
We strive to strike a harmonious balance between [open-source principles and sustainability](https://github.com/usebruno/bruno/discussions/269)

You can explore our [paid versions](https://www.usebruno.com/pricing) to see if there are additional features that you or your team may find useful! <br/>

## Table of Contents

- [Installation](#installation)
- [Bruno CLI](#bruno-cli)
- [Run with Docker](#run-with-docker)
- [Features](#features)
  - [Visual API Flows](#visual-api-flows)
  - [Run across multiple platforms 🖥️](#run-across-multiple-platforms-%EF%B8%8F)
  - [Collaborate via Git 👩‍💻🧑‍💻](#collaborate-via-git-%E2%80%8D%E2%80%8D)
- [Important Links 📌](#important-links-)
- [Showcase 🎥](#showcase-)
- [Share Testimonials 📣](#share-testimonials-)
- [Publishing to New Package Managers](#publishing-to-new-package-managers)
- [Stay in touch 🌐](#stay-in-touch-)
- [Trademark](#trademark)
- [Contribute 👩‍💻🧑‍💻](#contribute-%E2%80%8D%E2%80%8D)
- [Authors](#authors)
- [License 📄](#license-)

## Installation

Bruno is available as binary download [on our website](https://www.usebruno.com/downloads) for Mac, Windows and Linux.

You can also install Bruno via package managers like Homebrew, Chocolatey, Scoop, Snap, Flatpak and Apt.

```sh
# On Mac via Homebrew
brew install bruno

# On Windows via Chocolatey
choco install bruno

# On Windows via Scoop
scoop bucket add extras
scoop install bruno

# On Windows via winget
winget install Bruno.Bruno

# On Linux via Snap
snap install bruno

# On Linux via Flatpak
flatpak install com.usebruno.Bruno

# On Arch Linux via AUR
yay -S bruno

# On Linux via Apt
sudo mkdir -p /etc/apt/keyrings
sudo apt update && sudo apt install gpg curl
curl -fsSL "https://keyserver.ubuntu.com/pks/lookup?op=get&search=0x9FA6017ECABE0266" \
  | gpg --dearmor \
  | sudo tee /etc/apt/keyrings/bruno.gpg > /dev/null
sudo chmod 644 /etc/apt/keyrings/bruno.gpg
echo "deb [arch=amd64 signed-by=/etc/apt/keyrings/bruno.gpg] http://debian.usebruno.com/ bruno stable" \
  | sudo tee /etc/apt/sources.list.d/bruno.list
sudo apt update && sudo apt install bruno
```

## Bruno CLI

The **Bruno CLI** lets you run your API collections from the command line, which is ideal for automated testing and CI/CD pipelines. Install it from npm using the package manager of your choice:

```sh
npm install -g @usebruno/cli
```

Navigate to the directory containing your collection and run it:

```sh
# Run every request in the collection
bru run

# Run a single request
bru run request.bru

# Run a folder against a specific environment
bru run folder --env Local
```

For the full command reference, see the [Bruno CLI documentation](https://docs.usebruno.com/bru-cli/overview).

## Run with Docker

Official Docker images for the **Bruno CLI** let you run API collections in CI/CD pipelines and locally without installing Node.js or npm on the host. Images are published to both [Docker Hub](https://hub.docker.com/r/usebruno/cli) and the [GitHub Container Registry](https://ghcr.io/usebruno/cli) on every CLI release, with `alpine` and `debian` variants for `linux/amd64` and `linux/arm64`.

```sh
# Pull from Docker Hub
docker pull usebruno/cli:latest

# Or pull from the GitHub Container Registry
docker pull ghcr.io/usebruno/cli:latest

# Run a collection by mounting the current directory
docker run -v $(pwd):/bruno usebruno/cli run
```

For variants, tag matrix, environment files, and CI examples (GitHub Actions, GitLab CI, Jenkins), see the full [Bruno CLI Docker documentation](https://docs.usebruno.com/bru-cli/docker).

## Features

### Visual API Flows

Build and debug API workflows on a visual canvas. Flow support is implemented in this source tree; use the desktop app and CLI from this checkout to try it.

- Connect saved requests with scripts, conditions, delays, assertions, loops, datasets, and sub-flows.
- Save flows in Bru or YAML collections, and import/export definitions as JSON for sharing through Git.
- Use `ctx`/`res` autocomplete, quick assertion snippets, node duplication (**Ctrl+D / Cmd+D** or right-click), and **Beautify Graph** with Dagre.
- Inspect each node's input/output, compare loop iterations, and **Copy as cURL** from captured request executions.
- Validate the graph before running, or debug with breakpoints, **Step Next**, **Continue**, and editable paused context.
- Reuse a saved request with per-node header, query-parameter, and JSON-object body overrides.

To create a flow, right-click a collection or folder and choose **New Flow**. Select **Blank Flow**, **OAuth2 Login & Fetch**, **Async Job Polling Loop**, or **Data-Driven Batch Run**. Choose the saved requests required by the template, adapt its variables and assertions to your API, then save and select **Run Flow**.

To try JSON import, save the following as `hello-flow.json`, then choose **Import Flow (JSON)** from a collection or folder menu and select the file. It creates a simple Start → End flow. For a fuller example with a loop, script, and assertion, import [sum-flow.json](examples/flows/sum-flow.json); it adds three numbers and verifies that the total is 60. Existing flows also offer **Import JSON** and **Export JSON** in their toolbar.

```json
{
  "type": "bruno-flow",
  "version": 1,
  "name": "Hello Flow",
  "flow": {
    "nodes": [
      { "id": "start", "type": "start", "position": { "x": 100, "y": 100 }, "data": {} },
      { "id": "end", "type": "end", "position": { "x": 400, "y": 100 }, "data": {} }
    ],
    "edges": [
      { "id": "start-to-end", "source": "start", "target": "end" }
    ],
    "viewport": { "x": 0, "y": 0, "zoom": 1 },
    "config": { "stopOnError": true }
  }
}
```

Run a flow from the collection directory using this checkout's CLI:

```sh
node /path/to/bruno/packages/bruno-cli/bin/bru.js run \
  --flow flows/e2e.flow.bru --env staging --env-var buildId=9421
```

The resolved environment and CLI `--env-var` values are available directly in `ctx`, including before the first request. JSON and JUnit flow reports are supported. Body overrides shallow-merge JSON objects; binary and multipart request captures cannot be reconstructed by Copy as cURL. Templates require API-specific mappings, and interactive debugging is available in the desktop app.

#### Flow JSON examples by scenario

The [scenario collection](examples/flows/scenarios/README.md) includes complete importable JSON files for **all 11 supported node types**, saved requests, CSV/JSON datasets, and a local demo API. Download or clone this repository to keep the supporting files together.

| Scenario | JSON import file | What it demonstrates |
| --- | --- | --- |
| Start → End | [01-blank.json](examples/flows/scenarios/01-blank.json) | Minimal runnable graph |
| Variables and scripts | [02-variables-and-script.json](examples/flows/scenarios/02-variables-and-script.json) | Initial context, CLI variables, scripts, multiple assertions, desktop breakpoint |
| Request and response validation | [03-request-and-schema.json](examples/flows/scenarios/03-request-and-schema.json) | Saved request, output mappings, request-script variables, JSON Schema |
| Login → authenticated request | [04-login-and-authorized-request.json](examples/flows/scenarios/04-login-and-authorized-request.json) | Extract a token and interpolate it into an Authorization header |
| True/false branches | [05-conditional-branches.json](examples/flows/scenarios/05-conditional-branches.json) | Choose a route and rejoin after the branch |
| For-each loop | [06-for-each.json](examples/flows/scenarios/06-for-each.json) | Array iteration, named item/index variables, accumulation |
| Polling with a delay | [07-polling-with-delay.json](examples/flows/scenarios/07-polling-with-delay.json) | While loop, response mapping, delay, iteration limit |
| CSV-driven batch | [08-dataset-csv.json](examples/flows/scenarios/08-dataset-csv.json) | CSV headers become row properties; numeric strings are converted explicitly |
| JSON-driven batch | [09-dataset-json.json](examples/flows/scenarios/09-dataset-json.json) | Iterate an array of objects from a collection file |
| Reusable sub-flow | [10-sub-flow.json](examples/flows/scenarios/10-sub-flow.json), [10-child.json](examples/flows/scenarios/10-child.json) | Pass parent context into a child and receive its changes |
| Per-node request overrides | [11-request-overrides.json](examples/flows/scenarios/11-request-overrides.json) | Override headers, query parameters, and JSON body without editing the saved request |
| Continue after an error | [12-continue-on-error.json](examples/flows/scenarios/12-continue-on-error.json) | Run cleanup with `stopOnError: false`; final status remains failed |
| Collect assertion failures | [13-collect-assertion-failures.json](examples/flows/scenarios/13-collect-assertion-failures.json) | Evaluate every check with `failFast: false`, then continue |
| Stop on first failure | [14-stop-on-error.json](examples/flows/scenarios/14-stop-on-error.json) | Fail-fast assertions and stopping before downstream nodes |
| Runaway-loop protection | [15-loop-limit.json](examples/flows/scenarios/15-loop-limit.json) | An intentionally endless condition is stopped by `maxIterations` |

**Try it:** open `examples/flows/scenarios` as a collection, then import one of its numbered JSON files. In IntelliJ, use **Bruno → Flows → Import JSON…** and choose this directory as the destination collection. Set the flow runner to this checkout's CLI. Examples 01, 02, 05, 06, 08, 09, and 10 need no HTTP server. Examples 12–15 intentionally fail to demonstrate error reporting.

For examples that send requests, start the local API from the repository root:

```sh
node examples/flows/scenarios/mock-server.cjs
```

Set `baseUrl` to `http://127.0.0.1:4010` in the collection environment, or pass `--env-var baseUrl=http://127.0.0.1:4010` to the CLI. IntelliJ's API environment selector and CLI flow environment are separate; use a collection environment for **Run Flow**. Import creates a native flow file; run that file rather than passing the interchange `.json` directly to `--flow`.

For example, after importing `11-request-overrides.json`, run from the scenario collection directory (substitute the actual imported filename):

```sh
node /path/to/bruno/packages/bruno-cli/bin/bru.js run \
  --flow 'flows/Request Overrides.flow.bru' \
  --env-var baseUrl=http://127.0.0.1:4010
```

Request-node configuration uses a **saved request reference**, with response properties mapped into `ctx`:

```json
{
  "id": "login",
  "type": "request",
  "position": { "x": 360, "y": 100 },
  "data": {
    "itemRef": "requests/login.bru",
    "outputVars": { "token": "res.body.token" }
  }
}
```

Another request node can reuse that token and change parameters/body for this step:

```json
{
  "id": "post",
  "type": "request",
  "position": { "x": 620, "y": 100 },
  "data": {
    "itemRef": "requests/post.bru",
    "overrides": {
      "headers": { "Authorization": "Bearer {{token}}" },
      "params": { "page": 2 },
      "body": { "name": "{{label}}" }
    }
  }
}
```

These two snippets are **node fragments**; the linked files contain full importable graphs. Request, dataset, and sub-flow paths are relative to the destination collection root. Importing JSON transfers the graph; copy its supporting files too. See the [scenario guide](examples/flows/scenarios/README.md) for branch/loop wiring, expected results, variable precedence, failure policies, and report commands.

To validate every supplied scenario against the importer and real CLI, run from this checkout with dependencies installed:

```sh
node examples/flows/scenarios/verify.cjs
```

See [flow authoring and request overrides](docs/flows-phase7.md), [node input/output inspection](docs/flows-node-inspection.md), [JSON interchange](docs/flows-json.md), and [CLI flow execution](docs/flows-cli.md). The [implementation plan](FLOWS_PLAN.md) and [desktop validation notes](docs/flows-desktop-validation.md) track completed work and test coverage.

An experimental IntelliJ IDEA integration is available in [integrations/intellij](integrations/intellij/README.md). Version 0.4.10 adds a module/controller API tree and source gutter actions, persistent request tabs, IDE-wide environments with credential-store secrets, structured request editors, response viewers, JSON flow import, flow execution history with per-step input/output, OpenAPI export, Bruno JS hooks, and an optional development bridge for direct MVC and scheduled-task invocation. The tool window adapts to narrow widths and also runs saved collection flows with the Bruno CLI.

### Run across multiple platforms 🖥️

![bruno](assets/images/run-anywhere.png) <br /><br />

### Collaborate via Git 👩‍💻🧑‍💻

Or any version control system of your choice

![bruno](assets/images/version-control.png) <br /><br />

## Important Links 📌

- [Our Long Term Vision](https://github.com/usebruno/bruno/discussions/269)
- [Roadmap](https://www.usebruno.com/roadmap)
- [Documentation](https://docs.usebruno.com)
- [Stack Overflow](https://stackoverflow.com/questions/tagged/bruno)
- [Website](https://www.usebruno.com)
- [Pricing](https://www.usebruno.com/pricing)
- [Download](https://www.usebruno.com/downloads)

## Showcase 🎥

- [Testimonials](https://github.com/usebruno/bruno/discussions/343)
- [Knowledge Hub](https://github.com/usebruno/bruno/discussions/386)
- [Scriptmania](https://github.com/usebruno/bruno/discussions/385)

## Share Testimonials 📣

If Bruno has helped you at work and your teams, please don't forget to share your [testimonials on our GitHub discussion](https://github.com/usebruno/bruno/discussions/343)

## Publishing to New Package Managers

Please see [here](publishing.md) for more information.

## Stay in touch 🌐

[𝕏 (Twitter)](https://twitter.com/use_bruno) <br />
[Website](https://www.usebruno.com) <br />
[Discord](https://discord.com/invite/KgcZUncpjq) <br />
[LinkedIn](https://www.linkedin.com/company/usebruno)

## Trademark

**Name**

`Bruno` is a trademark held by [Anoop M D](https://www.helloanoop.com/)

**Logo**

The logo is sourced from [OpenMoji](https://openmoji.org/library/emoji-1F436/). License: CC [BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/)

## Contribute 👩‍💻🧑‍💻

I am happy that you are looking to improve bruno. Please check out the [contributing guide](contributing.md)

Even if you are not able to make contributions via code, please don't hesitate to file bugs and feature requests that needs to be implemented to solve your use case.

## Authors

<div align="center">
    <a href="https://github.com/usebruno/bruno/graphs/contributors">
        <img src="https://contrib.rocks/image?repo=usebruno/bruno" />
    </a>
</div>

## License 📄

[MIT](license.md)
