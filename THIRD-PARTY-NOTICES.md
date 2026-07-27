# Third-party notices

MetroMusic itself is under the **GNU General Public License, version 3** — see [LICENSE](LICENSE).
The material below belongs to other people and keeps its own terms. Everything here is compatible
with GPLv3; the notes say why, because two of these are the reason the version is 3 and not 2.

## Dependencies

| Dependency | Licence | Copyright |
| --- | --- | --- |
| `io.github.diffechento:metro` | MIT | Diffechento |
| `androidx.media3:*` | Apache-2.0 | The Android Open Source Project |
| `androidx.compose:*` | Apache-2.0 | The Android Open Source Project |
| `androidx.core`, `lifecycle`, `activity`, `concurrent` | Apache-2.0 | The Android Open Source Project |
| `org.jetbrains.kotlinx:*` | Apache-2.0 | JetBrains s.r.o. and contributors |
| `net.jthink:jaudiotagger` | LGPL-2.1 | Paul Taylor and contributors |

### Apache-2.0, and why this is GPLv3

Most of the stack — AndroidX, Compose, Media3, kotlinx — is Apache-2.0. Apache-2.0 is **compatible
with GPLv3 and incompatible with GPLv2**: its patent and indemnity provisions count as further
restrictions under version 2, while version 3 accommodates them. A GPLv2-only MetroMusic could
therefore not be distributed at all, which is why the licence here is v3.

### MangoTile

The UI framework this app is built on, MIT-licensed, and permissive licences combine into a GPL
work without friction. It bundles the Selawik typeface (Copyright 2015 Microsoft Corporation,
Reserved Font Name Selawik) under the SIL Open Font License 1.1, so a MetroMusic binary carries
those fonts as well. The OFL permits bundling with software under any licence provided its notice
travels along; see that project's `THIRD-PARTY-NOTICES.md` and `licenses/Selawik-OFL-1.1.txt`.

### jaudiotagger

Reads and writes the tags inside audio files, which is the only kind of metadata edit MediaStore
lets stick — see `MetadataWriter`. It is LGPL-2.1 and used unmodified, as a library.

Under GPLv3 this needs no special handling: section 3 of the LGPL-2.1 itself permits a recipient to
apply the terms of the ordinary GPL, "version 2 or any later version", to a copy of the library, so
the combination is a straightforward GPLv3 work. (Under a permissive licence it would have been the
one dependency demanding a deliberate decision, because the LGPL's relink obligation is awkward on
Android, where everything is compiled statically into the APK. Copyleft makes that question go
away.) Upstream: <https://bitbucket.org/ijabz/jaudiotagger>

## Services

MetroMusic can talk to two third-party web services. Both are optional, both are switchable off,
and neither is needed to play what is on the device.

| Service | Used for | Notes |
| --- | --- | --- |
| Last.fm | scrobbling, and covers for albums whose files have none | Needs an application key and secret of your own. [API](https://www.last.fm/api) · [Terms](https://www.last.fm/api/tos) |
| Genius | song lyrics, read from the public site | No API token is used |

Neither service is affiliated with this project, and neither endorses it. Lyrics are fetched for
personal use and are not redistributed by the app; if you ship builds to other people, satisfy
yourself that this is what you intend.

## Trademarks

MetroMusic is not affiliated with Microsoft, Last.fm or Genius. "Windows Phone" and "Metro" are
Microsoft's. This project is an homage to a design language, written from scratch.
