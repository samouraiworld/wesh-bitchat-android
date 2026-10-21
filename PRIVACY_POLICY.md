# Wesh Privacy Policy — UNPUBLISHED V1 candidate draft

*Requires technical and device qualification before publication. Last updated: 21 October 2026.* Wesh is a fork of BitChat. Reference pages: https://wesh.samourai.app/en/privacy; the French version, https://wesh.samourai.app/confidentialite, is the reference.

## 1. Who we are
Wesh is a free, open source, non-profit app published by **Samouraï Coop**, SARL SCOP, 149 avenue du Maine, 75014 Paris, France.
Contact: `support@samourai.coop`. Security issues: `security@samourai.coop`.

## 2. In short
- No account, no phone number, no email address required.
- Samouraï Coop runs no server for Wesh and receives none of your messages, contacts or location.
- Wesh does not read your location.
- No analytics, no crash reporting, no ads, no tracking.
- Over Bluetooth, nearby phones see your nickname and an identifier that does not change until you use the emergency erase (§5).
- The local room is public.

## 3. Data kept on your phone
- Nickname, identity keys, verified contacts, drafts, meeting note, settings.
- Private messages: on Android, the history is stored encrypted on the phone. On iPhone, it stays in memory and is gone when the app is closed.
- A private message not yet delivered is kept encrypted for at most 24 hours.
- Recent local-room messages may be kept for a few hours, so they can be relayed to phones that arrive later.
- On iPhone, Wesh may also keep, for at most 24 hours, an encrypted private message that a verified contact hands it for someone else. You cannot read it.
- Keys are held in the iOS Keychain or protected by the Android Keystore. Wesh does not send them to anyone (backups: §4).

## 4. Backups
- **Android:** Wesh excludes all of its data from Google backups and from transfers to a new phone.
- **iPhone:** Wesh configures its sensitive files to be excluded from iCloud and computer backups. Keys tied to the device do not migrate to another device. Restoring on the original device may retain keys and still requires qualification. App settings may be backed up (appearance, notices accepted<!-- INTERNET:START -->, whether Internet mode is on<!-- INTERNET:END -->).
- This version no longer counts encountered phones and no longer receives text through the retired share extension. A backup made by an older version may still contain that count, its secret value, encounter times, old geographic-room preferences or unused shared text. A peer ID recorded elsewhere may allow a phone to be recognized in the old count.
- Updating and emergency erase do not modify backups already made. Candidate V1 migration and restoration still require device qualification before publication; a general guarantee that an old backup contains no data has not been established.

## 5. What others see over Bluetooth
- Any Bluetooth device in range can tell that a phone runs Wesh or BitChat.
- While Wesh is running, including in the background, your phone regularly announces to phones in range:
  - your **nickname**;
  - your **peer ID** and your **public keys**;
  - the features your app supports;
  - the IDs of the phones it is directly linked to.
- These announcements are relayed from phone to phone, over at most 7 hops.
- On Android, Wesh may continue running in the background after you open it. **Start after restart** allows startup after the phone restarts, if Android permits it. It defaults to OFF when no choice was recorded; an existing recorded choice is preserved. This setting does not change the current session.
- **Your peer ID and public keys do not change until you use the emergency erase.** A device that records them can recognise your phone from one encounter to the next and see which phones it was linked to.
- Signal strength lets others estimate how far away your phone is.
- **Relaying:** your messages may pass through third-party phones (Wesh or BitChat). Each relaying phone sees the sender's and recipient's IDs, the time and the size of the message. It does not see the content of a private message, which is end-to-end encrypted.
- **The local room is public.** Every Wesh and BitChat user in range can read, keep and relay it.
- **Private messages:** only with contacts verified by QR code. They are end-to-end encrypted: only your contact can read them.
- **Your QR code** also carries a key for the optional Internet mode, even while that mode is off. The key is created on the phone at the end of the welcome and is not used on the network unless you turn the mode on. Anyone who has your QR code knows it.

