# Changelog

## 1.0

First release. Built on `io.github.diffechento:metro:1.0.0`.

**The library** — MediaStore scan into tracks, albums, artists and genres; the collection
panorama that *is* the library, its sections ordered from settings; long lists with the WP8 jump
grid over Latin and Cyrillic; per-section search from tapping a section header; album, artist,
genre and playlist pages; continuum from a cover in a list into the page it opens.

**Playback** — Media3 in a `MediaSessionService`: notification, lock screen, headset buttons,
Bluetooth, audio focus, and playback that survives the activity. Mini player as app chrome, full
player as an overlay that rises out of it. Sleep timer with a fade-out. Equalizer and bass boost
over the device's own bands.

**The queue** — `play next` on albums, artists, genres and tracks, inserting after the last block
queued rather than always next, so several albums play in the order they were picked. A top
banner confirms it, since nothing else on screen changes.

**Names that real files spell badly** — one credit string split into several artists
(`feat.`, `&`, `,`, `vs.`) and filed under every one of them, with the display credit left
alone; genre tags that differ only in case or punctuation folded onto the spelling most of the
library uses, plus merges by hand.

**Editing** — album metadata written to the tags *inside the files*, for every track of the
album, with the consent dialog the platform requires; MediaStore's own metadata columns are
read-only from Android 10 on and drop the write without an error.

**Hiding** — artists and albums hidden by name in one place, so they leave every list, search
result and playlist at once; restored from settings → hidden.

**Playlists and favourites** — create, rename, delete, reorder by dragging; favourites as a
permanent pseudo-playlist. Stored as JSON in `filesDir`.

**Network, all optional and all failing soft** — Last.fm scrobbling with a local-first queue that
records the second a track *started* and flushes when a validated network appears, so a journey
underground turns up in the right order; missing album art fetched from Last.fm, with "no cover"
and "could not ask" kept apart; lyrics read from Genius with no API token.

**Look** — all twenty WP8 accents, light and dark, an optional custom background or the playing
cover as wallpaper, one backdrop behind every page. Volume banner in place of the system panel.
Switchable gestures. Home-screen live tile. All user-visible text in `strings.xml`, counts through
`<plurals>`.

**Release builds** — `assembleRelease` signs with the debug key unless a `keystore.properties`
exists, because an unsigned APK is one no device will install. R8 on.
