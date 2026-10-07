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

Disclaimer: I am not a programmer, I just like to make things work, and AI helps make things work. 😉
