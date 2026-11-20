# Hearth

A simple home screen for the Family Room TV (Sony BRAVIA 4K VH2, Google TV /
Android 12, `192.168.1.75`), built so anyone in the family can find things.

- **Top:** clock, date, and weather plus the next countdown from
  [lookout-feed](https://github.com/rusty-workshop/latitude-services/tree/main/lookout-feed) (`192.168.1.158:8473/api/feed`,
  refreshed every 10 minutes; hidden until the first fetch succeeds).
- **Watch:** big 16:9 app banners. The first time Hearth runs, this row copies
  Projectivy's TV Apps row: Netflix, Disney+, YouTube, Jellyfin, Lookout,
  Prime Video, Sling TV, SmartTube, Fandango, Play Store.
- **Inputs & more:** one tile per HDMI port, showing the CEC device name
  (e.g. "Roku Express 4") or "Nothing plugged in", then All apps and Settings.
  Input tiles open the port directly through `TvContract` passthrough URIs.

Hold **OK** (or press **Menu**) on a tile for options:

| Where | Options |
|---|---|
| Home row | Move (left/right to shift, OK or Back to drop), Remove from home, App info |
| All apps | Add to / Remove from home, Hide / Unhide, App info |

Hidden apps sit dimmed in a "Hidden" section at the bottom of All apps. The order
and hidden list are saved on the TV in SharedPreferences.

## Background photos

Behind everything, a random nature photo crossfades in every 5 minutes. A credit
(title and photographer) sits in the bottom-right corner, which Commons' licenses require.
The photos are Wikimedia Commons **Featured pictures** of landscapes, mountains,
bodies of water, coasts, forests, islands, volcanoes, twilights and natural phenomena.
Commons doesn't allow watermarks on featured pictures, and no account or key is needed.
(Reddit's r/EarthPorn was the first idea, but Reddit now blocks anonymous API access.)

`Photos.kt` keeps only landscape 1.3:1–2.2:1 images at least 1920 px wide, and skips
titles that name towns or buildings. Wikimedia rate-limits anonymous clients hard,
so the list of photo names is built at most once a week, one category at a time with
a pause between, and image details are fetched 20 at a time. The last photo is kept,
so Hearth starts with it instead of a bare background.

### Photo buttons

Press **Down** from the inputs row to reach the buttons beside the credit:

| Button | Does |
|---|---|
| Next photo | Skips now (so does fast-forward on the remote) |
| ♡ Save / ★ Saved | Saves the photo |
| Hide photo | Never shows it again |
| Saved only | Appears once 3+ are saved; rotates only saved photos |

The last 80 downloaded photos are kept on the TV. When Wikimedia rate-limits (HTTP 429)
or the internet is down, Hearth backs off for at least 10 minutes and uses those instead.

### Time of day

A greeting under the date ("Good morning / afternoon / evening / night"). From 9pm to
6am the background is dimmed, and photos are picked from titles that mention sunset,
dusk, moon, stars, aurora and similar. They come from the same nature categories, because
Commons' "twilight" and "night photography" categories are mostly cities.

## Screensaver

`HearthDream` replaces Google's Backdrop screensaver (after 10 minutes idle). It shows a
full-screen photo with a slow zoom, a big clock, the date and the weather, and a new photo
every minute. To avoid burn-in, the clock swaps between bottom corners with each photo.
Any button wakes the TV.

```bash
adb -s 192.168.1.75:5555 shell settings put secure screensaver_components com.rusty.hearth/.HearthDream
# back to Google's: com.google.android.apps.tv.dreamx/.service.Backdrop
```

Plain Kotlin Views, no dependencies, laid out on a 1920x1080 design canvas like Lookout.

## Build and install

```bash
JAVA_HOME=~/.jdks/jdk-21.0.13.1+2 ./gradlew assembleRelease
adb -s 192.168.1.75:5555 install -r app/build/outputs/apk/release/app-release.apk
```

## Make it the home screen

Google TV's own launcher (launcherx) outranks any app set as the preferred home,
so Hearth works the way Projectivy does. Its `HomeKeyService` accessibility service
catches the Home key and opens Hearth. It also opens Hearth whenever launcherx's home
screen comes to the front some other way. Turn it on next to tv-button-remap:

```bash
adb -s 192.168.1.75:5555 shell settings put secure enabled_accessibility_services com.rusty.tvremap/.RemapService:com.rusty.hearth/.HomeKeyService
```

Testing note: `adb shell input keyevent HOME` skips accessibility key filters (only real
remote presses go through them), so over adb only the launcherx fallback can be tested.
For a few seconds after an `adb install -r` the service is restarting and Home can slip through.

**Don't `am force-stop com.rusty.hearth`.** Android removes a force-stopped app's
accessibility services from the enabled list, and Home goes back to Google TV until
you run the command above again. `adb install -r` updates are fine.

To go back to Projectivy:

```bash
adb -s 192.168.1.75:5555 shell settings put secure enabled_accessibility_services com.spocky.projengmenu/.services.ProjectivyAccessibilityService:com.rusty.tvremap/.RemapService
```

## License

AGPL-3.0-or-later
