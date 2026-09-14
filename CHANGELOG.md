# Changelog

## Unreleased

**Editing an album's tags works on files that carry the same field twice.** A rip that has been
through more than one tagger often has the album artist written under two or three names at once —
`ALBUMARTIST`, `ALBUM ARTIST`, `ALBUM_ARTIST` — and only one of them was being rewritten, so the
album went on showing the old artist and the edit looked as though it had not saved at all. The
others are now taken with it.

**The permission to change your music can be given once instead of once per album.** From Android 12
there is a system setting for exactly this, and the edit page offers it: after that, saving an album
just saves.

**The artists section can be built from the album artist alone**, on settings → library. A track
tagged “Gorillaz, National Orchestra for Arabic Music, Bashy, Kano” is one record by one band, and
splitting that credit files it under four artists — three of whom you have nothing else by, so the
list fills with names that lead to a single guest appearance and the band you were looking for is
harder to find rather than easier. With this on, the album artist tag says who a track belongs to and
the guests are not artists at all. The credit itself is untouched, so a row still reads what the file
says; a file with no album artist tag still goes under its own credit, split or whole as the setting
above it says. Off by default, and it turns that setting on with it, since it is built on believing
that tag.

**Lyrics can come from the device, and they can follow the music.** A `.lrc` beside a song — the
thirty-year-old format every other player reads — is used ahead of anything fetched from the internet,
and when it carries timestamps the page keeps up with the track: the line being sung is the big one,
the rest sit back out of the way, and tapping any line jumps the song to it. Words sung a second time
are a second line in the file, so a chorus highlights in the right place each time round rather than
the first. The page follows along only when the song on screen is the song playing — lyrics open from
a list as often as from the player, and following the position there would mean lighting up one song's
line at another song's position. Scroll it by hand and it stops chasing you for a few seconds, which is
long enough to read back a verse.

**Lyrics written inside the file are read too**, which is where a FLAC usually keeps them — and an MP3
and an M4A, under their own names for the same thing. Many of those tags hold a whole `.lrc` pasted in,
timestamps and all, so those follow the music exactly as a separate file does. Between a timed source
and a flat one the timed one wins, wherever each of them came from.

**You choose where lyrics are looked up**, on settings → library: **LRCLIB** or **Genius**. They are
different things rather than two of the same, which is why it is a choice and not a fallback order.
LRCLIB is a community collection of `.lrc` files and is the only one of the two that can answer with
*timings*, so a song with no file of its own can still follow the music; Genius reaches further into
obscure and non-English catalogues and publishes words without timings, which it has never had. Neither
needs an account. LRCLIB is the default, on the grounds that words that keep time are the better thing
to land on. Changing the setting forgets what the other service said — both its "no"s, which say nothing
about the new one, and its cached words, which would otherwise quietly win over the answers you switched
to get.

**Fetched lyrics can be saved as a `.lrc`**, on settings → library. It writes the words next to the
song under the song's own name, so they outlive this app and any other player can find them; Genius
publishes words and not timings, so a saved file is a flat one. Off by default, because it is the one
lyrics setting that creates files on the phone. There is also a folder setting for anyone who keeps
their `.lrc` files somewhere other than with the music — it is a fallback, not a requirement, and
sidecars are found without it.

**The line being sung changes over smoothly.** It used to stutter, and not because anything was
dropping frames — the words simply jumped. Growing the text meant re-measuring it, and at a phone's
width a line of lyrics that fits on one row while it waits often needs two once it is full size, so the
page reflowed half way through the change and everything below it shifted in a single frame, while the
scroll that was gliding toward the line kept re-aiming at a target whose height was moving. The line is
now measured once and only *drawn* larger, so nothing is re-laid out while it grows, and the page
travels on the same curve and over the same time as the growth instead of on one of its own. Measured
over the same seven changes: frames over 30ms went from twenty-nine to two.

