# TurboText — What's New

## New in 1.0.5

### Messaging
- After you send from New Message, TurboText opens that conversation. Your message shows "Sending", then "Sent" and "Delivered", without having to reopen it.
- Pressing Send while a word is still a suggestion sends it as shown, the same as pressing OK first.
- Picture messages you receive are now confirmed to the carrier, so whoever sent them sees "Delivered", and the carrier stops sending some pictures twice. For your own pictures to show "Delivered", the person you send to needs this version too (or another messaging app).
- Picture-message delivery reports that arrive early are no longer lost. "Delivered" shows more reliably, including "Delivered to 2 of 3" in groups.
- Text delivery reports are read correctly. A text that couldn't be delivered shows "Not delivered" instead of "Sent".
- Group conversations no longer split in two, which made messages seem to go missing. Conversations that were already split are joined back together.
- A new picture message is no longer dropped when the carrier reuses an old message's download address.
- Reading back through a conversation no longer jumps to the bottom on its own.
- Options > Mark All as Read, on the conversation and group lists.
- Picture and group messages are received again. TurboText now asks for Android's "receive picture messages" permission at startup. It could be missing, and then Android silently dropped every incoming picture message, group message and picture delivery report.
- Picture messages go through more reliably:
  - If sending fails because of a weak signal or no data connection, TurboText tries again twice: after 20 seconds and after 90 seconds.
  - If the carrier refuses a picture, it shows "Failed" instead of "Sent".
  - Pictures are now kept under 600 KB, which every US carrier passes on. Bigger ones were sometimes dropped on the way to someone on another carrier.
- A delivered picture message stays "Delivered" even when the carrier later sends an "expired" report for it.
- Group messages: a number wrongly taken for this phone's own is forgotten, so that person's group messages land in the group again. A group message can no longer end up in the sender's private conversation that way.

### Broadcast lists
- Import a customer list from a CSV file: Broadcast Lists > + New List from CSV, or Options > Import from CSV on a list. Any column with phone numbers works, with an optional name column (Name, or First Name + Last Name). A header row is optional.
- Numbers already in the list are updated, not added twice, so you can import a newer copy of the same file.
- Sending asks first, then texts each person one at a time, a few seconds apart, with progress in a notification. It keeps going if you leave the screen. After about 30 texts, Android may ask whether TurboText may send this many messages. Choose to always allow it.

### Typing
- Press * past ALL CAPS for the emoji picker: up to about 500 emojis (only the ones this phone can show) in a grid, by category, with your most-used under Recent. Use the D-pad to move, Left/Right at the edge to change category, and OK to insert. # inserts and keeps the picker open.

### Look & Feel
- Message bubbles have a small tail on the sender's side.
- The picture viewer works like MatChat's: # zooms in, * zooms out, the D-pad or 2/4/6/8 moves around, and 0 resets. Options has Save to gallery and Save to files.

### Outer screen
- Works like MatChat's. A new message shows the time, who it's from and the message, scrolling across the outer screen. It comes back each time you close the flip or press an outside button, until you read it.
- New: Settings > Notifications > Hide Message on Cover, which shows only "New message".

### Running in the background
- The Accessibility service is now called Background Helper. It keeps TurboText running in the background with no notification. Turn it on under Settings > Advanced > Background Helper.
- While Background Helper is off, TurboText shows a quiet "TurboText is running" notification so Android keeps it running.

### Notifications
- Do Not Disturb and silent/vibrate mode are respected. The notification still appears, but without sound or buzz.

### Report
- New Settings > Report, protected by a PIN you choose the first time. Once a day at the time you set, it texts a number you choose how many texts this phone sent to and received from each contact, for example "Boby - 45 sent, 34 received".
- Contact names are found however the number is written, and the time is picked with an hour, minute and AM/PM selector.
- Contacts saved without the area code are matched too. A number with no contact shows as (555) 123-4567 instead of +15551234567.

### Removed
- Settings > Advanced's option to open another app.
- Options > Refresh (the list refreshes by itself).
- Settings > Advanced > T9 Prediction Engine. TT9 is the only engine.

## Messaging
- Send and receive pictures, video, voice messages, contact cards and files. Videos shrink automatically to fit MMS size limits.
- New **Record Video** and **File** attach options, and you can share to TurboText from other apps.
- Send up to 5 photos in one message. Pick several at once in the gallery, or use Attach again to add more. Sharing several photos from another app works too.
- Received messages with several photos now keep them all. The bubble shows "+2" and so on, and opening it lets you step through them with Left/Right. Save to Gallery saves every one.
- Fixed incoming picture messages showing as "Unknown". Press OK on an undownloaded message to fetch it.
- Failed sends keep your text and attachment.
- Picture messages now show "Delivered" when the other phone gets them, "Delivered to 2 of 3" in a group, or "Not delivered". This depends on your carrier sending delivery reports, as with texts.
- Failed picture messages show on your side of the conversation instead of looking like incoming messages.
- Message notifications now clear when you open the conversation, including picture and group messages, and tapping an older notification opens the right conversation.

## Typing
- New T9 engine built on the Traditional T9 dictionary. It learns your words and common word pairs.
- "I" comes first on the 4 key, and contractions (I'm, it's, you're…) work.
- 50 emojis, with your most-used first.
- Hold # to start a new line. A tap still adds a space, and holding # no longer adds a row of spaces.
- Fixed "in", "at" and other short words coming out capitalized when a contact's name contained them.
- Fixed the next word being capitalized after holding a number key at the start of a sentence.

## Look & Feel
- Contact pictures or colored initials in lists and group chats.
- Smoother scrolling, with a grey position bar on the right.
- "Yesterday" or the weekday for recent messages, then the date.
- The white soft-key bar at the bottom is hidden on every screen, giving messages more room.
- Message bubbles and photos have smaller, matching rounded corners on all four sides.
- New app icon with a transparent background.

## Updates
- In-app updates: TurboText checks for a new version about once a week and asks before installing it. You can also check any time under Settings > Advanced > Check for Updates.

## Setup
- On startup, asks to be the default texting app and to turn on Accessibility, if they aren't set yet.

## Removed
- Reading messages aloud, the Bluetooth headset mic option, and the home-screen right soft key takeover.
