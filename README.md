# Android Pulse

Android Pulse is a background service application that monitors and reports various system statistics from an Android device. It collects data such as battery level, WiFi information, mobile data availability, RAM and storage usage, and network traffic, then sends this information to a specified server.

## Features

- Continuous monitoring of device stats in the background
- Collection of the following data:
  - Battery level
  - Connected WiFi network name
  - WiFi signal strength
  - Mobile data availability
  - RAM usage
  - Storage usage
  - Network traffic (download and upload speeds)
- Periodic sending of collected data to a server
- Runs as a foreground service with a notification for reliability reasons

## Requirements

- Android SDK 21+
- Kotlin 1.5+
- OkHttp3 library for network requests

## Permissions

The app requires the following permissions:

- `ACCESS_FINE_LOCATION`: For accessing WiFi information
- `ACCESS_BACKGROUND_LOCATION`: For accessing location in the background (Android 10+)

## Setup

1. Clone the repository
2. Open the project in Android Studio
3. Update the server URL in the `sendStatsToServer` function to point to your data collection server
4. Build and run the app!

## Usage

1. Launch the app
2. Grant the necessary permissions
3. The service will start automatically and begin collecting and sending data

## Other Notes

- Modify the `collectAndSendStats` function to add or remove data points
- Adjust the delay in the main coroutine loop to change how often data is collected and sent
- There is no UI for the Android device. It's just a simple "Hello World!" just to satisfy compiler requirements, but there is no functional UI.

## Contributing

Contributions to Android Pulse are welcome! Please feel free to submit a Pull Request! :)

## License

[MIT License](LICENSE)

## Disclaimer

This app collects and transmits device data. Ensure you have the necessary consent from users and comply with relevant data protection regulations when deploying this app. Ensure device security. Originally, it was created in response because I needed to monitor an Android device I had. It's a simple app with not many features and almost 0 true customization. More features to come soon!