## 6. Permissions
- **Bluetooth:** talk to nearby phones.
- **Camera:** scan QR codes. No image is stored or sent.
- **Notifications:** alert you to new messages. They are created on the phone, with no remote push service.
- **Location (Android 11 and earlier only):** Android requires this permission to scan for Bluetooth devices. Wesh reads no location and sends none. On Android 12 and later, and on iPhone, Wesh does not ask for it.
- Wesh uses neither the microphone, nor the photo library, nor the phone's contacts.

## 7. Reports and support
- **Reporting someone:** Wesh blocks them on your phone, then prepares an email to `support@samourai.coop` in your mail app. You review it and choose whether to send it.
- This email holds data about the reported person: their nickname, their short fingerprint and, for a local-room message, its text. It also holds the reason and where you met them (local room, request or contact). It never holds your private messages, your keys or your location.
- **When you write to us,** we receive your email address and whatever you choose to write. Your email provider also processes that email.
- Purpose: to reply and handle the report (legitimate interest: service safety).
- Retention: as long as needed to handle your request, then deletion.

## 8. Emergency erase
- In **Réglages** (Settings), hold the erase control (**Tout effacer**) for three seconds.
- The **Tout effacer** shortcut (on Android, from the app icon; on iPhone, from Spotlight or the Shortcuts app) opens the same hold-to-erase control. It never erases by itself.
- It erases from this phone: identity and keys, nickname, contacts, conversations, pending messages, drafts, meeting note, settings and notifications<!-- INTERNET:START -->, as well as the Internet key and Tor's files; Internet mode is turned off<!-- INTERNET:END -->.
- Afterwards, Wesh returns to the welcome screen and starts again with a new identity, peer ID and keys.
- **iPhone:** if Wesh cannot confirm that everything was erased, it says that some data may remain on the phone. It still returns to the welcome screen, but keeps Bluetooth<!-- INTERNET:START --> and Internet<!-- INTERNET:END --> off until an erasure is confirmed. Wesh erases again at its next launch, before connecting; you can also retry from the notice at the top of Wesh's main screen.
- **Android:** Wesh deletes its own files and keys, then restarts. If it cannot confirm this, it asks Android to clear all of the app's data: the app closes and shows the welcome screen at its next launch. An erase interrupted before the end is finished at the next launch, before anything starts.
- It does not erase what others have received or recorded (local-room messages, Bluetooth announcements<!-- INTERNET:START -->, copies on Nostr relays<!-- INTERNET:END -->), nor iPhone backups already made (§4).
- Uninstalling the app also deletes its data. On iPhone, keys left in the Keychain are erased at the next install.

## 9. Your rights
- You have the GDPR rights of access, rectification, erasure, objection and restriction.
- The app sends nothing to Samouraï Coop. You manage the data on your phone directly in the app (edit nickname, erase).
- Samouraï Coop only holds the emails sent to it (§7). A report may contain another person's nickname, short fingerprint and a public text. To exercise your rights over these emails, including if you were reported: `support@samourai.coop`.
- You may complain to the CNIL (www.cnil.fr) or your local data protection authority.

## 10. Minimum age
Wesh is for people aged **16 and over**.

## 11. Changes
Any change is posted on this page with its date. Significant changes are flagged in the release notes.

<!-- INTERNET:START -->
## 12. Internet mode (optional, off by default)
- You turn it on in **Connexion**. The emergency erase turns it off.
- It is used only for private messages to verified contacts whose QR code carries an Internet key.
- Messages go through the **Tor** network, then through three public **Nostr** relays that Wesh does not run: `relay.damus.io`, `relay.primal.net` and `relay.snort.social`. Wesh uses no other relay.
- Relays see neither your IP address nor the content. They see the recipient's Internet key, the time and size of messages, and can link your sends to your key.
- Anyone who has your QR code knows your Internet key.
- The Tor entry relay sees your IP address, but not which relays you reach or what you send.
- Relays may keep the encrypted messages under their own rules. **These copies cannot be deleted**, not by you, not by Samouraï Coop, not by the emergency erase.
- Samouraï Coop neither controls nor receives this data.
- On the phone, Wesh keeps your Internet key and Tor's files. The emergency erase deletes them; a new key is created afterwards.
<!-- INTERNET:END -->
