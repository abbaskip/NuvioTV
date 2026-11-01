<p align="center">
  <img src="branding/Nuvio-ReSeq-logo.png" alt="Nuvio ReSeq" width="640">
</p>

# Nuvio ReSeq

Nuvio ReSeq is an independent Android TV fork of [Nuvio](https://github.com/NuvioMedia/NuvioTV), combining TheTVDB episode ordering and identity-aware tracking with integrated IPTV and Sports Hub. Its Live TV and Sports implementation incorporates and adapts code from [Nuvio + IPTV](https://github.com/homelessbrian/NuvioPlusIPTV), alongside the original Nuvio foundation.

**[Download the latest APK](https://github.com/abbaskip/Nuvio-ReSeq/releases/latest)** · [All releases](https://github.com/abbaskip/Nuvio-ReSeq/releases) · [Report an issue](https://github.com/abbaskip/Nuvio-ReSeq/issues)

## Choose the episode order that matches your sources

Open a series and select **Episode order**:

- **Default / Metadata Provider** keeps the original metadata provider’s episodes and normal Nuvio behaviour.
- **TVDB Standard / Aired** uses TVDB’s standard listing.
- Other TVDB orders are offered when available for that series, including DVD, absolute and alternate orders. The available names come from TVDB.

The season and episode number you see is the number sent to streaming addons. For example, selecting Alternate **S05E03 — Business Ethics** searches for **S05E03**, even though its standard identity is **S05E02**. Choose the returned stream that matches your intended episode; ReSeq does not inspect filenames or filter editions for you.

Tracking is separate from stream searching. Verified episode correspondence connects watched status, scrobbling, progress and resume to the underlying episode identities used by Nuvio, Trakt, Simkl and MDBList. Your selected tracking sources still control watched indicators and Continue Watching. Verified correspondences can span series that different catalogues organise differently.

For known split/combined episodes, progress can be projected onto verified underlying episodes. Known runtimes are used where available; otherwise part boundaries are proportional estimates. Local resume keeps the exact video position. Extended cuts of ordinary episodes use the playing video’s actual duration. Existing **Reuse Last Link** continues to identify the underlying episode across order changes.

## Set up TheTVDB

1. Visit [TheTVDB’s API information page](https://thetvdb.com/api-information) to obtain an API v4 key with suitable access. Access is subject to TheTVDB’s terms and may require a subscription or licence.
2. In ReSeq, open **Settings → Integrations → TheTVDB** for the profile you use.
3. Enter your own key, save it and select **Test connection**.
4. Open a series and choose its episode order.

Keys and order selections are profile-specific. No personal TVDB key is included in the APK. Default / Metadata Provider works without one.

Metadata provided by [TheTVDB](https://thetvdb.com). Please consider contributing missing information or subscribing.

## IPTV and Sports Hub

**Live TV** opens a TV guide with channel groups, playlist selection, favourites, ordering preferences, search and full-screen playback. Configure your own **Xtream Codes** account or an **M3U playlist with XMLTV guide** in Live TV settings. Supported provider on-demand content is available from **On Demand**. ReSeq supplies no playlists, subscriptions or media. The guide and standalone On Demand browser use provider metadata and artwork, without automatic title searches against metadata addons. Catalogue caching and identity-aware IPTV stream matching from normal movie and series pages remain available.

**Sports Hub** brings together fixtures, supported results/live scores and channels from your playlists. Choose sports and optionally exclude categories or competitions; synonyms work in the background. Rugby League and Rugby Union are separate. Matching considers guide titles/descriptions, participants, broadcasters and channel names while retaining regional, provider and quality alternatives. Catch-up depends on your provider.

Confirmed live fixtures use **LIVE**. Guide-only transmissions use **ON AIR**; explicit replay evidence can show **REPLAY**. A programme airing now does not establish that the original match is live. Confirmed live events take priority over guide broadcasts and replays, ahead of upcoming events.

A shared catalogue is refreshed by this repository’s scheduled GitHub workflow and served from [our public sports feed](https://abbaskip.github.io/Nuvio-ReSeq/sports-base.json). Selective live-score requests, local EPG matching and caches keep device requests bounded. ESPN, TheSportsDB and sporting-events.org remain public data dependencies; their free coverage and availability vary. The feed contains public sporting metadata, never users’ playlists or account data.

Optional Gemini assistance uses **your own key**, entered in Settings. It is not required for ordinary deterministic channel matching, and its suggestions are not treated as verified broadcast evidence. No personal Gemini or TVDB key is supplied with the app.

## Configuration backup and personal ratings

**Google Drive backup and sync** is available in **Settings → Backup & Sync** on the primary profile. Connect Google Drive on each TV using the on-screen code. Manual backup/restore and debounced automatic sync carry playlists and connection settings, guide mappings, channel customisations, Sports Hub preferences and each profile's mixed addon/playlist source ordering, disabled playlist sources, non-default TVDB order IDs and per-series Shuffle choices (enabled/off and All episodes/Unwatched episodes). The global Shuffle availability switch continues using Nuvio's existing settings/cloud sync. Temporarily unavailable orders are retained. Use matching Nuvio profile IDs and names on both devices; restore stops rather than assigning one person's choices to another profile.

The backup includes private playlist credentials. ReSeq never shares it; don't share the file yourself. Personal TVDB/Gemini keys, tracker sessions, learned episode mappings and playback caches are not copied. Conflicting changes stop sync and are reported rather than silently overwriting settings. Automatic checks run while ReSeq is open, not as an always-running background service. Explicit Restore uses the saved configuration, so review the confirmation first.

**Rate** on movie and series details reads your personal ratings and submits changes to all eligible signed-in Trakt, Simkl and MDBList accounts, independently of your Continue Watching source. Half-star choices map to their 1–10 scales. Each service confirms its own result; failures are reported separately. Removing a rating uses the rating-removal API, not history deletion. Simkl itself may add a newly rated title to a watching/completed list according to its API rules.

An optional **Prompt to rate after watching** setting is off by default. It prompts after returning from a movie that reached playback completion, not after episodes or early stops. Rating controls require a supported title identity and a compatible connected tracker.

## IPTV playback sources and Stalker portals

Xtream playlists with movies and series enabled appear **individually in Addons**, alongside your existing addons. Move each playlist up or down independently; its switch controls stream-list availability without removing the playlist, guide or On Demand browser. Autoplay has separate Addons, Plugins and IPTV Playlists switches under **Settings → Playback**. IPTV autoplay starts off.

For normal addon searches, the selected TVDB order still determines the requested SxxEyy. IPTV catalogue matching instead uses the selected episode's identity and metadata, so a differently numbered provider copy can be offered. Conflicting identifiers, remakes, generic episode names and ambiguous matches are left unresolved rather than substituted by episode number. The separate On Demand browser remains available for unmatched content. Existing playback, resume and tracker paths are reused.

**Stalker / Ministra** connections can be added under **Settings → IPTV** using a provider-authorised portal URL and MAC address, with optional login and serial fields when required. Live channels, categories and the built-in guide use the existing Live TV and Sports Hub paths. Playback links are requested when needed; portal session tokens stay in memory. This initial implementation covers live TV and EPG, not portal VOD or portal-specific archive APIs. Protocol behaviour is tested against simulated portals; a real Stalker subscription has not been tested. Some providers require additional device identity fields not covered by the basic MAG profile.

The Stalker protocol implementation incorporates adaptations from [IPTVnator](https://github.com/4gray/iptvnator); its [MIT notice is retained](NOTICE-IPTVNATOR.md).

## Install and update

Download the **universal APK** from this repository’s [GitHub Releases](https://github.com/abbaskip/Nuvio-ReSeq/releases/latest), transfer it to your Android TV device and allow installation from your chosen installer. Android 7.0 or later is required. Release pages include a SHA-256 checksum.

ReSeq installs alongside official Nuvio and existing Nuvio Debug installations. Its permanent package ID is `io.github.abbaskip.nuvioreseq`. Settings and login sessions are separate. Signing into the same Nuvio account restores supported cloud data; Google Drive can carry the configuration described above. Personal API keys and integration sessions still require setup on each device.

In-app updates check **only this repository**. There is one release channel: versions labelled beta are published as ordinary GitHub Releases. Install future signed ReSeq releases over the existing app to retain its data. Do not uninstall first.

## Beta limitations

- TVDB coverage, artwork and episode correspondence vary. Missing or ambiguous identities are not guessed for tracking. Some episodes may therefore have limited tracking or resume support.
- A combined video’s estimated split point may not match its actual scene boundary. Cross-device resume recovers the best available native-episode position; exact local resume is retained.
- Switching cuts may change the meaning of a saved timestamp. Stream addons use different numbering conventions; selecting an order does not guarantee every returned stream matches it.
- Large playlists and guides can take several minutes for the first complete background scan. Cached results and event-specific suggestions appear progressively; guide changes invalidate affected discovery. Ambiguous channels, competitions and replay status may remain unverified.
- Free sports sources do not cover every sport, competition or broadcast territory, and scores can be delayed. A broadcaster match alone does not prove a specific programme is the selected event.
- ReSeq does not supply media. Bring your own addons and sources. External services have their own availability, account and API requirements.

## Build from source

Use Android Studio, JDK 21 and the Android SDK. Copy `local.example.properties` to the ignored `local.properties` and supply your own application configuration. Credentials are not included in this repository. For the bundled FFmpeg decoder, a debug build is:

```sh
./gradlew :app:assembleFullDebug -PuseLocalFfmpegDecoder=false
```

Debug builds have a separate package ID. Signed releases require a private keystore and the `NUVIO_RELEASE_STORE_FILE`, `NUVIO_RELEASE_KEY_ALIAS`, `NUVIO_RELEASE_STORE_PASSWORD` and `NUVIO_RELEASE_KEY_PASSWORD` environment variables (or ignored local properties). Public ReSeq APKs use a permanent release certificate. Build configuration embedded in an APK is extractable; never supply personal account tokens or confidential backend credentials.

For optional Google Drive support, enable the Drive API and create a **TVs and Limited Input devices** OAuth client. Supply `GOOGLE_DRIVE_CLIENT_ID` and `GOOGLE_DRIVE_CLIENT_SECRET` through ignored local properties or environment variables. This device flow does not use an Android package/signature OAuth registration. Users connect their own Google account; no individual API registration is required. ReSeq uses the limited `drive.file` permission. Google branding verification is pending, so Google may display an unverified-app notice or apply access limits. See [Google's device-flow documentation](https://developers.google.com/identity/protocols/oauth2/limited-input-device). Keep downloaded client configuration outside Git; installed-app configuration is extractable from an APK.

[Privacy and data handling](PRIVACY.md). Some service authorisation pages may identify the existing Nuvio integration.

## Credits and licence

Nuvio ReSeq is maintained by [abbaskip](https://github.com/abbaskip). It is based on [Nuvio by Tapframe and contributors](https://github.com/NuvioMedia/NuvioTV), whose work makes this fork possible. Substantial Live TV, EPG, playback, Sports Hub and related code is incorporated and adapted from [Nuvio + IPTV by homelessbrian and contributors](https://github.com/homelessbrian/NuvioPlusIPTV); see [the incorporation and modification notice](NOTICE-IPTV.md). Personal-rating API behaviour was also adapted from [Nuvio Mobile Enhanced](https://github.com/luqmanfadlli/NuvioMobile-Enhanced), using ReSeq’s existing authenticated tracker clients. ReSeq is not an official release of, or endorsed by, these projects.

Source code is distributed under the [GNU General Public License v3.0](LICENSE). Existing third-party notices and in-app licences are retained. TheTVDB, TMDB, Trakt, Simkl, MDBList and other services retain their respective names, data rights and terms; their use does not imply endorsement.
