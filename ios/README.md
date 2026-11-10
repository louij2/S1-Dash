# S1 Dash for iPhone (dev build)

The same dash screens as the Android app, in a small Swift wrapper. For now it
runs the built-in demo data: no OpenHaldex or ECU connection yet, and no CarPlay.
The screens come straight from `app/src/main/assets/ui`, so a fix lands in both apps.

## Build and run

1. Open `ios/S1Dash.xcodeproj` in Xcode.
2. Under the S1Dash target › Signing & Capabilities, choose your team.
3. Pick your iPhone and press Run. The iPhone needs Developer Mode on
   (Settings › Privacy & Security).

With a free Apple ID the app stops opening after 7 days; run it from Xcode again to renew it.
