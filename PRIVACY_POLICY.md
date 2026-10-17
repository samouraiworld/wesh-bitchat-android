# Wesh Privacy Policy

*Last updated: October 2026.* Wesh is a fork of BitChat. This file mirrors the policy published at https://wesh.samourai.app/en/privacy; the French version, https://wesh.samourai.app/confidentialite, is the reference.

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
- Keys are held in the iOS Keychain or protected by the Android Keystore. They do not leave the phone.

## 4. Backups
- **Android:** Wesh excludes all of its data from Google backups and from transfers to a new phone.
- **iPhone:** Wesh's files and keys are excluded from iCloud and computer backups. These backups contain none of your messages, contacts, keys or nickname.
- They may contain:
  - the app's settings (appearance, notices accepted<!-- INTERNET:START -->, whether Internet mode is on<!-- INTERNET:END -->);
  - today's count of the phones you passed: for each one, a hash of its peer ID, stored with the secret value used to compute it, and the time you last met. Because peer IDs are stable, someone who has this backup and a phone's peer ID can check that you passed that phone that day;
  - for at most 24 hours, a text you shared to Wesh and have not used yet.
- Restoring a backup therefore restores neither your identity, nor your contacts, nor your conversations.

## 5. What others see over Bluetooth
- Any Bluetooth device in range can tell that a phone runs Wesh or BitChat.
- While Wesh is running, including in the background, your phone regularly announces to phones in range:
  - your **nickname**;
  - your **peer ID** and your **public keys**;
  - the features your app supports;
  - the IDs of the phones it is directly linked to.
- These announcements are relayed from phone to phone, over at most 7 hops.
- On Android, Wesh runs in the background by default and starts again after the phone restarts, without you opening it. No setting can turn this off today. To stop the announcements, turn Bluetooth off.
- **Your peer ID and public keys do not change until you use the emergency erase.** A device that records them can recognise your phone from one encounter to the next and see which phones it was linked to.
- Signal strength lets others estimate how far away your phone is.
- **Relaying:** your messages may pass through third-party phones (Wesh or BitChat). Each relaying phone sees the sender's and recipient's IDs, the time and the size of the message. It does not see the content of a private message, which is end-to-end encrypted.
- **The local room is public.** Every Wesh and BitChat user in range can read, keep and relay it.
- **Private messages:** only with contacts verified by QR code. They are end-to-end encrypted: only your contact can read them.

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
- **Réglages → Tout effacer** (Settings → Erase everything), held for three seconds.
- It erases from this phone: identity and keys, nickname, contacts, conversations, pending messages, drafts, meeting note, settings and notifications<!-- INTERNET:START -->, as well as the Internet key and Tor's files; Internet mode is turned off<!-- INTERNET:END -->.
- A new identity is then created, with a new peer ID and new keys.
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
- Messages go through the **Tor** network, then through three public **Nostr** relays that Wesh does not run: `relay.damus.io`, `relay.primal.net` and `offchain.pub`. Wesh uses no other relay.
- Relays see neither your IP address nor the content. They see the recipient's Internet key, the time and size of messages, and can link your sends to your key.
- Anyone who has your QR code knows your Internet key.
- The Tor entry relay sees your IP address, but not which relays you reach or what you send.
- Relays may keep the encrypted messages under their own rules. **These copies cannot be deleted**, not by you, not by Samouraï Coop, not by the emergency erase.
- Samouraï Coop neither controls nor receives this data.
- On the phone, Wesh keeps your Internet key and Tor's files. The emergency erase deletes them; a new key is created afterwards.
<!-- INTERNET:END -->
