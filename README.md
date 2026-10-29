# Seyir

**English** · [Türkçe](README.tr.md)

**Seyir is a plugin-based desktop media app for Windows and Linux.** You add plugin repositories, browse and search
every installed provider at once, and play movies, TV series and live channels in mpv or cast them to Google TV and
Chromecast. It runs CloudStream-compatible plugins, including CloudStream `.cs3` files (experimental).

Seyir is written in Kotlin with Compose Desktop. The app contains no site code of its own: what you can watch depends
entirely on the plugins you install. Plugins use the CloudStream plugin API, so most CloudStream providers can be
ported with little or no change to their source. Plugins live in separate repositories that you add yourself.

## Features

- **Search across providers.** One query runs against every enabled provider at once and results appear provider by
  provider as they arrive. A searchable provider filter narrows the search to one site.
- **Home rows.** A featured banner and poster rows from the selected provider, plus a "Continue watching" row across all
  providers.
- **Library.** Continue watching with the exact position, watch history, and **My List**.
- **Downloads.** Plain files are fetched directly; HLS streams are saved with ffmpeg.
- **Built-in player.** Video plays inside the Seyir window on mpv, with Seyir's own controls: seek previews, subtitle,
  audio, quality, speed and source menus, an episode list, previous/next episode and an "up next" card near the end.
  Subtitles are added with their titles and your preferred language is selected; their size can be changed. Video-only
  streams (for example YouTube) are played with their separate audio track. A separate mpv window can be used instead
  (Settings); on Linux this is the default until the built-in player is verified there.
- **Casting.** Send a stream to Google TV or Chromecast on your network. The app finds receivers with mDNS; you can also
  enter an address by hand in Settings. Pausing and seeking with the TV remote are reflected in the app.
- **Plugin repositories.** Add a repository by its GitHub URL, `repo.json` or `plugins.json`. Install, update and remove
  plugins from the Plugins screen, or install a single plugin from a `.jar` / `.cs3` file or URL.
- **Automatic plugin updates.** At startup, installed plugins are updated from your repositories when a newer `version`
  is published, and plugins added to a repository later are installed; plugins you removed stay removed (both can be
  turned off in Settings). An installed plugin that was dropped from its repository is marked on the Plugins screen.
- **Private repositories.** A GitHub token in Settings is used to read private plugin repositories. It is sent only to
  GitHub hosts.
- **Optional TMDB metadata.** With a TMDB key in Settings, a missing poster, backdrop, overview, year or rating is filled
  in from TMDB (only when the match is unambiguous; what the plugin provides always wins). Off by default.
- **Keyboard and screen readers.** Everything works with the keyboard (Tab, Enter/Space, Esc or Alt+Left to go back,
  Ctrl+F or `/` to search) with a visible focus ring, and controls carry labels for screen readers.
- **Cloudflare challenges.** Challenges are solved with your system browser (Edge, Chrome, Chromium or Brave). The
  browser starts off-screen and becomes visible only if the challenge is not passed automatically.
- **Privacy and censorship resistance.** Encrypted DNS (DNS over HTTPS: Cloudflare, Google or AdGuard), TLS ClientHello
  fragmentation (the first TLS record is split in the middle of the SNI, a common anti-DPI technique), and an optional
  SOCKS/HTTP proxy.
- **Domain tracking.** When a site moves to a new domain and leaves a redirect, Seyir follows it and updates the plugin's
  address.

## Installation

