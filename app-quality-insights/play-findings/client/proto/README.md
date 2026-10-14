# Play Findings API (mirrored from google3)

The `.proto` files in this directory are mirrored from
`//depot/google3/google/play/androidpublisher/v3/` with internal Google API
annotations (such as `google/api/field_behavior.proto`,
`google/api/resource.proto`, and `datapol`) stripped for Android Studio builds.

## How to port `.proto` files from google3
1. Build the proto targets in `google3`:
   ```shell
   blaze build //google/play/androidpublisher/v3:all
   ```
2. Copy the relevant findings `.proto` files from
   `//depot/google3/google/play/androidpublisher/v3/` into this directory and
   strip internal Google API options and imports.

* **Future automation:** In the future, we will automate this mirroring and
  annotation-stripping process using a script / Copybara workflow (similar to
  how Play Vitals and Crashlytics protos are mirrored).
* **Local prototype additions:** `large_screens_finding_data.proto` and the
  `LARGE_SCREENS_*` entries in `finding.proto` and `finding_data.proto` are
  local prototype definitions added ahead of the public backend API and will be
  synced once finalized in `google3`.
