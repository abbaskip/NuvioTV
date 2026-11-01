# Public website maintenance

Public URLs:

- https://abbaskip.github.io/Nuvio-ReSeq/
- https://abbaskip.github.io/Nuvio-ReSeq/privacy.html
- https://abbaskip.github.io/Nuvio-ReSeq/terms.html

The public site is buildless HTML/CSS under `docs/`. Page content and shared layout
are maintained in `tools/build-public-site.py`; run `python tools/build-public-site.py`
after editing it, then commit the generated HTML. Styles are in `docs/assets/site.css`.
Assets derive from the repository's existing ReSeq branding. No browser JavaScript,
third-party fonts, analytics, package installation or Android build is required.

Run `python tools/check-public-site.py` for internal link/fragment and page checks.
Preview with `python -m http.server 8765 --directory docs`.

## Deployment

Repository Settings → Pages must use **GitHub Actions**, preserving the existing
sports-feed deployment. Do not switch to branch-based `/docs` hosting: the app's
`sports-base.json` is produced dynamically and must remain available at the same URL.
The existing `refresh-sports-feed.yml` workflow stages the three public pages,
their assets and the generated sports feed into one Pages artifact. Architecture
and other Markdown documentation in `docs/` is not part of this public artifact.

Website pushes to `dev` publish automatically, carrying forward the currently
published feed; its next scheduled refresh regenerates the catalogue as before.
If fetching the live feed fails, deployment fails rather than removing it.
Manual dispatch and the six-hour schedule regenerate the feed and publish the site
together. HTTPS is already enforced in this repository's Pages settings.

## OAuth verification follow-up

Enter the three URLs above in Google Auth Platform → Branding, with the app name
Nuvio ReSeq and a monitored support email / developer contact. The public project
contact is the maintainer's GitHub issue tracker; a support email still needs to be
configured in Google Cloud. No email address was invented for the website.

Google requires a homepage on a domain under verified ownership. Hosting on
GitHub Pages does not itself establish this. Attempt URL-prefix verification in
Search Console using Google's issued HTML verification file, then place that file
in the Pages artifact if this method is accepted for the exact site property.
The workflow currently stages only named website files/assets, so add the actual
verification file explicitly when issued. Do not invent a verification token.
Path-level Search Console verification may not satisfy OAuth's authorised-domain
or ownership requirements for `abbaskip.github.io`; Google may reject shared hosting.
No domain purchase or paid hosting was configured. Approval cannot be guaranteed.

Important implementation discrepancy: the published v0.1.0-beta.3.2
`LiveTvDriveSync.SCOPE_ATTEMPTS` tries `openid email drive.appdata`, appdata alone,
then `drive.file`. A Cloud Console configuration listing only `drive.appdata`
does not change these requests in the APK. Before submitting an app-data-only
claim, align the actual app requests and released APK with that scope, and test
the TV device authorisation flow. Android code was intentionally not changed as
part of this website task. Google also expects a prominent in-app privacy link;
confirm the released app links to the published policy during Drive setup.

## Privacy evidence (reviewed 2 November 2026)

Source baseline: `dev` commit `3ee383d905dfa419702c84065b82695084109e9b`,
the published `v0.1.0-beta.3.2` release. Development differences were reviewed
in local `codex/next-features` commit `13efa9290`.

- `livetv/sync/LiveTvDriveSync.kt`: scope attempts, token storage, device-code
  sign-in, direct Drive API requests, backup envelope, merge baseline, startup
  and debounced automatic sync, disconnect/revoke and backup retention.
- `livetv/data/LiveTvPreferences.kt`: `exportForSync`, `SYNC_EXCLUDED`,
  `encodePlaylists` (includes Xtream credentials), preferences and guide data.
- `livetv/ondemand/OnDemandStreams.kt`: provider VOD in the main stream picker.
- `livetv/sports/SportsGemini.kt` and `GeminiGuideMatching.kt`: optional prompt
  fields, API key use, URL/identifier input cleaning and Gemini endpoint.
- Existing `PRIVACY.md`, README, TVDB credential/progress implementation and
  tracking integrations: local data and optional third-party services.
- Development `LiveTvDriveSync.exportSetup`: profile labels, TVDB order,
  stream-source and Shuffle selections; kept separate from published payload.

Java/Kotlin paths above are relative to `app/src/main/java/com/nuvio/tv/`.
Recheck scope requests, payloads, credential storage and deletion controls whenever
the app changes. Do not silently broaden the policy beyond implementation.

Primary policy references:

- https://developers.google.com/identity/protocols/oauth2/policies
- https://support.google.com/cloud/answer/15549049
- https://developers.google.com/terms/api-services-user-data-policy
- https://developers.google.com/workspace/workspace-api-user-data-developer-policy
- https://developers.google.com/workspace/drive/api/guides/appdata