Packages are published on the [GitHub Releases](https://github.com/fatihozpolat/seyir/releases) page of this
repository.

v1.0.0 ships Windows packages only; the Linux packages below will be added once they are verified. Until then
Seyir can be run from source on Linux (see "Building from source" below).

| System | Package | Notes |
|---|---|---|
| Windows 10/11 | `.msi` or `.exe` | Installs per user, no administrator rights needed. **mpv is bundled.** |
| Debian / Ubuntu | `.deb` | `sudo apt install ./seyir_*.deb` (declares mpv as a dependency; apt installs it) |
| Fedora | `.rpm` | `sudo dnf install ./seyir-*.rpm` (requires mpv, which comes from RPM Fusion on Fedora) |
| Other Linux | `seyir-linux-x64.tar.gz` | Extract and run `Seyir/bin/Seyir`. Install mpv with your package manager. |

Windows packages are not code-signed; if SmartScreen shows "Windows protected your PC" on first install, choose
**More info → Run anyway**. Later updates come through the app and are verified with Seyir's own signature
(Ed25519, `SHA256SUMS.sig`); an update whose signature does not match is not installed.

On Linux install mpv with your package manager (`sudo apt install mpv`). Downloading HLS streams needs `ffmpeg` on
`PATH`. Cloudflare solving needs Edge, Chrome, Chromium or Brave.

Data is stored in `%APPDATA%\Seyir` on Windows and `~/.local/share/seyir` on Linux (settings, history, cache,
`seyir.log`). Downloads go to `~/Downloads/Seyir`.

## First run

Seyir ships with no plugins and no built-in repository address. On first launch the home screen shows a text field:
paste the address of a plugin repository and press **Add and install** ("Ekle ve kur" in the Turkish interface). The
**Open the plugins screen** link ("Eklentiler ekranını aç") takes you to the Plugins screen instead. You can add a
repository later from **Plugins (Eklentiler) -> Repositories (Depolar) -> paste a URL**. Example:

```
https://github.com/<user>/<repo>
```

What you can paste:

- **A GitHub repository address** (`https://github.com/<user>/<repo>`). Seyir looks for the repository list in this
  order: `builds/repo.json` on the `builds` branch, then `repo.json` on `master` or `main`, then `builds/plugins.json`.
- **A direct `repo.json` or `plugins.json` URL.**
- **A single plugin URL** ending in `.jar` or `.cs3`.
- **A file on disk**, with **Install from file** on the Plugins screen.

If the repository is private, enter a GitHub token in Settings first. The token is sent only to GitHub hosts.

## How it works

```
plugin repository (GitHub "builds" branch: repo.json, plugins.json, <Name>.jar)
        |  install / update (Plugins screen)
        v
%APPDATA%\Seyir\plugins\*.jar           each jar has a manifest.json (entry class, version)
        |  PluginLoader: one URLClassLoader per plugin
        v
core
  |- android/*, com/lagradost/*   look-alike Android and CloudStream classes
  |                               (plugins compile and run on the JVM without Android)
  |- net/                         TLS record splitting, DoH, proxy, Cloudflare handling
  |- relay/                       local relay: playlist rewriting, headers, CONNECT tunnel, LAN gate for casting
  |- player/                      mpv process and IPC (position, subtitles, embedded player state)
  |- cast/                        Google Cast discovery and protocol
  |- browser/                     Cloudflare solver over the Chrome DevTools Protocol
  `- DomainTracker                follows site moves
        |
        v
app (Compose Desktop)   /   cli (command-line test tool)
```

Playback pipeline: a plugin finds a link, the link is registered with the local relay, and mpv opens
`http://127.0.0.1:<port>/...`. The relay makes every upstream request with the app's own HTTP client (DoH, ClientHello
fragmentation, Cloudflare handling) and the headers the plugin asked for, rewrites HLS playlists, and serves segments disguised as `.png` as `.ts`. If a segment
server answers 403, the relay retries once with the master playlist as the `Referer`, like a browser player would.
Casting uses a separate LAN listener of the same relay that accepts only GET/HEAD/OPTIONS for the current session.

## Building from source

Requirements: JDK 21 (Gradle finds or downloads it through its toolchain) and git.

```bash
./gradlew :app:run                  # run the app
./gradlew :app:createDistributable  # app image under app/build/compose/binaries/main/app
./gradlew :app:packageMsi :app:packageExe        # Windows packages
./gradlew :app:packageDeb :app:packageRpm        # Linux packages (on Linux)
./gradlew :core:test                # unit tests (add -PmanualTests for tests that need network or windows)
```

For the Windows packages, download the bundled mpv first with `powershell -File scripts/fetch-mpv.ps1`. Releases are
built, signed and published locally with `pwsh scripts/release.ps1 -Version <x.y.z> -NotesFile <notes.md>`; the app
only installs an update whose `SHA256SUMS` carries a valid `SHA256SUMS.sig` (Ed25519, base64) and whose installer
matches its checksum. The version lives in `gradle.properties` (`seyirVersion`).

To run the tests before every push, enable the repository hook once: `git config core.hooksPath .githooks`
(it runs `scripts/verify.sh`; `scripts/verify.ps1` does the same on Windows).

### Command-line tool

The `cli` module loads plugins without the UI and is the quickest way to test a plugin.

