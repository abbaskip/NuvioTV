# Nuvio ReSeq privacy

The current public [Privacy Policy](https://abbaskip.github.io/Nuvio-ReSeq/privacy.html)
includes Google Drive backup contents, credential storage, permission differences
between builds, third-party services, and instructions for disconnecting and deleting
data. Please read that policy for the complete disclosures. The original summary
below is retained as repository background.

Nuvio ReSeq runs on your Android TV device. The maintainer does not operate a separate ReSeq account service or tracking backend. This release has no ReSeq telemetry endpoint.

The app contacts the metadata providers, addons and integrations you configure. These requests can include titles, catalogue IDs, search terms and episode numbers. Connected tracking services can receive playback progress and watched activity through Nuvio’s existing integration settings. Your chosen read sources control watched indicators, resume and Continue Watching; playback can scrobble to each connected service.

Signing into a Nuvio account uses Nuvio’s authentication and cloud-sync services. Supported profiles, addons, settings, library and progress data can be stored there. [Nuvio’s privacy policy](https://nuvio.tv/privacy-policy) applies to those services. Trakt, Simkl, MDBList, TheTVDB and your other providers have their own policies and account controls.

Profiles, settings, caches, local progress and saved stream references are stored in the app’s private device storage. Personal TVDB keys and supported protected credentials use Android Keystore encryption. Public application client IDs identify the integration; your personal account tokens are separate and are not included in distributed APKs. Some authorisation pages may display the existing Nuvio integration name.

Local data remains until the app removes or replaces it through its normal controls, a profile is deleted, or Android app data is cleared. Uninstalling or clearing app data removes local settings and resume information. Disconnecting an integration stops its authenticated access; it does not delete history already held by that provider. Manage or delete cloud account data through the relevant service.

Do not include API keys, account tokens, private addon URLs or exported app data in public issues. For ReSeq questions, use [this repository](https://github.com/abbaskip/Nuvio-ReSeq/issues) and redact personal information.