**A song that was found once stays findable.** Two copies of one song — a full recording and a short
clip — share one entry in the "does this have lyrics" index, and LRCLIB matches on length, so it
rightly recognises one and refuses the other. The refusal used to overwrite the success and grey the
menu entry out for a song whose words were already on the phone. A yes is now never overwritten by a
no: at worst the page opens and reports it found nothing, which is recoverable, where a wrong no was
not.

**"Show lyrics" no longer greys out when the online lookup is switched off.** Words on the device owe
nothing to a network, and hiding them behind the Genius switch hid the files people had put there
themselves.

## 1.3

Built on `io.github.diffechento:metro:1.0.3`.

**An album is its tags, not its folder.** One record split across two folders — a CD1/CD2 rip, a
track downloaded later into a different place — showed as two albums, because the id the library
grouped by was Android's own, and Android bakes the file's folder into it whenever there is no album
artist tag. Albums are now gathered by what the files say: the album title, plus the album artist
where there is one, so two records that merely share a name ("Greatest Hits") still stay apart. The
listening history follows its albums across the change, and files with no album tag at all keep the
old per-folder behaviour — with no tag there is nothing better to go on.

**Drag the left edge of a list to scroll it fast.** A band down the side of the artists, albums, songs
and genres: put a finger on it and the list travels its whole length under the drag, with the letter you
have reached carried in a tile beside your finger — the gallery movement, on a library. It is not a
replacement for tapping a heading to zoom out to the alphabet, which answers "take me to Н"; this
answers "take me a third of the way in", which is not a letter and so was not a question you could ask.
A hairline shows where you are in the list whenever it is scrolling, so the band can be found without
being told about. Tapping a row inside the band still plays it, and swiping sideways there still changes
section. On settings → gestures, like every other gesture the app adds on top of tapping, and it appears
on lists longer than about two screens — below that an ordinary scroll already reaches everything.

**A record is filed under its album artist.** A compilation used to be filed under whoever its first
track happened to credit, so twelve artists on one album meant the album lived under one of them,
picked by track number; and a record where every song reads "band feat. somebody" was by the band and
said so nowhere. Both are what the album artist tag is for, and it is read now — the album's row, its
page and whose page it appears on all follow it, while the credit under each song stays exactly as the
file spells it. The album artist gets a page of their own, so "Various Artists" is somewhere to go
rather than a name with nothing behind it. A switch on settings → library, because a ripper that wrote
something odd in that tag would otherwise move an album somewhere its owner would not think to look.
Needs Android 11, which is where the system started reporting the tag at all.

**Search the whole library at once**, from a row at the top of "more". The panorama's own search is a
different question — tapping a section header filters that section, which is "which of these" — and
this is the one where you do not know, or do not care, whether what you half-remember is a title, a
band or a record. Results come grouped by what they are, with a count on each group, so a query that
turns up one artist and forty of their songs says so instead of being a wall to scroll. Tapping does
what tapping that row does anywhere else: a song plays and the rest open.

**Open an audio file from anywhere else on the phone.** Tapping a song in a file manager, a download or
an attachment offers MetroMusic and plays it, with the player already up. A file the library knows is
played as that track, counted and queued like any other; one from outside — a file the scan has not
reached, or one another app holds — plays as itself, and does not pretend to be part of a library it
is not in.

**Even out the volume between tracks**, off by default, on settings → playback. It reads the
ReplayGain tags files are given when they are ripped or tagged — ID3, Vorbis comments and MP4 atoms
alike, and the R128 numbers Opus uses — out of the stream the player is already parsing, so it costs
nothing and needs no second pass over the files. It can only turn a loud track down, never a quiet one
up, which is the honest limit of a volume that is a fraction of the output: a library mastered
uniformly quiet will hear nothing change, and the setting says so.

**Save the queue as a playlist.** A queue built out of four albums and then pruned is a playlist that
does not exist yet, and the only way to keep it used to be building it again from memory. One button
under the queue's title; the order it writes down is the order on screen.

