# Seyir plugin template

**English** · [Türkçe](README.tr.md)

A starter project for writing plugins for [Seyir](https://github.com/fatihozpolat/seyir), the desktop app for watching
Turkish movies, TV series and live TV channels. Plugins use the CloudStream plugin API, and every folder is a plugin in
the **CloudStream layout**: the same folder structure as in a CloudStream repository. Building produces a repository
(`repo.json`, `plugins.json`, `<Name>.jar`) that Seyir reads directly.

The example plugin is **`KanalD/`**, a small, complete provider for kanald.com.tr with a home page, search, seasons and
HLS links read from JSON-LD. Read it next to the
[porting and development guide](../docs/porting-cloudstream-plugins.md).

## How it works

- Each folder in the project root that contains a `build.gradle.kts` is one plugin.
- The folder's `build.gradle.kts` is **never executed**. It holds the CloudStream metadata (`version`, and a
  `cloudstream { }` block); the root `build.gradle.kts` reads it with regular expressions and supplies the real build
  settings. There is no `buildSrc`.
- The root build compiles each plugin for the JVM against `libs/seyir-api.jar` (Seyir's Android and CloudStream
  compatibility layer) and the CloudStream library, and writes a `manifest.json` into the jar.
- `./gradlew makeRepo` writes the repository to `build/repo/`; `./gradlew publishRepo` pushes it to the `builds` branch.

## Requirements

- JDK 21 (Gradle downloads it if missing)
- Internet access on the first build (dependencies)

## Quickstart

1. **Copy the template** into a new project or GitHub repository (without `build/`).

2. **Rename the example.** Rename the `KanalD/` folder (the folder name is the plugin's internal name), then rename the
   package and classes inside it. Delete the example's sources you do not need.

3. **Edit the metadata** in `<YourPlugin>/build.gradle.kts`:

   ```kotlin
   version = 1 // raise it with every release; Seyir finds updates by comparing it

   cloudstream {
       displayName = "Your Site"                      // Seyir only, optional: name shown in the app
       description = "Short description of the site"
       authors     = listOf("your-github-name")
       language    = "tr"
       tvTypes     = listOf("Movie", "TvSeries")      // Movie, TvSeries, Anime, Documentary, Live ...
       iconUrl     = "https://example.com/favicon.ico"
       status      = 1                                // 0: down, 1: ok, 2: slow, 3: beta (default 3)
   }
   ```

   Values must be plain literals (`version = 2`, `authors = listOf("a")`); nothing else is understood.

4. **Write the code** under `src/main/kotlin/...`. Two classes are needed:
   - the entry class, annotated with `@CloudstreamPlugin`, extending `Plugin`, calling `registerMainAPI(...)` (and
     `registerExtractorAPI(...)` for extractors);
   - the provider, extending `MainAPI`: `getMainPage`, `search`, `load` and `loadLinks`.

   The API is CloudStream's (`com.lagradost.cloudstream3.*`). Android UI code (settings screens, `R` resources) and
   WebView-based extractors are not supported.

5. **Build:**

   ```bash
   ./gradlew makeRepo --continue   # Windows: gradlew.bat makeRepo --continue
   ```

   The output is in `build/repo/`. A plugin that does not compile is left out; `--continue` lets the others finish.

6. **Test.**
   - *With the Seyir CLI* (built from the Seyir repository with `./gradlew :cli:installDist`):

     ```bash
     JAVA_OPTS="-Dseyir.plugins=/path/to/this/project/build/repo -Dseyir.play=8 -Dseyir.mpv=/usr/bin/mpv" \
       cli probe "Your Site" "search words"
     ```

     A working plugin prints `OYNATMA OK` (playback OK). `cli list`, `home`, `search`, `load`, `links` and `fetch` test
     the steps one by one.
   - *In the app:* Plugins -> **Install from file** (*Dosyadan kur*) -> `build/repo/YourPlugin.jar`.
   - *Through a local repository:* run `python -m http.server 8765` inside `build/repo`, then add
     `http://127.0.0.1:8765/repo.json` under Plugins -> Repositories.

7. **Publish.**
   - Push the project to a GitHub repository.
   - Run `./gradlew publishRepo`. It builds and force-pushes `build/repo` as a single commit to the `builds` branch of
     `origin`.
   - Alternatively `.github/workflows/build.yml` does the same on every push to `main` when GitHub Actions is available
     (set Settings -> Actions -> General -> Workflow permissions to "Read and write").
   - Users add `https://github.com/<user>/<repo>` under Seyir -> Plugins -> Repositories. Seyir reads
     `https://raw.githubusercontent.com/<user>/<repo>/builds/repo.json`. A direct `repo.json` / `plugins.json` URL or the
     URL of a single `.jar` also works. Private repositories need a GitHub token in Seyir's Settings.

## Porting an existing CloudStream plugin

Copy its folder into the project root, remove Android-only code, and build. The full walkthrough, with the common compile
errors and runtime gotchas, is in
**[docs/porting-cloudstream-plugins.md](../docs/porting-cloudstream-plugins.md)**.

## Repository name

The repository name and description shown in the Plugins screen are `repoName` and `repoDescription` at the top of the
root `build.gradle.kts`.

## Skipping plugins

List folder names in `excludedPlugins` in `gradle.properties` to keep them out of the build. The file is a Java properties
file, so Turkish letters need Unicode escapes (`İ` = `İ`, `ı` = `ı`, `ş` = `ş`).

## Seyir API

`libs/seyir-api.jar` holds the classes Seyir offers to plugins (the Android and CloudStream look-alikes).
`gradle/libs.versions.toml` pins the matching dependency versions. When Seyir changes, refresh both from the Seyir
repository with `./gradlew syncPluginTemplate`, or copy them from a newer version of this template. The Kotlin version
must stay the same as the app's.

## Security

Plugins run code on the user's computer. Do not publish a plugin that collects user data or does more than the site itself
asks for.
