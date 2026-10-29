# Porting CloudStream plugins to Seyir

**English** · [Türkçe](porting-cloudstream-plugins.tr.md)

This guide shows how to turn a [CloudStream](https://github.com/recloudstream/cloudstream) plugin into a Seyir plugin,
how to write a new plugin from scratch, and how to publish it. Most Turkish-site providers in the CloudStream
ecosystem need no source changes at all, or only a few lines.

Contents

1. [Why porting works](#1-why-porting-works)
2. [Plugin layout](#2-plugin-layout)
3. [Porting step by step](#3-porting-step-by-step)
4. [Compile errors and how to fix them](#4-compile-errors-and-how-to-fix-them)
5. [Runtime gotchas](#5-runtime-gotchas)
6. [Writing a plugin from scratch (KanalD walkthrough)](#6-writing-a-plugin-from-scratch-kanald-walkthrough)
7. [Publishing](#7-publishing)
8. [Experimental: installing `.cs3` plugins directly](#8-experimental-installing-cs3-plugins-directly)
9. [Credits and licensing](#9-credits-and-licensing)

## 1. Why porting works

CloudStream plugins are Kotlin code written against the CloudStream library (`MainAPI`, `newMovieLoadResponse`,
`loadExtractor`, the `app` HTTP client and so on). That library has a JVM build, `library-jvm`, and Seyir's `core`
module uses it. Everything a plugin imports from `com.lagradost.cloudstream3.*` therefore exists in Seyir with the same
signatures.

What a plugin imports from Android is another matter. Seyir's `core` contains small look-alikes of the few Android and
CloudStream-app classes that plugins touch:

| Provided by Seyir | Notes |
|---|---|
| `android.content.Context` | An empty stub. A plugin only sees it in `load(context: Context)`. Do not call anything on it. |
| `android.util.Log` | Writes to Seyir's log (`seyir.log`; in the CLI use `-Dseyir.log=debug`). |
| `android.util.Base64` | Same constants and methods as Android's. |
| `org.json` | The Android (AOSP) implementation, so `getString()` on a number behaves as on Android. |
| `com.lagradost.cloudstream3.plugins.Plugin` | The plugin entry-point base class. |
| `com.lagradost.cloudstream3.network.CloudflareKiller` | Detects Cloudflare challenges and hands them to Seyir's solver. |
| BouncyCastle crypto provider | Registered at startup, so Android-only cipher names such as `AES/CBC/PKCS7Padding` work. |

Because the signatures match the originals, the same source compiles for the JVM. Plugins are compiled against
`libs/seyir-api.jar` (the `core` jar) plus the CloudStream library, NiceHttp, jsoup, Jackson, coroutines, jspecify and
BouncyCastle. All of these are provided by Seyir at runtime, so they are not packed into the plugin jar.

### What is not supported

- **Any Android UI.** Fragments, activities, dialogs, settings screens, `R` resources, layouts, drawables. Remove them.
- **Android resources in general.** The build writes `requiresResources: false` into the manifest. A plugin that really
  needs resources is rejected at install time.
- **WebView-based extractors** such as `WebViewResolver`. There is no WebView on the desktop. Find the real API request
  instead (see [section 5](#5-runtime-gotchas)).
- **`context` is a stub.** No shared preferences, files directory, resources or system services.
- **Libraries that CloudStream plugins do not normally use and Seyir does not ship.** If it is not on the list above,
  it will not compile.

## 2. Plugin layout

A plugin is a folder. Seyir uses the CloudStream layout, so a CloudStream plugin folder drops in unchanged:

```
MySite/
  build.gradle.kts
  src/main/kotlin/com/example/mysite/
    MySitePlugin.kt        # @CloudstreamPlugin entry class
    MySite.kt              # MainAPI subclass
    MyExtractor.kt         # optional extractors
```

The folder name is the plugin's internal name. It is also the name of the produced jar (Turkish letters are replaced by
ASCII: `Filmİzlesene` becomes `FilmIzlesene.jar`).

### `build.gradle.kts` is read, never executed

The root build of the plugin repository reads the folder's `build.gradle.kts` with regular expressions. Nothing in it is
run. This is why a file written for the Android Gradle plugin can stay as it is.

```kotlin
version = 2

cloudstream {
    displayName = "My Site"                    // Seyir only, optional
    authors     = listOf("your-github-name")
    language    = "tr"
    description = "Short description of the site."
    status      = 1                            // 0: down, 1: ok, 2: slow, 3: beta (default 3)
    tvTypes     = listOf("Movie", "TvSeries")  // Movie, TvSeries, Anime, Documentary, Live ...
    iconUrl     = "https://example.com/favicon.ico"
}
```

Rules that follow from the regular expressions:

- Values must be plain literals: `version = 2`, `language = "tr"`, `authors = listOf("a", "b")`. A variable, `setOf(...)`
  or a computed string is not understood and falls back to the default (`version` 1, `status` 3, empty lists).
- Lines that start with `//` and `/* ... */` blocks are ignored, so commented-out values do not count. A `//` comment after
  a value is fine.
- A list written with `listOf(...)` must not contain `)` inside the strings.
- `displayName` is a Seyir-only addition. It is the name shown in the Plugins screen; when it is missing the folder name is
  shown. It may contain spaces and Turkish letters.
- Android-specific blocks (`android { }`, `dependencies { }`) can stay; they are not run.
- `version` is what the automatic updater compares. Raise it with every release.

### The entry class

The class annotated with `@CloudstreamPlugin` is the entry point. The build finds it by scanning `src/main/kotlin` for
the annotation and writes it into the jar's `manifest.json`. It calls `registerMainAPI` for every site provider and
`registerExtractorAPI` for every extractor the plugin ships:

```kotlin
package com.example.mysite

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class MySitePlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(MySite())
        // registerExtractorAPI(MyExtractor())
    }
}
```

One plugin can register several providers, for example one per TV channel.

Package names are free. Providers ported from nik-cloudstream use `com.nikyokki` or `com.keyiflerolsun`; new plugins in the
template use `com.seyir.<name>`. Two plugins may contain classes with the same name, because every plugin gets its own
class loader.

## 3. Porting step by step

### Prerequisites

- JDK 21 (Gradle can download it).
- Your own plugin repository (a copy of [`plugin-template/`](../plugin-template/)). It has the root build, the Gradle
  wrapper and `libs/seyir-api.jar`.
- A checkout of Seyir itself, to build the test CLI (`./gradlew :cli:installDist`), and mpv for playback tests.

### Steps

1. **Copy the plugin folder.** Take it from a CloudStream plugin repository (for example
   [nikyokki/nik-cloudstream](https://github.com/nikyokki/nik-cloudstream) or
   [keyiflerolsun/Kekik-cloudstream](https://github.com/keyiflerolsun/Kekik-cloudstream)) into the root of your plugin
   repository, next to the other plugin folders. Delete `AndroidManifest.xml`, `res/` and any settings fragment code; none
   of it is used.

2. **Check `build.gradle.kts`.** Make sure `version`, `language`, `description`, `authors`, `status`, `tvTypes` and
   `iconUrl` are plain literals as described in [section 2](#2-plugin-layout). Set `status = 1` once you have tested it.

3. **Check the entry class.** It must carry `@CloudstreamPlugin` and extend `Plugin`. Remove lines that assign a settings
   screen or a resources object.

4. **Remove Android-only code** (see [section 4](#4-compile-errors-and-how-to-fix-them)). In practice this is a few
   imports and the `Plugin` class body.

5. **Make sure every extractor is available.** An extractor the plugin uses must either live in the plugin folder and be
   registered with `registerExtractorAPI`, or be part of the CloudStream library (`loadExtractor(...)` resolves it by URL).
   If the CloudStream app repository kept the extractor in a shared module that is not in the library, copy its source into
   your plugin folder.

6. **Build.**

   ```bash
   ./gradlew makeRepo --continue
   ```

   `--continue` builds every plugin even when one fails; a plugin that does not compile is simply left out of
   `build/repo`. To build only yours, run `./gradlew :MySite:jar`. The output is `build/repo/` with `MySite.jar`,
   `plugins.json` and `repo.json`.

7. **Test with the Seyir CLI.** The provider name you pass is `MainAPI.name`, not the folder name; `cli list` prints it.

   ```bash
   # in the Seyir checkout, once
   ./gradlew :cli:installDist

   # list the providers found in your build
   JAVA_OPTS="-Dseyir.plugins=/path/to/your-plugins/build/repo" cli/build/install/cli/bin/cli list

   # search -> details -> links -> 8 seconds of silent mpv playback
   JAVA_OPTS="-Dseyir.plugins=/path/to/your-plugins/build/repo -Dseyir.play=8 -Dseyir.mpv=/usr/bin/mpv" \
     cli/build/install/cli/bin/cli probe MySite "search words"
   ```

   On Windows use `cli.bat` and set `JAVA_OPTS` in the environment. A working plugin ends with:

   ```
   OYNATMA OK <link name> q=<quality> (ses var)
   ```

   (`OYNATMA` is Turkish for playback; `ses var` means the stream has sound, `SES YOK` means it has none.) The
   individual steps are also available: `cli home`, `cli search`, `cli load <provider> <url>`,
   `cli links <provider> <episode-data>`. Add `-Dseyir.log=debug` to see the plugin's `Log` output, and
   `-Dseyir.cloudflare=true` if the site sits behind Cloudflare.

   If something fails, run `cli fetch <url>` first to see what the site returns today. Most failures come from the site
   having changed, not from the port.

8. **Test in the app.** In Seyir open Plugins -> **Install from file** (*Dosyadan kur*) and pick
   `build/repo/MySite.jar`. The provider appears in the provider menu and works like any other.

9. **Mark your changes.** Keep the upstream code recognizable by tagging every change with a
   `// Seyir:` comment. It helps the next person who merges upstream fixes.

## 4. Compile errors and how to fix them

| Error | Cause | Fix |
|---|---|---|
| `Unresolved reference: R` / `BuildConfig` | Android resources and generated constants | Remove the use. Put API keys or constants into a Kotlin `object`. |
| `Unresolved reference: androidx`, `android.app`, `android.widget`, `FragmentActivity`, `BottomSheetDialogFragment` | Android UI code, usually a settings screen | Delete the class and the lines that open it (`openSettings = ...`). |
| `Unresolved reference: AcraApplication`, `getKey`, `setKey`, `CommonActivity`, `showToast` | CloudStream app code, not part of the library | Remove, or replace with a private field. Persistent plugin settings are not supported. |
| `Unresolved reference: WebViewResolver` | WebView does not exist on the desktop | Rewrite the extractor to call the real API with `app.get`. See [section 5](#5-runtime-gotchas). |
| `Unresolved reference: <SomeExtractor>` | The extractor lived in another module of the CloudStream repository | Copy its source into the plugin folder and register it with `registerExtractorAPI`. |
| `Unresolved reference: <library>` for a non-CloudStream dependency | The library is not on Seyir's class path | Replace it with what the JDK, jsoup, Jackson or the CloudStream library offer. |
| Deprecation error on calls such as `ExtractorLink(...)` or `Episode(...)` | The pinned CloudStream library version differs from the one the plugin was written for | Use the current builders: `newExtractorLink(...)`, `newEpisode(...)`, `newMovieLoadResponse(...)`, `newTvSeriesLoadResponse(...)`, `newHomePageResponse(...)`. |
| `GradleException: ... @CloudstreamPlugin class not found` | The annotation is missing, or the file is not under `src/main/kotlin` | Move the entry class there and annotate it. |

Notes:

- `Log` is `android.util.Log`. The import compiles as is; output goes to Seyir's log.
- `app.get(...)` / `app.post(...)` is NiceHttp, with the same API as in CloudStream. Seyir installs its own client behind it,
  which splits TLS handshakes, uses DoH and takes a proxy, so plugin code needs no special handling.
- Kotlin warnings are suppressed on purpose, because code written for Android produces many harmless ones.

## 5. Runtime gotchas

These are problems met while porting CloudStream plugins.

**The episode `data` string.** `Episode.data` is handed back to `loadLinks` when the user plays the episode. Use a real
absolute URL where you can. If `data` is not an absolute URL (a bare id or a slug), `newEpisode` turns it into one with the
site's `mainUrl` (`"90511"` arrives in `loadLinks` as `"https://www.startv.com.tr/90511"`). When a plugin keeps an id in `data`, read it defensively, for example
`data.substringAfterLast('/')`, so it works with and without a prefix.

**Video-only links.** A link may carry separate audio tracks (`ExtractorLink.audioTracks`), which is what YouTube-style
sources need. Seyir passes them to mpv as external audio files. Playback is covered; downloads and casting do not yet merge
the separate audio track.

**Sites that move domains.** Many sites change domain and leave a redirect on the old one. Seyir's `DomainTracker`
follows the redirect, accepts it only when the new host looks like the same site (same "brand", HTTPS, not an IP address, not
a parked-domain page), updates `mainUrl` at runtime and remembers it. To benefit from this:

- declare `override var mainUrl` (not `val`);
- build URLs from `mainUrl` at call time where you can. Home-page request URLs declared at construction time with
  `mainPageOf("$mainUrl/...")` are rewritten by Seyir when the domain changed;
- when the site moves permanently, change `mainUrl` in the source and raise `version`.

**HTTP/2 resets on rapid paging.** Some sites reset the connection when many pages are requested in quick succession. Wrap
paging loops in a small retry (`runCatching`, one or two retries with a short delay) and stop at the first empty page.
`KanalD` does this with `runCatching { ... }.getOrDefault(emptyList())`.

**Cloudflare.** Many sites use Cloudflare challenges. Seyir detects a challenge on any request made through `app` and, if
*Cloudflare solving* is on in Settings (default), opens the system browser (Edge, Chrome, Chromium or Brave) through the
DevTools protocol until the challenge is passed, then reuses the cookies. In the CLI pass `-Dseyir.cloudflare=true`.
Plugins do not need their own challenge handling; remove interceptors that assume a particular warning text.

**Where the real data comes from.** A scraped page often hides the player in JavaScript. Use `cli fetch <url> [referer]` and the
browser's network tab to find the real API request or the JSON embedded in the page (JSON-LD, `__NEXT_DATA__`, a signed
`ajax` call), and request that from the plugin.

**Headers on the media request.** If a CDN needs a `Referer` or `Origin`, set them on the `ExtractorLink` (`referer`,
`headers`). The local relay sends exactly those headers, and retries a failing segment once with the master playlist as the
`Referer`.

## 6. Writing a plugin from scratch (KanalD walkthrough)

[`plugin-template/KanalD/`](../plugin-template/KanalD/) is a small, complete provider for kanald.com.tr (free, legal,
unprotected HLS). Open `KanalD.kt` next to this section.

**1. Declare the provider.**

```kotlin
class KanalD : MainAPI() {
    override var mainUrl = "https://www.kanald.com.tr"
    override var name = "Kanal D (örnek)"
    override val hasMainPage = true
    override var lang = "tr"
    override val supportedTypes = setOf(TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/diziler" to "Güncel Diziler",
        "$mainUrl/diziler/arsiv" to "Arşiv Dizileri",
        "$mainUrl/programlar" to "Programlar",
    )
```

`name` is the string users and the CLI see, and it must be unique across all installed plugins: when two plugins register the
same `name`, Seyir keeps the one from the higher plugin `version` (then the one loaded first) and logs a warning. The template's
sample is called `Kanal D (örnek)` for this reason; give your own provider a distinctive name. `mainPage` lists the home-page
rows as `url to title`.

**2. Listing (`getMainPage`).** Fetch the row's URL with `app.get(...)`, which returns a response whose `.document` is a jsoup
`Document`, select the cards and turn each into a `SearchResponse` with `newTvSeriesSearchResponse(title, url, TvType.TvSeries)`.
KanalD adds a `?page=N` query for the archive row only and returns `hasNext = false` for the others.

**3. Search (`search`).** The site's own search returns episode cards without a vertical poster, so KanalD reads the
whole series catalog once, caches it in a field, and filters by title (ignoring Turkish letter differences), and falls back to
the site search when nothing matches. Choose whichever approach gives better results for your site.

**4. Details with seasons (`load`).** Read the series page for title, poster, backdrop and plot (`og:` meta tags,
poster images). Then read `/<series>/bolumler`, collect the season links (`activeSeasonNumber=N`), and for each season walk
its pages until a page adds no new episode. Each episode is created with `newEpisode(url) { name; season; episode; posterUrl }`,
using the episode page URL as `data`. The result is `newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) { posterUrl;
backgroundPosterUrl; plot }`. For a movie use `newMovieLoadResponse(title, url, TvType.Movie, dataUrl)`.

**5. Links (`loadLinks`).** The episode page embeds JSON-LD that contains a `contentUrl` pointing to the HLS playlist.

```kotlin
val html = app.get(data).text
val path = Regex(""""contentUrl"\s*:\s*"([^"]+\.m3u8[^"]*)"""").find(html)?.groupValues?.get(1)
    ?.replace("\\/", "/") ?: return false
callback(
    newExtractorLink(name, name, stream, ExtractorLinkType.M3U8) {
        referer = "$mainUrl/"
        quality = Qualities.Unknown.value
    }
)
return true
```

Older episodes carry a relative path; the page's `MediaCdnDomainUrl` supplies the root. `loadLinks` returns `true` if it
found a link. Subtitles go through `subtitleCallback(SubtitleFile(lang, url))`. For third-party hosts, call
`loadExtractor(url, referer, subtitleCallback, callback)` and let the library's extractors resolve the player.

**6. The entry class and metadata.** `KanalDPlugin.kt` registers `KanalD()`; `KanalD/build.gradle.kts` holds `version`,
`displayName`, `description`, `tvTypes` and so on, as described in [section 2](#2-plugin-layout).

**To start your own:** copy the template, rename the `KanalD` folder (and the package and classes), edit the metadata, rewrite
`KanalD.kt`, then build and test as in [section 3](#3-porting-step-by-step). Test each function separately with the CLI
(`home`, `search`, `load`, `links`) before running `probe`.

## 7. Publishing

### Repository layout

A plugin repository is a GitHub repository whose root looks like this:

```
build.gradle.kts        # root build: reads the plugin folders, writes build/repo
settings.gradle.kts     # every folder with a build.gradle.kts is a plugin
gradle.properties       # excludedPlugins
gradlew, gradlew.bat, gradle/
libs/seyir-api.jar      # the API plugins compile against
MySite/
OtherSite/
```

The easiest start is to copy [`plugin-template/`](../plugin-template/) into a new repository.

### Build and publish

```bash
./gradlew makeRepo --continue   # build/repo/: <Name>.jar, plugins.json, repo.json
./gradlew publishRepo           # force-pushes build/repo to the "builds" branch of origin
```

`publishRepo` builds first, then makes a one-commit history from `build/repo` and force-pushes it to the `builds` branch of
the `origin` remote, so `builds` never grows. The template also contains `.github/workflows/build.yml`, which does the same
on every push to `main` where GitHub Actions is available. Where it is not (a billing problem, for example), run
`publishRepo` locally.

### How users add your plugins

In Seyir: Plugins -> Repositories, then paste one of:

| Input | What Seyir reads |
|---|---|
| `https://github.com/<user>/<repo>` | `https://raw.githubusercontent.com/<user>/<repo>/builds/repo.json` |
| a direct `repo.json` URL | the CloudStream-style manifest with `pluginLists` |
| a direct `plugins.json` URL | the plugin list itself |
| the URL of a single `.jar` (or `.cs3`) | that one plugin is installed |

Relative `url` values inside `plugins.json` are resolved against the file that lists them. **Install from file** installs a
single downloaded `.jar`. To test a build locally without publishing, serve `build/repo` (`python -m http.server 8765`) and add
`http://127.0.0.1:8765/repo.json`.

**Hosting outside GitHub.** A repository is only static files: `build/repo` contains the `.jar` files, `plugins.json`
and `repo.json`. Upload that folder to any web server (HTTPS recommended) and give users the address of `repo.json`,
for example `https://example.com/seyir/repo.json`. Install, updates and the selection dialog work the same way; only
the `github.com/<user>/<repo>` shorthand and the GitHub token are GitHub-specific.

### Integrity and trust

A plugin jar is arbitrary code, so Seyir is strict about where it comes from:

- **`sha256` (optional, per entry in `plugins.json`).** The lowercase hex SHA-256 of the file named by `url`. `makeRepo`
  writes it for every plugin. When the field is present, Seyir hashes the downloaded bytes and refuses to install on a
  mismatch (a malformed value counts as a mismatch); when it is absent the plugin installs as before.
- **Validation before replacing.** Before an installed jar is replaced, the new file must open as a zip, contain a readable
  `manifest.json`, and contain the entry class named by `pluginClassName`. Otherwise the installed version stays untouched.
- **HTTPS only.** Repository and plugin URLs must be `https://`, also after redirects. `http://` is accepted only for
  `localhost`, `127.0.0.1` and `::1` (local development). Local file paths are accepted.
- **Source pinning.** Seyir remembers which repository each plugin was installed from and updates it only from that
  repository, never from another repository that lists the same internal name. A plugin installed from a file or a single
  URL is not tied to any repository and is not updated automatically.

### Private repositories

For a private GitHub repository, users enter a GitHub access token in Seyir -> Settings. The token is sent only to GitHub hosts
(`github.com`, `raw.githubusercontent.com`, `api.github.com`, `objects.githubusercontent.com`) and never to a plugin's site.
When the repository cannot be found, Seyir says so and points at the token.

### Versioning and updates

Seyir installs a plugin when the repository lists a higher `version` than the installed one. At startup, when *automatic plugin
updates* is on (default), installed plugins are updated from the repository each one was installed from. Plugins that are not
installed are never installed automatically. **Raise `version` in the folder's `build.gradle.kts` with every change you publish.**

### Excluding plugins

A folder can stay in the repository but be skipped by the build, for example while a site is down. List the folder names in
`gradle.properties`:

```properties
# Closed sites, with the reason as a comment
excludedPlugins=OldSite,Filmİzlesene
```

`gradle.properties` is a Java properties file, so Turkish letters must be written as Unicode escapes: `İ` = `İ`,
`ı` = `ı`, `ş` = `ş`, `ç` = `ç`, `ğ` = `ğ`, `ö` = `ö`, `ü` = `ü`. A trailing `\` continues the list
on the next line.

## 8. Experimental: installing `.cs3` plugins directly

> **Experimental.** Source porting (sections 3 and 4) is the recommended route. It is faster at runtime, you can fix site
> changes, and the result is a normal Seyir plugin.

CloudStream distributes plugins as `.cs3` files: a zip with `classes.dex` (Android bytecode) and `manifest.json`. Seyir can
convert such a file to a JVM jar with [dex2jar](https://github.com/ThexXTURBOXx/dex2jar) while installing:

- **Install from file** accepts `.cs3` next to `.jar`.
- A repository added by URL may be a CloudStream repository whose plugin list points at `.cs3` files, and the Repositories
  box also takes the URL of a single `.cs3`.
- From the command line: `cli cs3 <in.cs3> <out.jar>` converts a file without installing it.

The converter copies the manifest and the converted classes; Android resources (`resources.arsc`, `res/`) are not carried over,
and a plugin whose manifest says `requiresResources: true` is rejected.

It works for many plugins. In our test, DMax installed from its `.cs3` behaved the same as the source-ported plugin. It is not
guaranteed, so Seyir checks in two stages:

1. **Static check (before installing, no plugin code runs).** Every class the plugin references is looked up in the plugin
   itself, in the JDK and in Seyir's own classpath. If any is missing (typically an Android UI class or a CloudStream built-in
   extractor Seyir does not provide), the install is refused and the first missing classes are named. In the repository picker,
   `.cs3` entries are downloaded, converted and checked in the background; incompatible ones are marked and cannot be
   selected. `cli compat <file>` runs the same check from the command line.
2. **Functional test ("Test" on the Plugins screen, installed plugins only).** It runs the plugin: home page or search,
   details, links, then a short request to the first link. The result is green (everything works), yellow (search and details
   work but no playable link was verified) or red (it failed early). Plugins installed from the picker are tested
   automatically; nothing is ever removed without confirmation.

If a converted plugin still misbehaves, port its source instead.

## 9. Credits and licensing

CloudStream plugins are usually licensed under the GPL-3.0. When you port one:

- keep the original author's header and a link to the upstream repository in every file you copy;
- keep the license of the upstream code. Plugin repositories derived from CloudStream plugins are usually GPL-3.0 and must
  keep that license and the author headers;
- say what you changed (the `// Seyir:` comments mentioned above).

The CloudStream library and plugin API are the work of [recloudstream](https://github.com/recloudstream/cloudstream).
Each plugin repository credits the authors of the providers it contains.

Plugins run code on the user's computer. Do not publish a plugin that collects user data or does more than the site itself
asks for. A plugin should only read publicly reachable pages and must respect the rights of the content it accesses; that
responsibility lies with the plugin author.
