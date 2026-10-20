# Battery display calibration

The user recorded a fully charged ElectroPhy module showing 78% at startup,
reaching displayed 0% at 48 minutes and powering off at 48 minutes 15 seconds.
The app now estimates remaining runtime under similar operating conditions.
The firmware sampling interval and 3300 mV shutdown threshold are unchanged.

## Method

1. Interpret the supplied `48:15:00` OFF entry as 48 minutes 15 seconds (48.25 minutes).
2. Infer each voltage by inverting the previous piecewise-linear lookup:
   `(mV, percent) = (3300,0), (3500,5), (3600,10), (3700,20),
   (3750,30), (3800,40), (3850,50), (3900,60), (4000,75),
   (4100,90), (4200,100)`.
3. For repeated displayed percentages, use the mean observation time.
   A voltage-only function cannot distinguish different times with the same reading.
4. Set the new percentage to `100 * (1 - mean_minutes / 48.25)`,
   rounded to the nearest integer. Round inferred voltages to integer millivolts.
5. Anchor 3300 mV to 0%, matching shutdown; clamp at 100% above
   4020 mV, the inferred fully charged voltage under the test load.
   Interpolate linearly between adjacent calibrated points.

This is a single-run empirical estimate, assuming approximately constant load.
The old percentages were rounded, so inferred voltages are approximate, especially
near empty. This is not a measured open-circuit voltage/state-of-charge curve.
Changing streaming settings, load, temperature or battery condition may change
accuracy. The app still uses each incoming voltage, not a countdown timer.
Future calibration should log actual millivolts and elapsed time under a known load.

## Original observations

| Minutes | Old displayed percentage |
|---:|---:|
| 0 | 78 |
| 2 | 71 |
| 3 | 68 |
| 4 | 65 |
| 5 | 62 |
| 6 | 59 |
| 7 | 57 |
| 8 | 53 |
| 10 | 46 |
| 12 | 38 |
| 13 | 35 |
| 14 | 33 |
| 15 | 26 |
| 16 | 25 |
| 17 | 24 |
| 18 | 20 |
| 19 | 19 |
| 20 | 18 |
| 21 | 16 |
| 22 | 15 |
| 23 | 13 |
| 24 | 13 |
| 25 | 12 |
| 26 | 11 |
| 27 | 10 |
| 28 | 9 |
| 29 | 9 |
| 30 | 8 |
| 31 | 7 |
| 32 | 7 |
| 33 | 6 |
| 34 | 6 |
| 35 | 6 |
| 36 | 5 |
| 37 | 5 |
| 38 | 4 |
| 39 | 4 |
| 40 | 4 |
| 41 | 4 |
| 42 | 3 |
| 43 | 3 |
| 44 | 3 |
| 45 | 2 |
| 46 | 2 |
| 47 | 1 |
| 48 | 0 |
| 48.25 | OFF |
