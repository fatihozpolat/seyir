# Third-party notices

Seyir is licensed under the GNU General Public License v3.0 (see [LICENSE](LICENSE)). It uses the following
third-party software. Each component keeps its own license; the source code of every component is available at the
linked project.

## Libraries

| Component | License | Project |
|---|---|---|
| CloudStream library (`library-jvm`) | GPL-3.0 | https://github.com/recloudstream/cloudstream |
| NiceHttp | GPL-3.0 | https://github.com/Blatzar/NiceHttp |
| NewPipe Extractor | GPL-3.0 | https://github.com/TeamNewPipe/NewPipeExtractor |
| dex2jar (`de.femtopedia.dex2jar`) | Apache-2.0 | https://github.com/ThexXTURBOXx/dex2jar |
| OkHttp, Okio | Apache-2.0 | https://github.com/square/okhttp |
| Jackson | Apache-2.0 | https://github.com/FasterXML/jackson |
| jsoup | MIT | https://jsoup.org |
| Kotlin, kotlinx.coroutines | Apache-2.0 | https://github.com/JetBrains/kotlin |
| Compose Multiplatform (Desktop) | Apache-2.0 | https://github.com/JetBrains/compose-multiplatform |
| Coil | Apache-2.0 | https://github.com/coil-kt/coil |
| JNA | Apache-2.0 / LGPL-2.1 | https://github.com/java-native-access/jna |
| Bouncy Castle | MIT (Bouncy Castle Licence) | https://www.bouncycastle.org |
| JSpecify | Apache-2.0 | https://jspecify.dev |
| `org.json` (Android implementation, in `core/src/main/java/org/json`) | Apache-2.0 | https://android.googlesource.com/platform/libcore |

The classes under `core/src/main/kotlin/android/**` and `core/src/main/kotlin/com/lagradost/**` reproduce the public
signatures of Android and CloudStream APIs so that CloudStream plugins compile for the JVM.

## Bundled in the Windows package

| Component | License | Project |
|---|---|---|
| mpv (build by shinchiro/mpv-winbuild-cmake) | GPL-2.0-or-later (this build) | https://mpv.io, https://github.com/shinchiro/mpv-winbuild-cmake |
| Java runtime (jlink image of the JDK used for packaging) | GPL-2.0 with Classpath Exception | https://openjdk.org |

On Linux, Seyir uses the system mpv.

## Player interface (all platforms)

Seyir opens videos with its own interface, built on these scripts (in `app/resources/common/mpv-ui`, used through
mpv's `--config-dir`). They are included unmodified, except that uosc's `bin/` folder (ziggy helper binaries) is left out.

| Component | Version | License | Project |
|---|---|---|---|
| uosc | 5.13.0 | LGPL-2.1 ([text](app/resources/common/mpv-ui/licenses/uosc-LGPL-2.1.txt)) | https://github.com/tomasklaen/uosc |
| thumbfast | commit 0f711de (2026-06-28) | MPL-2.0 ([text](app/resources/common/mpv-ui/licenses/thumbfast-MPL-2.0.txt)) | https://github.com/po5/thumbfast |
| Inter (Regular, Bold) | 4.1 | SIL Open Font License 1.1 ([text](app/resources/common/mpv-ui/licenses/Inter-OFL.txt)) | https://rsms.me/inter/ |

## Fonts

| Component | License | Project |
|---|---|---|
| Inter 4.1 | SIL Open Font License 1.1 ([text](app/src/main/resources/fonts/Inter-OFL.txt)) | https://rsms.me/inter/ |

## Plugins

Seyir ships without plugins. Plugins are installed by the user from plugin repositories; each plugin repository states
its own license and authors.
