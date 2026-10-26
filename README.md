# LIFETRACE

Android app for lightweight lifelogging data collection on a phone.

# Dataset Availability

This repository provides the sensing software used for lifelog data collection in our study. The dataset collected in this study will be made publicly available after the paper is published.

## Features

- Captures camera photos together with current GPS and IMU context.
- Reads GPS data: latitude, longitude, altitude, speed, bearing, accuracy, and provider.
- Reads IMU sensors: accelerometer, gyroscope, and magnetometer.
- Estimates a coarse motion state on-device: `UNKNOWN`, `STATIONARY`, `WALKING`, `RUNNING`, `IN_VEHICLE`.
- Resolves a readable place name from location when available.
- Adapts capture interval with a simple sampling scheduler.
- Builds local lifelog snapshots and queues them for upload.
- Keeps snapshots in a local cache and deletes expired items after 72 hours.
- Deletes local snapshot files after a successful upload.
- Shows the latest cached photo in the camera page.

## Local storage

The app stores temporary upload cache files inside the app-private files directory:

- `files/lifelog_queue/snapshots/`: JSON metadata for queued snapshots
- `files/lifelog_queue/images/`: captured JPEG images

These files are not normally visible in the phone's file manager because they are stored in the app sandbox. Use Android Studio `Device Explorer` or `adb shell run-as com.example.sensorcollector` to inspect them.

## Configuration

Set these values before testing the full upload pipeline:

- `lifelog.upload.url`: server endpoint for snapshot upload
- `amap.api.key`: AMap reverse geocoding key

They can be defined in `local.properties`.

Example:

```properties
lifelog.upload.url=http://xxx:8000/upload
```

Use the LAN IP of this computer, not `localhost`. The phone and this computer must be on the same network.

## Receive uploads on this computer

Start the local receiver on this computer:

```bash
cd SensorCollector
python3 tools/local_upload_server.py
```

Uploaded files will be saved under:

- `local_uploads/images/`: JPEG photos
- `local_uploads/metadata/`: JSON metadata for each snapshot

## Open the project

1. Open `SensorCollector` with Android Studio.
2. Let Android Studio sync Gradle and install required SDK components.
3. Connect a real Android phone running Android 8.0+.
4. Grant camera and precise location permissions.
5. Start auto capture on the camera page and monitor the latest cached photo and upload state.

## Notes

- GPS and camera features should be tested on a real device, not an emulator.
- If a sensor is missing on the phone, the app will show it as unavailable.
- If `lifelog.upload.url` is empty, the app still captures and caches snapshots locally, but upload is skipped.
- Android 9+ blocks plain HTTP by default. This project now explicitly allows cleartext traffic so uploads to a local `http://<电脑IP>:8000/upload` server work.
- Uploaded snapshots are removed from the local cache immediately after success.