```bash
./gradlew :cli:installDist
cli/build/install/cli/bin/cli list                                  # providers of the installed plugins
cli/build/install/cli/bin/cli home   "Kanal D"                     # first home-page rows
cli/build/install/cli/bin/cli search DMax usta                      # search one provider
cli/build/install/cli/bin/cli load   DMax <series-url>              # details and episode list
cli/build/install/cli/bin/cli links  DMax <episode-data>            # video links
cli/build/install/cli/bin/cli probe  "Kanal D" "uzak şehir"         # search -> details -> links (-> mpv)
cli/build/install/cli/bin/cli fetch  <url> [referer]                # raw page, to see what a site really returns
cli/build/install/cli/bin/cli cast-scan                             # list Google Cast receivers on the network
cli/build/install/cli/bin/cli cs3    <in.cs3> <out.jar>             # convert a CloudStream .cs3 (experimental)
cli/build/install/cli/bin/cli compat <plugin.jar|plugin.cs3>        # static check: would it run in Seyir? (runs no plugin code)
```

By default the CLI loads the plugins installed by the app. Use `-Dseyir.plugins=<dir>` to load a directory of jars, for
example `-Dseyir.plugins=<plugin repo>/build/repo` for a plugin repository you built yourself. With `-Dseyir.play=<seconds>` and `-Dseyir.mpv=<path>`, `probe` plays
the best three links silently in mpv and prints `OYNATMA OK` or `OYNATMA HATA` (playback OK / failed).

On Windows use `cli.bat`. Options are passed through `JAVA_OPTS`:

| Option | Meaning |
|---|---|
| `-Dseyir.plugins=<dir>` | Directory with plugin jars (default: the app's plugin directory) |
| `-Dseyir.log=debug` | Show plugin logs (`verbose/debug/info/warn/error`) |
| `-Dseyir.cloudflare=true` | Solve Cloudflare challenges with the system browser |
| `-Dseyir.fragment=false` | Turn off ClientHello splitting |
| `-Dseyir.doh=false` | Use system DNS instead of DoH |
| `-Dseyir.proxy=socks5://127.0.0.1:40000` | Proxy (for example Cloudflare WARP in proxy mode) |
| `-Dseyir.data=<dir>` | Data directory |
| `-Dseyir.play=<s>` / `-Dseyir.mpv=<path>` | Play-test duration for `probe` / path to mpv |

## Writing plugins

Plugins use the CloudStream plugin API, so a CloudStream plugin can usually be ported by copying its folder and
removing Android-only code.

- Step-by-step porting guide: [docs/porting-cloudstream-plugins.md](docs/porting-cloudstream-plugins.md)
- Ready-to-build starter project with a working example (Kanal D): [plugin-template/](plugin-template/)

After a change to the compatibility layer that plugins can see, run `./gradlew syncPluginTemplate`. It copies the API jar
(`seyir-api.jar`), the version catalog and the Gradle wrapper into `plugin-template/` and, with
`-PpluginRepoDir=<folder>`, into a plugin repository of your own.

## Project layout

```
core/             plugin loader, Android/CloudStream look-alikes, network, relay, mpv, Cast, Cloudflare solver
app/              Compose Desktop application (screens, library, downloads, plugin manager)
cli/              command-line test tool
plugin-template/  starter project for new plugins (example: KanalD)
docs/             porting guide
packaging/linux/  Docker build for the Linux packages
scripts/          fetch-mpv.ps1 (downloads the mpv bundled in the Windows package)
```

## Known limitations

- Plugins depend on the websites they read. When a website changes, a plugin can stop working until its author
  publishes an update; Seyir installs plugin updates from the repositories you added automatically.
- Servers that are unreachable from your network need a proxy (set one in Settings).
- Seyir contains no content and no site code. What plugins access, and whether that content is licensed, is the
  responsibility of the plugin author and the user; Seyir only provides the player and the plugin system.

## Credits

- [CloudStream](https://github.com/recloudstream/cloudstream) (recloudstream): the plugin API and `library-jvm` that
  Seyir builds on.
- [mpv](https://mpv.io): playback.
- [Inter](https://rsms.me/inter/) (SIL OFL 1.1): the interface font.

## License

Seyir is licensed under the GNU General Public License v3.0 (see [LICENSE](LICENSE)). Third-party components and their
licenses are listed in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
