This App is designed to replace the stock messaging App on the Kyocera E4810 flip phone. (E4811 and E4610 may work but are not fully tested.)

Key features:

•	Speech-to-text provided using a Groq API key. Simply press the dictate button and say what you want it to type!

•	MMS support. Send and receive pictures, video, voice messages, contact cards and other files. Videos are compressed automatically to fit your carrier's picture-message size limit.

•	Group messaging support. Groups show up in a separate list.

•	Translation to English of incoming texts to most languages.

•	Archives all conversations with a click of a button. 

Installation instructions:

1.	Install the TurboText app on the phone:
2.	 a. You can install through Android Studio, or copy the .APK to the phone, then open it in the phone.
3.	 b. When the App opens, go to: options/settings/advanced/ , set the default texting app to TurboText, and optionally enable “Accessibility Service” (it lets TurboText flash the outer-screen message icon when you press a key while you have unread texts).
4.	 c. Optional: you can set the “provisioning phone number” if you want to be able to set the API key remotely. (See below for more details) This app is using Groq’s API Whisper Speetch-to-text service. You will need a Groq API key to make it work.

There are two methods for entering the API key into the app.

2.	Copy and paste the key into the text file found in: Internal storage/Turbo key/
3.	You can set the key remotely by sending it from a Phone number that matches what you enter in step 1.-c Above. The API key needs encoded using Base64 format (base64encode.org) then prefixed like follows: TURBOVOICE_SETUP:put your encoded api key here. Send this encoded and prefixed text to the phone that you are setting up, TurboText will read it and the Speech-to-text and translation should start working. (You should not see the text come into the phone. The app scraps it and does not let you see it.)
4.	There are half a dozen sounds for the notifications by default, but you can add more also: Internal storage/notifications . (.mp3 and .ogg files are supported.) 

Updates:

TurboText checks this repo's GitHub Releases about once a week when you open the conversation list, and asks before installing a newer version (Wi-Fi is preferred for the download). You can also check any time from options/settings/advanced/Check for Updates. The first time, Android asks you to allow TurboText to install apps; allow it and the install carries on.

Publishing a release (for the maintainer):

1.	One time: create a release signing key and a keystore.properties file (see keystore.properties.example). Back up the key and its passwords. Android only installs an update signed with the same key as the installed app, so a lost key means every phone has to uninstall and reinstall. For the same reason, a phone running a debug build or an APK signed with a different key has to uninstall it once and install a signed release before in-app updates work.
2.	In app/build.gradle, raise versionCode by 1 and set versionName (e.g. "1.0.4"), then commit.
3.	Build the signed APK: ./gradlew :app:assembleRelease (output: app/build/outputs/apk/release/app-release.apk).
4.	On GitHub, go to Releases, Draft a new release, create the tag v. plus the versionName (e.g. v.1.0.4; v1.0.4 works too), attach app-release.apk, and click Publish release. Don't mark it as a draft or pre-release, because the app skips those. The repo has to stay public, since the app reads releases without logging in.

Disclaimer: I am not a programmer, I just like to make things work, and AI helps make things work. 😉
