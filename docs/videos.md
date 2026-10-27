# Videos

## Sources

| Source | How | Notes |
|---|---|---|
| Local file | **Choose video** | Anything Android can play. No network needed. |
| Stream | **More → Stream or YouTube URL → Play URL** | Direct mp4, HLS (`.m3u8`) or DASH (`.mpd`), including plain `http://` on your own network. |
| YouTube | Paste a link, or **Share** from the YouTube app and pick *Row to this video* | Played in the same player as everything else, up to 1080p. |

## Speed follows your pace

Each video has a **video pace**: the pace the footage looks right at. Rowing at that
pace plays it at 1×; faster or slower scales the speed, from 0.25× to 4×. Pace is
averaged over about five seconds, because the pace within a single stroke swings too
much to follow directly.

Set it under **More → Video pace**. It is remembered for each video separately.

Two switches under **More** control this:

- **Match speed to my pace**: off plays at 1× whatever you do.
- **Pause when I stop rowing**: pauses after 5 seconds without a stroke and resumes
  on the next one. Before your first stroke the video waits for you. With no rower
  connected the video just plays, so you can preview it.

## Fullscreen

**Fullscreen** hides Android's status and navigation bars (swipe from an edge to get
them back for a moment). The stats panel and the race bar can be dragged anywhere,
and stay where you put them. **More → Race bar in fullscreen** turns the race bar off.

## Saving videos offline

**More → Save offline** downloads the URL in the box so the next row needs no
network. A YouTube video is fetched as separate picture and sound and joined into one
mp4. Downloads continue in the background with a progress notification, even if you
switch tabs or apps.

Saved videos are listed under the player with their sizes. Deleting one asks first.

!!! warning "YouTube"
    YouTube support uses [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor)
    rather than YouTube's official player, so playback rate is not capped and there are
    no ads. YouTube's terms of service do not allow downloading videos except through
    their own features: only save videos you have the right to keep. When YouTube
    changes something and links stop working, an app update with a newer extractor
    usually fixes it.
