# History and export

**History** lists every finished row with its time, distance and pace. **Details**
shows average and maximum stroke rate, average power, strokes and 100 m splits.

## Export

From a row's details:

- **Export CSV**: the raw samples, one line per reading.
- **Export TCX (Strava)**: Garmin's Training Center format, which Strava and Garmin
  Connect import. TCX has no rowing sport, so set the activity type to *Rowing* after
  uploading.

Both go to `Download/Fellwater` on the device.

## Where rows are stored

Each row is a CSV file in `Android/data/fi.tunturi.rows/files/sessions/`, with a
small `.meta` file for the goal and damper. Copy the folder off the device to back
it up.

| Column | Meaning |
|---|---|
| `elapsed_s` | seconds since the first stroke |
| `stroke_rate_spm` | strokes per minute |
| `stroke_count` | strokes so far |
| `distance_m` | metres so far |
| `pace_s_per_500m` | instantaneous pace |
| `power_w` | watts |
| `resistance` | console resistance, when reported |
| `heart_rate_bpm` | from the console or a strap |
| `energy_kcal` | when reported |

## Unfinished rows

The row in progress is saved every 30 seconds. If the app is closed or crashes
before you tap **Finish row**, the next launch offers it back: **Save to history** or
**Discard**.

Deleting a row from History asks first, since your rows are also your racers.
