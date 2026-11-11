NEXUS Capacitor 8 / SwapLab build package

Version alignment:
- Node.js: 22.x (SwapLab image)
- @capacitor/core: 8.6.0
- @capacitor/android: 8.6.0
- @capacitor/cli: 8.6.0
- Android Gradle Plugin: 8.13.0
- Gradle runtime expected from SwapLab: 8.14.3
- Java: 21
- compileSdk / targetSdk: 36
- minSdk: 24

Build layout:
- Root Gradle project: settings.gradle + build.gradle + app/
- Capacitor Android runtime: node_modules/@capacitor/android/capacitor
- Web entry: dist/index.html
- Capacitor assets copy: app/src/main/assets/public/index.html

Security note:
The sole exported activity is the Android MAIN/LAUNCHER entry point. Android requires an exported launcher activity so the system launcher can start the app. It extends Capacitor BridgeActivity and contains no admin API or privileged operation. Do not change exported=true to false. If SwapLab's generic Semgrep rule blocks this required launcher entry point, request a rule exclusion/false-positive review from SwapLab.

This package has been structurally updated, but it has NOT yet been built by SwapLab or installed on a device. A successful cloud build and device test are still required.
