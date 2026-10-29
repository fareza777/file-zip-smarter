# ZipHatch – Play Console listing notes

Privacy policy: https://fareza777.github.io/ziphatch/privacy-policy.html

## Data safety
- Data collected: **Yes** (only by the Google Mobile Ads SDK / AdMob; the app itself has no accounts, analytics or first-party network).
- Data shared: **Yes** (AdMob, for advertising/analytics and fraud prevention).
- Types (collected + shared):
  - Device or other IDs (advertising ID)
  - Approximate location (derived from IP)
  - App interactions
  - Crash logs / Diagnostics
- Encrypted in transit: Yes. Deletion request: users can reset/delete the advertising ID in Android settings.
- Purchases: Google Play Billing one-time `remove_ads` (USD 4.99) – handled by Google Play; no payment data reaches the app.
- Files, archives and passwords stay on device (Storage Access Framework only; no All-files access).
- Consent: Google UMP form shown where required (EEA/UK/CH, US states). Ads and UMP skipped when `remove_ads` is owned.

## Foreground service declaration
`ExtractionService` uses `foregroundServiceType="dataSync"` for long extract/compress jobs.
Play Console > App content > Foreground service permissions requires: task description
("Extracting/compressing user-selected archives continues while the app is in background, with progress notification")
and a **demo video link** (YouTube, unlisted OK) showing a user starting an extraction and the progress notification.
