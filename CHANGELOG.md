# Changelog

## Unreleased

**The queue is a screen now, and it is one pull further up.** The player came out of the strip; pull
it up again and the queue comes out over it — the same movement, one page on — and pushing it back
down anywhere on the page, tapping its title, or Back puts it away. Anywhere means anywhere: the
title answers to the push directly, and the list hands the same push over as soon as it has nothing
left to scroll to, so a page dragged down from the top of the list — or from the empty space under a
short queue — travels with your finger like any other. The way *in* has a mark of its own: a caret
in the space under the transport, which opens the queue on a tap, because a gesture with nothing on
screen to hint at it is a feature only its author knows about. It opens on the track you are
listening to rather than on the top of a queue whose first forty songs have played.

**Hold a row to pick it up, swipe it aside to remove it.** Holding lifts the row under your finger
and dragging carries it a place at a time, with the list creeping while you hold it against either
end. Swiping it sideways — either way — takes it out of the queue: the row travels with the word
"remove" uncovered behind it, at full strength exactly where letting go would commit, so you are
told where the threshold is instead of finding it out by losing a song. There is no menu on this
screen at all; both of the things you come here for are the row itself moving under your hand.

Removing the song that is playing is allowed and does what it says: playback moves to the next one.
Taking the last row out empties the queue, and an emptied queue does not come back at the next
launch.

Three new switches on settings → gestures ("swipe up to see the queue", "swipe down to close it",
"swipe a song aside to remove it"), because the upward pull shares an axis with the push that puts
the player away, the sideways one shares a list with scrolling and dragging, and a gesture that
fires by accident is worse than no gesture — the more so when it deletes something.

## 1.1

Built on `io.github.diffechento:metro:1.0.1`.

**The songs, albums and artists sections can be arranged four ways each.** Hold a group header and
the arrangements unroll out of it; the list re-orders and every run of rows keeps a heading, so
ordering by a number you cannot see still says where you are.

- **songs** — name, date added (by month), length (four bands), times played (four bands)
- **albums** — name, artist (grouped by the *artist's* letter, so the zoom-out is still the alphabet),
  date added, year (a heading per year, with "year unknown" last)
- **artists** — name, songs, albums, times played

Tap a heading and it still zooms out over the groups and jumps to the one you pick — the alphabet, the
months, the bands. That is the same gesture on every arrangement: a tap that meant "zoom out" under a
letter and "choose an arrangement" under a band would teach one thing and do another. A closed set of
bands offers all four and dims the ones your library has nothing in; date added shows the months it
has, because there is no set of all months. Each section remembers its own arrangement — they are
different questions, and an artist has no length to sort by. Tapping a row plays the list **as
arranged**, so the top song under "times played" is followed by the second.

**The library screens give back a row and a half of space.** The panorama's title spent 83dp above its
own letters and 63dp below: the status bar is now cleared by inset rather than by a fixed 52dp, the
blank the font reserves above the capitals is trimmed, and the gaps are 6dp and 12dp. Measured on a
1080x2400 screen, a section's first row started 788px down and now starts at 609px.

**And the title rolls away as you scroll.** It gives up its *height*, so the list grows into the space
rather than being covered by it, and it comes back when the list is dragged past its top. The album
page does the same with the cover, the numbers and its app bar — fourteen tracks fill the screen
instead of six. It is a setting (settings → interface), because the title is also how some people know
where they are.

**A banner no longer swallows the screen while it is up.** "Added to queue" and the volume strip lived
in a screen-sized window, so for the two seconds one was showing, every tap went into it and nothing
underneath answered.

**The zoom-out and a context menu's sheet are slightly translucent**, so what you pulled back from
stays faintly behind it and both read as something laid over the page rather than as a different
screen. A menu of arrangements also marks the one in force in accent — offering four ways to sort and
saying nothing about which you are looking at makes the user pick one to find out.

## 1.0.1

Packaging, so the app can be built from source by F-Droid rather than distributed as a binary. The
application itself is unchanged from 1.0.

**The release variant no longer insists on signing itself.** F-Droid's builder edits the Gradle file
before building — it deletes the whole `signingConfigs { }` block and every line matching
`signingConfig = <one token>`, then signs the result with its own key. The signing config was chosen
over two lines (`findByName("release") ?: getByName("debug")`), of which only the first matched, and
what was left behind was a dangling `?:` that fails to compile. The choice is now made in a `val`
above, so the line they delete is one token and what remains is a build with no signature — which is
exactly what they want. A local `assembleRelease` still signs with `keystore.properties`, or with the
debug key when there is none.

**The app's page lives in the repository** — `fastlane/metadata/android/en-US`: title, descriptions,
the icon rendered from the vector the app itself uses, five screenshots, and a changelog per version
code. It is read from the same commit as the build, so the page and the binary cannot drift apart.

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

**Apple Lossless, decoded in the app** — Android ships no ALAC decoder, and where there is none
Media3 raises no error: it marks the track unsupported, plays nothing and runs the position against
the clock, which looks like a player playing an album in silence. A software decoder (BSD, vendored)
sits behind a renderer added after the platform's own, so a device that does have one keeps using it.
16- and 24-bit, hi-res not cut down to 16 on the way through. The **length** of a file whose
`duration` MediaStore leaves empty — the same ALAC files, on the same devices — is measured from the
container instead of shown as blank.

**The queue** — `play next` on albums, artists, genres and tracks, inserting after the last block
queued rather than always next, so several albums play in the order they were picked. A top
banner confirms it, since nothing else on screen changes. The queue and the current track survive
being closed: they come back paused, at the start of the track, resolved from the library by id.

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

**Favourites and Last.fm's loved tracks, kept the same** — opt-in, and in both directions: a heart
added here is loved there, a love removed on the website stops being a favourite here. It is a
three-way merge against what the two sides agreed on last time, not a copy, so neither end has to
lose anything to the other; the first run is a union. Only music that is on the device is ever
compared, which is what keeps a library of forty tracks from un-loving a profile of seven hundred.

**Look** — all twenty WP8 accents, light and dark, an optional custom background or the playing
cover as wallpaper, one backdrop behind every page. Volume banner in place of the system panel.
Switchable gestures. Home-screen live tile. All user-visible text in `strings.xml`, counts through
`<plurals>`. An about page with the version, the date, the licence — and six tiles that each carry a
real number out of your own library and turn over when you poke them.

**Release builds** — `assembleRelease` signs with the debug key unless a `keystore.properties`
exists, because an unsigned APK is one no device will install. R8 on.
