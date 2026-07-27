# MetroMusic

A music player for Android in the Windows Phone 8 style, built on
[MangoTile](https://github.com/Diffechento/MangoTile).

It plays the music already on your phone. It groups it by album, artist and genre, it looks and
moves like the Music + Videos hub did, and it has no account, no feed, no video and nothing to
subscribe to. Everything that talks to the network is optional and switchable off, and none of it
is needed to play a file.

minSdk 26 · compileSdk 36 · one `:app` module · Kotlin and Compose throughout.

## What it does

**The library is the start screen.** There is no "library" page you tap into: the panorama you
land on *is* it. Its sections are artists, albums, songs, genres, `more`, history and settings, so
anything is one sideways swipe from launch. The panorama is circular, which is what lets settings
sit at the far end — one swipe to the *left* of where you start — without giving them a place among
the things you actually came for. The order of the sections is yours, from settings → interface.

**Long lists behave like the phone's.** `MetroLongList` groups alphabetically and opens the WP8
jump grid: tap a letter, the alphabet zooms out over the whole screen, pick another, and you land
there. It buckets Cyrillic as well as Latin and shows whichever alphabets your library actually
uses.

**Search is the section header.** Tap the header of artists, albums or songs and a box opens
inside that section. The query belongs to that list — swipe to the next section and you do not
carry a stale filter along; tap the header again and it is gone.

**Album, artist, genre and playlist pages.** Cover, the numbers, the track listing in disc and
track order. The cover *flows* out of the list into the page and on into the player rather than
cross-fading (continuum). An artist's page lists their albums and then everything of theirs.

**Now playing** is full-screen artwork with parallax, rising out of the mini player rather than
arriving as a new page. Drag sideways past a quarter of the width to change track; push it down to
send it away. The scrubber seeks on release, not on every pixel. Lyrics are one tap from it.

**The queue remembers where it put the last one.** `play next` on an album, artist, genre or track
inserts after the block you queued *before*, not always directly after the current track — so
queueing three albums plays them in the order you picked them instead of in reverse. Nothing else
on screen changes when you do it, so a banner drops from the top to say what went in and how much
of it.

**Playlists and favourites.** Create, rename, delete, reorder by dragging the grip. Favourites are
a permanent pseudo-playlist. Both are a few kilobytes of JSON in `filesDir`.

**Hiding.** Long-press an artist or an album and hide it. It leaves every list, every search
result and everything a playlist resolves to at once, because hiding happens in one place — the
tracks are filtered and the indices rebuilt upward from them. Two rules then fall out for free:
hiding an artist hides their albums and songs, and an artist whose albums you hid one by one
disappears too. Settings → hidden lists what is hidden; a tap puts it back.

**Editing metadata** writes the tags *inside the files*, for every track of the album, asking for
the platform's write consent once. MediaStore's own metadata columns look writable and are not:
from Android 10 the store treats them as derived from the file and drops the update with no error
and no rows-affected of zero. Tags are the only level where "save" means anything.

**Names that real files spell badly.** A tag saying `Daft Punk feat. Todd Edwards` is two artists,
and filed under the string as it stands it is neither of them. One credit is split on commas,
semicolons, ampersands and the feature words, and the track is filed under *every* name — while
the credit you see stays exactly as the file spells it. Genre tags that differ only in case or
edge punctuation are folded onto the spelling the most tracks use, and you can merge the rest by
hand from a genre's long-press menu. Both are switchable, because the separators are also
punctuation inside real band names.

**Playback** runs on Media3 in a `MediaSessionService`, so the notification, lock screen, headset
buttons, Bluetooth controls and audio focus all work like any other player and playback survives
the activity. There is an equalizer and bass boost over the device's own bands, a sleep timer that
fades out, and a minimum clip length that keeps ringtones out of the library.

**Volume** gets a WP8 banner instead of Android's panel, inside the app — the activity takes the
key events before the framework's default handling. From the lock screen you get Android's, and
that is the honest limit of it.

**Looks.** All twenty WP8 accents, light and dark, optionally following the system. One backdrop
behind every page — your own colour, the gradient, or the playing album's cover — so navigating
does not change the wallpaper. Every gesture the app adds on top of tapping has a switch, because
a gesture that fires by accident is worse than no gesture. There is a home-screen live tile.

**Text.** All of it is in `strings.xml`, counts go through `<plurals>`, and adding a language is
one `values-xx/` folder and nothing else. See the caveat at the bottom.

### Optional, network, and all failing soft

**Scrobbling to Last.fm.** Every play is written to disk *first*, carrying the wall-clock second
the track started, and only then is a send attempted — so offline is not a special case, it is the
ordinary path with the request failing. That timestamp is the point: Last.fm files a scrobble at
the time it says, so an hour underground turns up in the right order and at the right hour once
the phone surfaces, instead of as a burst at the moment of reconnection. The queue flushes when a
*validated* network appears (joining a wifi is not the same as that wifi reaching the internet),
when credentials appear, from a backing-off timer for what the system does not report, or from the
settings page, where the waiting count is a row you can tap. Signing in is Last.fm's own web page
in a `WebView`, so nothing here ever sees a password.

**Missing album art**, for albums whose files carry no cover: asked about once, remembered, and
kept as a file so two albums with the same cover share one. "No cover" and "could not ask" are
different answers and only the first is remembered — one morning behind a captive portal must not
write a permanent "no artwork" across a library.

**Lyrics** come from Genius with no API token, read out of the page. A token identifies an
application, cannot be committed, and would make the feature dead on arrival for anyone building
this from source. Reading HTML is fragile by nature, so every step distinguishes *"no lyrics"*
from *"could not ask"*.

## Building

The framework comes from Maven Central like any other dependency, so a clone builds as it stands:

```
./gradlew :app:assembleDebug
```

You only need MangoTile checked out if you are *changing* it. Then publish it locally and make
sure `mavenLocal()` comes first in `dependencyResolutionManagement.repositories`:

```
cd ../MangoTile
./gradlew :metro:publishToMavenLocal      # -> io.github.diffechento:metro:1.0.0
```

Republish after every change to the framework — a fixed version has no snapshot magic, so a change
over there is invisible here until you do.

**Last.fm credentials** are optional. Copy `local.properties.example` over `local.properties`, or
add the two lines to the one Android Studio wrote for you:

```
LASTFM_API_KEY=…
LASTFM_API_SECRET=…
```

They reach the code through `BuildConfig`, and a key typed into the settings page wins over them.
Without them the app builds and runs; scrobbling and the art lookup behave as if switched off, and
the Last.fm page asks for a key. Get a pair at <https://www.last.fm/api/account/create>. Nothing
else needs a key: lyrics use no token, and everything about playing a file works with no network
at all.

**Release builds** are signed with the debug key unless you put a `keystore.properties` in the
project root:

```
storeFile=metromusic.jks
storePassword=…
keyAlias=metromusic
keyPassword=…
```

A debug-signed release installs fine for sideloading; publishing needs a real keystore, and
switching to one means uninstalling first, since the signature changes. R8 is on.

## Layout

```
app/src/main/java/com/metromusic/
├── core/          Services — the composition root; no DI framework, just lazy singletons
│                  Connectivity — is there a *validated* network
├── data/
│   ├── model/     Track, Album, Artist, Library; the artist-splitting rules; hiding
│   ├── media/     MediaStore scanning, artwork decoding + cache, tag writing, online art
│   ├── library/   LibraryRepository: StateFlow<Library> + ContentObserver
│   ├── lastfm/    the API client and the local-first scrobble queue
│   ├── lyrics/    the Genius client and the have-they-got-any index
│   └── store/     playlists, stats, genres, hidden and settings — JSON in filesDir
├── playback/      MediaSessionService, MediaController wrapper, PlayerState, effects, volume
├── ui/
│   ├── nav/       Screen (sealed) — every destination, typed
│   ├── screens/   one file per screen; LibrarySections.kt holds the panorama's sections
│   └── components/shared rows, tiles, artwork, mini player, the long-press menus
└── widget/        the home-screen live tile
```

Extending it is deliberately mechanical, and the compiler points at the spot:

- **A screen** — a `Screen` entry and a branch in the `when` in `MetroMusicRoot`. The `when` is
  exhaustive. If it must survive process death, extend `Screen.encode`/`decode` too.
- **A library section** — a `LibrarySection` entry, a branch in `CollectionScreen`, a title in
  `sectionTitleOf`, and the body in `LibrarySections.kt`. It lands at the end of everyone's saved
  order.
- **A settings page** — a `SettingsPage` entry, a branch in `SettingsDetailScreen`, and a
  title/subtitle pair. It then appears in both places that list the pages.
- **A singleton** — one `by lazy` line in `Services`, read through `LocalServices`.

`CLAUDE.md` in this repository is the long version: the traps behind each of these, why the
player is an overlay rather than a destination, what the continuum keys must not collide with, and
how to test the parts that only misbehave on a real phone. It is written for whoever picks the
project up next, human or otherwise.

## Keeping it small

The release APK is around 2 MB and idles near 90 MB PSS.

The library is read from MediaStore and never cached to disk, so the only thing persisted is a few
kilobytes of playlists, settings and indices. The one real memory risk is album art — a full-size
cover is about a megabyte — so `ArtworkLoader` decodes no larger than the view that asked for it
and caps its cache by allocated bytes (heap/8, at most 12 MB) rather than by entry count. Lists
stay lazy with stable keys, pivot pages compose one at a time, and playback position is a separate
cold flow that only ticks while something is collecting it.

## Honest caveats

- **No tests.** There is no test source set at all. The best first candidates are
  `MediaStoreScanner` (grouping into albums and artists) and `PlaylistStore` (JSON round-trip).
- **Strings are extracted but not translated.** There is no `values-ru/` yet, and the effect on a
  Russian phone is worse than plain English: the *text* falls back to English while the *plural
  rule* stays the locale's, so 21 tracks reads "21 song". Only a translation fixes it.
- **Genres need API 30+.** `MediaStore.Audio.Media.GENRE` does not exist below that, and the
  section says so instead of hiding, because the circular panorama numbers its pages modulo the
  section count and a section that appears later would renumber every other one.
- **A scrobble Last.fm actually accepts is unproven.** Everything around it is verified on a
  device — a play recorded offline with the right second, an old one pruned, a retry scheduled, the
  queue flushing six seconds after wifi returned and the signed request reaching Last.fm — but the
  last mile needs a real account, and nobody has signed in with one. The cover lookup, which uses
  the same key, *is* proven against the live API.
- **The equalizer was only checked against the emulator's five bands.** A device with ten, or with
  no equalizer at all, takes a branch nobody has watched. Every `audiofx` call is wrapped, so the
  intended failure is "no equalizer" rather than a crash.
- **Opening the player costs one long frame** (~60ms in a debug build on the emulator) for its
  first composition. Closing it is clean. Fixing it would mean keeping the page composed between
  openings.
- **Files tagged by ffmpeg show "unknown year"**, because MediaStore does not map the `date` tag it
  writes. Whether real-world files behave the same is unconfirmed.
- The album, artist, playlist and genre pages still show captions under their app-bar buttons, and
  the artists and songs sections still have accent letter tiles. Both were raised once and never
  decided.

## Licence

Copyright (C) 2026 Diffechento.

**GNU General Public License, version 3** — see [LICENSE](LICENSE). Fork it, sell it, put it in a
store; ship it to anyone and they get the source and these same freedoms.

It has to be v3 rather than v2, and not by preference: almost the whole stack under this app —
AndroidX, Compose, Media3, kotlinx — is Apache-2.0, which is *incompatible* with GPLv2 and
compatible with GPLv3. A GPLv2 build of this could not be distributed at all.

The framework it is built on, [MangoTile](https://github.com/Diffechento/MangoTile), is MIT.
Copyleft here is a decision about this application, not about that library.

[THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md) covers the dependencies, the Selawik fonts that
arrive with the framework under the SIL Open Font License, and the two web services.

MetroMusic is not affiliated with Microsoft, Last.fm or Genius. "Windows Phone" and "Metro" are
Microsoft's; this is an homage to a design language, built from scratch.
