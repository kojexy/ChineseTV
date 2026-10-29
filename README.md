# Chinese TV · 中文电视

A TV channel of your own for Android phones and Android TV. Programmes start at a set time, keep running
whether you watch or not, and cannot be paused, rewound or skipped. The point is to watch Chinese series
the way people watch television: no choosing, no "maybe later", no pausing.

## How it works

- **The schedule is the remote.** You add a series and give it a time slot, for example
  "繁花, Mon–Fri at 20:00, 2 episodes". Every airing moves the series on, even if nobody watched.
- **No controls.** OK and the arrows only show what is on (title, episode, time, what comes next).
  Play/pause/seek keys do nothing.
- **Live, always.** Close the app at 20:10 and open it at 20:30: you are at 20:30, not 20:10.
- **Leaving asks first.** Back shows "Are you sure you want to leave? It's better to keep watching!"
  You can still leave. Home is never blocked.
- **It starts by itself.** At the start time the channel opens on its own. On a TV, switch the TV on in the
  middle of a programme and the channel comes back (the "Reopen when the screen turns on" switch).
- **Off air** shows a clock, a countdown to the next programme and today's guide.
- If a file has several audio or subtitle tracks, Chinese is chosen first. Subtitles inside MKV files work.

## Getting started

1. Install `ChineseTV.apk` from [Releases](../../releases) (the same APK works on a phone and on Android TV).
2. Copy each series into its own folder on the device or a USB drive, one file per episode,
   for example `Movies/繁花/第01集.mp4`. File names decide the order (`第2集` comes before `第10集`).
3. Open the app → **TV guide & settings** → **Add a series from a folder** → choose the folder → set the time.
4. In **Starting by itself**, allow **Display over other apps**. Without it Android does not let an app open
   itself; the app then falls back to a notification.

On some Android TV models the "Display over other apps" screen is hidden. Then use a computer:

```
adb shell appops set ru.kojexy.chinesetv SYSTEM_ALERT_WINDOW allow
```

## Rules of the broadcast

- Which episode airs is counted from the calendar, not stored as progress. Changing a slot starts counting
  again from the episode you choose.
- Airings that overlap: the one that started later is on screen; the earlier one keeps running underneath
  and is back when the later one ends.
- A long block may run past midnight.
- If a file is shorter than MediaStore said, the screen waits for the next episode; if it is longer, the next
  episode cuts in on time. Files with unknown length count as 45 minutes.

## Programme from a web link

**TV guide → Load a programme from a web link** reads a JSON file (for example a raw GitHub Gist).
This is the easy way to plan a whole week on a computer, and to use online video links.

```json
{
  "shows": [
    {
      "id": "fanhua",
      "title": "繁花",
      "episodes": [
        { "title": "第1集", "uri": "https://example.com/fanhua/01.mp4", "durationMs": 2700000,
          "subtitles": "https://example.com/fanhua/01.srt" },
        { "title": "第2集", "uri": "https://example.com/fanhua/02.m3u8" }
      ]
    }
  ],
  "slots": [
    { "showId": "fanhua", "time": "20:00", "days": [1, 2, 3, 4, 5], "episodes": 2, "startEpisode": 1 },
    { "showId": "fanhua", "time": "10:00", "date": "2026-11-07" }
  ]
}
```

- `days`: 1 = Monday … 7 = Sunday; empty or missing = every day. `date` makes it a one-off airing.
- `episodes`: how many episodes in a row. `startEpisode`: the episode (1-based) for the first airing.
- `durationMs` is optional: the app reads the length from the file when it can.
- `since` (optional, `YYYY-MM-DD`): the day counting starts. By default, today (or tomorrow if the time has passed).
- Direct video links (MP4, MKV, HLS `.m3u8`) work. Pages of streaming sites do not.

## Build

GitHub Actions tests the broadcast clock and builds a signed APK on every push to `main`, then attaches it to a
release. The signing key is in the repository secrets and in `signing/` on the author's Mac (not in git).
If the key is lost, new versions install only after removing the old one.

The schedule logic is in `Broadcast.kt` (pure Kotlin, unit-tested); the screen is `PlayerActivity.kt`;
the guide and settings are `ProgrammeActivity.kt`; auto-start is `AutoStart.kt`.