**Fixed: the banner at the top of the screen arrived in two jerks.** The volume strip, "added to
queue" and "press back again" all drop in from the top edge, and all three did it by moving their own
window — which the system applies on its own schedule, so almost the whole travel happened before
anything was on screen and the last few pixels then crawled. On the way down the strip's lower rows
also passed under the clock and the wifi icon, so it read as landing in the wrong place and then
correcting itself. Both are gone: the strip is placed once and its contents drop inside it, clear of
the clock. That is framework work — MangoTile 1.0.3.

**Fixed: the player did not open when the app was launched to play something.** Nothing had a track
for the first moment of a launch — the player is still connecting — and that counted as the queue
having been lost, which closes the player. It only counts as lost now once something has actually
played.

## 1.2

Built on `io.github.diffechento:metro:1.0.2`.

**Every gesture follows your finger, and lets go at the speed your hand had.** A report after 1.1 that
"doing any gestures is a bit rough" turned out to be four separate faults being felt as one, all of them
in the framework. The largest: a settle handed the finger's velocity *accepted* it and threw it away,
because only a spring reads that argument and every settle here was an easing curve. So each gesture
ended with the speed jumping to whatever the curve began at, at exactly the moment your hand was judging
the result — a discontinuity in the one thing a drag is made of. Everything that follows a drag now ends
on a critically damped spring given the gesture's own velocity: an ease-out with no bounce in it, and no
seam where the finger lifts.

**The player is dragged rather than nudged.** Pulling the strip up used to move it 26dp, decide, and
then play a canned animation from wherever the page had reached — your hand doing one thing while your
eye watched another. The page's position *is* the drag now, and letting go only finishes a movement
already underway. One detector owns both of the player's axes, too: a sideways swipe and a downward push
were two separate one-axis detectors that could not arbitrate, so a thumb thirty degrees off the
horizontal started both at once. The axis is locked to whichever way you actually moved.

**And the player pages between tracks instead of springing back.** The neighbouring tracks are laid out
either side and your finger moves all three, so the track that lands is the one that was visibly
coming — rather than the screen springing back to the middle and the change then arriving afterwards as
its own choreography, one movement of which went the opposite way to the hand. Only the face pages: the
slider, the times and the transport belong to the player rather than to the track, and a swipe that
carried the play button off the screen would be moving the furniture to change a record. At the end of
the queue the page resists instead of flying a quarter of the screen and coming back to the same song.

**The strip carries the top of the playing cover**, because a player dropping back into a flat black bar
ends the movement by turning the artwork into a black rectangle. It is not a picture of its own but the
same square the full player draws — the cover at the screen's width, hung from the top of the page — so
the strip shows the top of it, pulling up draws the rest out from underneath, and the size never changes
on the way.

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

**Fixed: editing an album's tags did nothing on a release build**, which is every build anyone has
installed — it worked in development throughout and listed an error per track on a phone. jaudiotagger
finds the class for each tag field by name and copies frames through a constructor it looks up
reflectively, and minification is invisible to both: the classes were renamed and the constructors
removed, so the library could no longer build the field it had been asked to write. It did not say that.
The message named the *value* instead, which is why the first report read as a problem with Cyrillic
text and was not one.

**Fixed: the panorama went on taking a sideways swipe after it had visibly stopped moving.** Swipe
between sections and then try to scroll a list and the panorama moved sideways instead, unless you left a
pause between the two. A surface that is still animating deliberately takes the next touch without
waiting for the slop — that is how you catch a list still in flight — but the slop is the only place the
direction is arbitrated, and the snap was still creeping by fractions of a pixel for hundreds of
milliseconds after the movement was over. It now stops calling itself a scroll when it stops moving.

**Fixed: a band of tinted artwork appeared over the library** while the player was on its way up. The
player's backdrop is deliberately overscaled so panning never exposes an edge, and that overspill hung
above the page's top edge — invisible on a page that could only be all the way open or all the way shut,
which is why nothing caught it until the page could be held halfway.

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
