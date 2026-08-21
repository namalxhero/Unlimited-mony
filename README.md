# Root Game Editor

A root-only Android tool for browsing `/data/data/<package>` and editing
**offline single-player game saves** — SQLite databases and shared_prefs
XML/text files — directly on-device.

## How it works

1. On first launch it requests root (via Magisk / SuperSU, using the
   [libsu](https://github.com/topjohnwu/libsu) library).
2. You browse into any app's private data folder (root lets us see folders
   normal apps can't).
3. Tapping a `.db` / `.sqlite` file copies it into our app's own cache
   (root `cp`), opens it with Android's normal `SQLiteDatabase` API, and
   shows every table as an editable list of rows.
4. Tapping a `.xml` / `.json` / `.txt` file (e.g. `shared_prefs`) opens it
   as plain text you can edit and save.
5. "Save changes to game" copies the edited file back over the original
   with root `cp`, and restores the original owner/permissions with
   `chown`/`chmod` so the game doesn't choke on the wrong file owner.

## Building

This is a standard Gradle Android project. Either:

- Open it in Android Studio and hit Run, or
- Push it to GitHub — `.github/workflows/build.yml` builds a debug APK
  on every push to `main` and uploads it as a workflow artifact you can
  download from the Actions tab.

## Important limitations

- **Root required.** Without root, `RootFs` calls will just fail — this
  is not something you can work around.
- **Offline / single-player games only.** Online or competitive games
  validate progress server-side; editing local save files there does
  nothing except risk a ban. Don't use this on anything with
  multiplayer, leaderboards, or an anti-cheat system.
- **Back up before editing.** The app copies the *original* file out
  before touching it, but it doesn't keep a versioned backup automatically.
  Consider copying the whole app data folder somewhere safe first
  (e.g. `adb pull` or a root file manager) if the save matters to you.
- **Encrypted saves aren't handled.** Some games encrypt or checksum
  their save files; editing those directly will just make the file
  unreadable to the game. This tool only helps with plain SQLite/XML/JSON
  saves.
- **App/package names change.** The `/data/data/<package>` folder name
  is the app's package ID (e.g. `com.some.game`), not its display name —
  you may need to check the package name in Settings > Apps first.
