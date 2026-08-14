	This App is designed to replace the stock messaging App on the Kyocera E4810. (E4811 and E4610 may work but are not fully tested.)
Key features:
•	Speech-to-text provided using a Groq API key. Simply press the dictate button and say what you want it to type!
•	MMS support. Convenient to send and receive pictures and audio.
•	Group messaging support. Groups show up in a separate list.
•	Translation to English of incoming texts to most languages.
•	Text-to-speech with Bluetooth only option.
•	Self-launches in place of the default App, by pressing the standard Right soft key from the home screen.
•	Archives all conversations with a click of a button. 
•	You can set the speech-to-text input, to work with the noise canceling mic on your headset.

	Installation instructions:
1.	Install the TurboText app on the phone: a. You can install through Android Studio, or copy the .APK to the phone, then open it in the phone. b. When the App opens, go to: options/settings/advanced/ , set the default texting app to TurboText, enable “Accessibility Service” and enable “Usage Access”. (these settings allow TurboText to Launch from the home screen) c. Optional: you can set the “provisioning phone number” if you want to be able to set the API key remotely. (See below for more details) This app is using Groq’s API Whisper Speetch-to-text service. You will need a Groq API key to make it work. There are two methods for entering the API key into the app.
2.	Copy and paste the key into the text file found in: Internal storage/Turbo key/
3.	You can set the key remotely by sending it from a Phone number that matches what you enter in step 1.-c Above. The API key needs encoded using Base64 format (base64encode.org) then prefixed like follows: TURBOVOICE_SETUP:put your encoded api key here. Send this encoded and prefixed text to the phone that you are setting up, TurboText will read it and the Speech-to-text and translation should start working. (You should not see the text come into the phone. The app scraps it and does not let you see it.)
4.	There are half a dozen sounds for the notifications by default, but you can add more also: Internal storage/notifications . (.mp3 and .ogg files are supported.) 

Disclaimer: I am not a programmer, I just like to make things work, and AI helps make things work. 😉
