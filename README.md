# LIFETRACE
﻿
LIFETRACE is an Android application for lightweight, context-aware lifelogging.
It captures photos with location and motion context and adapts the capture interval to context changes and device resource conditions.
This repository contains the Android sensing application used for data collection in our study.
﻿
## Dataset Availability
We will publicly release the dataset upon publication of the paper. The download link, dataset documentation, and licensing information will be added to this section when the release is available.
﻿
The public release will respect participant consent and applicable ethical and privacy requirements. Sensitive or personally identifying information will be excluded or de-identified as appropriate. The release documentation will describe the available data fields, privacy processing, and conditions of use.
﻿
## Features
﻿
- Captures camera photos together with location and motion context.
- Reads available GPS information, including latitude, longitude, altitude, speed, bearing, accuracy, and provider.
- Reads accelerometer, gyroscope, and magnetometer measurements when the corresponding sensors are available.
- Estimates motion state on-device: `STATIONARY`, `WALKING`, `RUNNING`, or `IN_VEHICLE` and so on.
- Resolves a readable place name from location.
- Adapts photo capture intervals to changes in place, motion state, and GPS displacement in high-frequency mode and low-frequency mode.
- Uses transition confirmation, location-quality checks, bounded high-frequency windows, and cooldown periods to limit unnecessary captures.
- Reduces capture frequency under low-battery, power-saving, or elevated thermal conditions.
﻿
## Adaptive Sampling
The sampling policy uses location and motion observations to identify context changes. Confirmed changes can temporarily increase the photo capture frequency.
﻿
## Configuration
﻿
The app supports the following settings in the project-root
`local.properties` file:
﻿
| Property | Purpose |
| --- | --- |
| `lifelog.upload.url` | Endpoint used to receive snapshot uploads |
| `amap.api.key` | Key for AMap reverse geocoding |
﻿
Example:
﻿
```properties
lifelog.upload.url=http://XXXX:XXXX/upload
amap.api.key=YOUR_AMAP_API_KEY
```
## Local Storage
The temporary upload cache is stored inside the app's private files directory:
```text
files/lifelog_queue/
├── snapshots/    # JSON metadata for queued snapshots
└── images/       # Captured JPEG images
```
﻿
The app also writes an export to:
﻿
```text
Documents/LIFETRACE/
├── images/
│   └── YYYYMM/
│       └── DD/
│           └── yyyyMMdd_HHmmss_000.jpg
└── lifetrace_snapshot_metadata.csv
```
﻿
The export contains:
- `images/`: exported JPEG images, organized by date.
- `sensorcollector_snapshot_metadata.csv`:minute-level per-snapshot metadata.
﻿
The main CSV uses one row per minute. When multiple images are captured within the same minute, their names are appended to the `ImageID` list field.
﻿
﻿
## Privacy and Data Handling
﻿
Lifelogging photos and location records may contain sensitive information about participants, bystanders, and places.
﻿
- Collect data only with appropriate consent and authorization.
- Review images and metadata before sharing or publishing them.
- Protect exported files and uploaded copies as well as the app-private cache.
- Delete shared-storage exports separately when they are no longer needed.
- Use HTTPS for uploads outside a trusted local testing environment.
﻿
The app's collection and export features do not themselves make a dataset suitable for public release. Research data must undergo the consent, privacy, and ethical review required for the intended release.
﻿
## Development Notes
- Camera, GPS, lifecycle behavior, and power consumption should be evaluated on a real device.
- Sensor availability varies by phone. Missing sensors are shown as unavailable.
- The application's source-code license and the study dataset's release terms are separate. Dataset licensing information will accompany the public release.
﻿
