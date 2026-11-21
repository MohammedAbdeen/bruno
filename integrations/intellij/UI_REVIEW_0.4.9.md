# Stitch-assisted UI review — IntelliJ plugin 0.4.9

Reviewed 2026-11-21. Scope: APIs request header, environment/search controls, Flows toolbars, and first-run empty state.

## Stitch workflow

Created a private [Stitch review project](https://stitch.google.com/projects/8657401481547929384) and attached sample-data screenshots of the existing wide/narrow API workspace and empty Flows view. Screenshot upload used the official Stitch CLI because the current MCP tools do not expose upload. Project creation, discovery, layout refinement and canonical reference URL retrieval used Stitch MCP.

Inspected the generated [layout reference](https://stitch.google.com/projects/8657401481547929384?node-id=64dcd8b04def472d91f80014c88e6b0e). The useful direction was a consolidated request command row, explicit resolved URL and compact environment controls. Stitch is a design generator, not a Java static analyzer; findings were confirmed in the native code and tests.

The generated HTML was not incorporated into the plugin. Native Swing/JetBrains controls, theme colors, existing method colors and behavior remain authoritative. Generated decorative details, invented version text and extra actions were not adopted.

## Findings and fixes

| Finding | User impact | Native fix |
| --- | --- | --- |
| Save/More occupied a separate mostly empty row above the request URL. | Wasted vertical space and separated related actions. | Group Send, Save and More on one command row at wide widths; share the second URL row at compact widths. Save has a visible label, tooltip and accessible name. |
| The resolved URL had no caption and HTML labels could request excessive space for long content. | Variable resolution was easy to miss; long URLs could distort the header. | A single-line “Resolved:” label clips visually, retains the full redacted tooltip, and has a flexible width. Consistent BoxLayout alignment keeps it at the left edge and gives it the full header width. Validation remains amber and missing-variable highlighting is preserved. |
| The global environment label and manager consumed too much of a narrow row. | Selected profiles became difficult to read. | Use compact “Env” and “Manage…” captions below 550 scaled pixels. Retain the full accessible name and explanation of IDE-wide scope. |
| Search fields relied on a placeholder property instead of a native empty-text component. | Empty search fields could appear unlabeled or give little guidance. | Use JBTextField empty-text hints for API search and response search, with accessible names. |
| FlowLayout wrapped actions without reserving height for the additional rows. | Flow actions could be clipped or overlap adjacent rows at narrow widths. | Width-aware wrapping toolbar computes the required height and invalidates cached ancestor sizes on resize, including first attachment. |
| Flow selector and environment competed for one narrow row. | Flow paths and environment text became cramped. | Stable responsive row stacks the two controls below 550 scaled pixels; labels distinguish collection environments from API profiles. |
| Empty-state text and paired buttons assumed a wide window. | First-run actions could overflow a narrow tool window. | Short multi-line guidance, bounded content width and wrapping action groups keep all four actions available. |

## Regression coverage

StitchUiReviewTest verifies command alignment and visibility at 420/900px, long URL handling, global environment readability, all populated Flow actions and environment input at 320/420/900px and after resizing back, and all four empty-state actions at 320px. Assertions check both viewport bounds and ancestor heights, detecting controls that wrap below the reserved toolbar area.

Existing workspace, tab overflow, draft preservation, request/response sizing, value readability, JSON import and actual flow execution/history checks are also retained.

## Verification

- Full IntelliJ 2024.3 baseline: **120 plugin tests passed**, zero failures/errors/skips.
- Spring companion: **5 tests passed**, zero failures/errors/skips.
- Build and plugin structure task succeeded. Existing plugin-ID naming warning remains for update compatibility.
- Native IntelliJ **2026.3 (263.7307.200)** with bundled JBR: **31 UI/integration checks passed**, zero failures/errors/skips.
- Inspected native screenshots for compact request commands, populated Flows at 320px, empty Flows at 320px and the wide workspace. Caught and fixed the resolved-label alignment during inspection; final bounds assertions cover it.
- Version **0.4.9** ZIP integrity, descriptor, new layout classes and companion setup version verified: **340,797 bytes**.
- Source whitespace checks and git diff --check passed. The installed plugin was not modified.

## Limits

The Stitch reference is a visual aid; it does not establish accessibility compliance or prove every screen is defect-free. Tests cover the listed widths and native fixtures. The 550px breakpoint is HiDPI-scaled; fonts and intrinsic component sizes determine the wrapping rows. No network/rendering work was introduced into layout code.


Generated evidence under build/ui-snapshots:
- stitch-request-420.png and stitch-request-900.png
- stitch-flows-320.png, stitch-flows-420.png and stitch-flows-900.png
- stitch-flows-empty-320.png
- workspace-1200.png and workspace-420.png

Verified implementation screenshots were also uploaded to the same private Stitch project for comparison: compact request (9366722573415453419), narrow Flows (9246230136299459057), and narrow empty state (12924359166666181191).
