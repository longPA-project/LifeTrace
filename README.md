# SensorCollector

Android app for reading phone GPS and IMU data in real time.

## Features

- Reads GPS location data: latitude, longitude, altitude, speed, bearing, accuracy.
- Reads IMU-related sensors: accelerometer, gyroscope, magnetometer.
- Requests runtime location permission on Android 6.0+.
- Shows sensor availability on the device.

## Open the project

1. Open `SensorCollector` with Android Studio.
2. Let Android Studio sync Gradle and download required SDK components.
3. Run the app on a real Android phone.

## Notes

- GPS data is best tested on a real device, not an emulator.
- If a sensor is missing on the phone, the app will show it as unavailable.
- The current app displays live data only and does not save logs to file yet.
