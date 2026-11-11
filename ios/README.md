# S1 Dash for iPhone (dev build)

The same dash screens as the Android app, in a small Swift wrapper. For now it
runs the built-in demo data: no OpenHaldex or ECU connection yet, and no CarPlay.
The screens come straight from `app/src/main/assets/ui`, so a fix lands in both apps.

**CarPlay (simulator only, for now):** simulator builds carry Apple's CarPlay
"driving task" entitlement, so the CarPlay screen (live readings and Haldex modes,
demo data) can be tried in Simulator › I/O › External Displays › CarPlay. A real
head unit needs Apple to grant the entitlement first.

It also builds as a Mac app (Mac Catalyst): pick **My Mac** as the run destination.

## Build and run

1. Put your Apple team in `ios/Local.xcconfig` as `DEVELOPMENT_TEAM = <team ID>`
   (git ignores this file), or choose the team in Xcode under Signing & Capabilities.
2. Open `ios/S1Dash.xcodeproj` in Xcode.
3. Pick your iPhone or My Mac and press Run. An iPhone needs Developer Mode on
   (Settings › Privacy & Security).

The app icon comes from the Android logo: `ios/make-icons.sh` re-renders it.

With a free Apple ID the app stops opening after 7 days; run it from Xcode again to renew it.
