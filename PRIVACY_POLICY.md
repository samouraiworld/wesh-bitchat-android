# Wesh Privacy Policy

*Version 1.7, 28 October 2026.* This file is a copy of the published policy: https://wesh.samourai.app/en/privacy (English) and https://wesh.samourai.app/confidentialite (French). The French page is the reference; if this file differs from it, the French page prevails. Wesh is a fork of BitChat.

## 1. Who we are

Wesh is a free, open source app published by **SAMOURAI COOP**, a French cooperative limited liability company with variable capital, registered under number 830 485 108 R.C.S. Paris, whose registered office is at 149 avenue du Maine, 75014 Paris, France.
Contact: support@samourai.coop. Security issues: security@samourai.coop.

## 2. In short

- No account, no phone number, no email address required.
- Samouraï Coop runs no server for Wesh. Exchanges are not automatically addressed to it. If you choose to email support, it receives what you send (§7).
- Wesh does not read your location.
- Wesh includes no analytics, crash-reporting or advertising tool, and does not itself send any such data. If you allowed it in your phone's settings, Apple or Google may collect system diagnostics, such as crash reports, and pass some of them to Samouraï Coop in their developer tools, under their own rules.
- Over Bluetooth, nearby phones see your nickname and a fixed identifier. It does not change until you use the emergency erase: someone who noted it can recognise your phone when it comes near again (§5).
<!-- INTERNET:START -->
- Internet mode goes through Tor. Your network operator can see that you use Tor, and some networks block it (§12).
<!-- INTERNET:END -->
- The local room is public.

## 3. Data kept on your phone

- Nickname, identity keys, verified contacts, drafts, meeting note, settings.
- Private messages: on Android, the history is stored encrypted on the phone. On iPhone, it stays in memory and is gone when the app is closed.
- A private message not yet delivered is kept encrypted for at most 24 hours.
- Recent local-room messages may be kept for a few hours, so they can be relayed to phones that arrive later.
- On iPhone, Wesh may also keep, for at most 24 hours, an encrypted private message that a verified contact hands it for someone else. You cannot read it.
- Private keys are held in the iOS Keychain or protected by the Android Keystore. Wesh does not transmit these private keys. Public keys are shared with other devices (§5; backups: §4).

## 4. Backups

- **Android:** Wesh excludes all of its data from Google backups and from transfers to a new phone.
- **iPhone:** Wesh configures its sensitive files to be excluded from iCloud and computer backups. Keys tied to the device do not migrate to another device. Restoring on the original device may retain these keys. App settings may be backed up (appearance, notices accepted<!-- INTERNET:START -->, whether Internet mode is on<!-- INTERNET:END -->).
- This version no longer counts encountered phones and no longer receives text through the retired share extension. A backup made by an older version may still contain that count, its secret value, encounter times, old geographic-room preferences or unused shared text. A peer ID recorded elsewhere may allow a phone to be recognized in the old count.
- Updating and emergency erase do not modify backups already made: an old backup may still contain Wesh data.

## 5. What others see over Bluetooth

- Any Bluetooth device in range can tell that a phone runs Wesh or BitChat.
- While Wesh is running, including in the background, your phone regularly announces to phones in range:
  - your **nickname**;
  - your **peer ID** and your **public keys**;
  - the features your app supports;
  - up to **10 IDs of peers it is directly linked to**.
- These announcements are sent automatically, even if you post nothing in the local room. They are readable by the peers that receive them: signing does not encrypt them. The neighbour list describes proximity and routing links; it is not imported from the phone's address book and does not necessarily represent contacts verified by QR code.
- These announcements are relayed from phone to phone, over at most 7 hops.
- On Android, Wesh may continue running in the background after you open it. **Start after restart** allows startup after the phone restarts, if Android permits it. It defaults to off when no choice was recorded; an existing recorded choice is preserved. This setting does not change the current session.
- **Your peer ID and public keys stay the same in normal use.** A device that records them can recognise your phone every time it comes near and see which phones it was linked to. The Bluetooth address changes made by the system do not prevent this, because the ID is part of the announcements. Only the emergency erase, or deleting Wesh's data (uninstalling, a new phone), creates a new ID.
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

- **Reporting someone:** Wesh blocks them on your phone, then prepares an email to support@samourai.coop in your mail app. You review it and choose whether to send it.
- This email holds data about the reported person: their nickname, their short fingerprint and, for a local-room message, its text. It also holds the reason and where you met them (local room, request or contact). It never holds your private messages, your keys or your location.
- **When you write to us,** we receive your email address and whatever you choose to write. Your email provider also processes that email.
- Purpose: to reply and handle the report (legitimate interest: service safety).
- Timing: handled on a best-effort basis according to severity, with no contractual promise of a response within any fixed period.
- Retention: as long as needed to handle your request, then deletion.

## 8. Emergency erase

- In **Settings**, hold **Erase everything** for three seconds.
- The **Erase everything** shortcut (on Android, from the app icon; on iPhone, from Spotlight or the Shortcuts app) opens the same hold-to-erase control. It never erases by itself.
- It erases from this phone: identity and keys, nickname, contacts, conversations, pending messages, drafts, meeting note, settings and notifications<!-- INTERNET:START -->, as well as the Internet key and Tor's files; Internet mode is turned off<!-- INTERNET:END -->.
- Afterwards, Wesh returns to the welcome screen and starts again with a new identity, peer ID and keys.
- **iPhone:** if Wesh cannot confirm that everything was erased, it says that some data may remain on the phone. It still returns to the welcome screen, but keeps Bluetooth<!-- INTERNET:START --> and Internet<!-- INTERNET:END --> off until an erasure is confirmed. Wesh erases again at its next launch, before connecting; you can also retry from the notice at the top of Wesh's main screen.
- **Android:** Wesh deletes its own files and keys, then restarts. If it cannot confirm this, it asks Android to clear all of the app's data: the app closes and shows the welcome screen at its next launch. An erase interrupted before the end is finished at the next launch, before anything starts.
- It does not erase what others have received or recorded (local-room messages, Bluetooth announcements<!-- INTERNET:START -->, copies on Nostr relays<!-- INTERNET:END -->), nor iPhone backups already made (§4).
- Uninstalling the app also deletes its data. On iPhone, keys left in the Keychain are erased at the next install.

## 9. Your rights

- You have the GDPR rights of access, rectification, erasure, objection and restriction.
- You manage the data on your phone directly in the app (edit nickname, erase). Exchanges are not automatically addressed to Samouraï Coop; if you choose to email support, it receives what you send (§7).
- Samouraï Coop only holds the emails sent to it (§7) and, where applicable, the diagnostics Apple or Google pass to it (§2). A report may contain another person's nickname, short fingerprint and a public text. To exercise your rights over these emails, including if you were reported: support@samourai.coop.
- To request deletion of data we hold, including information you voluntarily sent to support, email [support@samourai.coop](mailto:support@samourai.coop). You do not need to reinstall Wesh to make this request. We do not control copies kept by other participants or third-party relays; this request cannot guarantee their deletion.
- You may complain to the CNIL (www.cnil.fr) or your local data protection authority.

## 10. Minimum age

Wesh is for people aged **18 and over**. The current app does not ask for a date of birth or verify participants' ages; the Wesh website does not perform age checks either.

Google Play's minor-access restriction, when enabled for Wesh, blocks search and download for people Google identifies as minors. People who already installed the app can continue using it. This control does not cover installations outside Google Play, the Bluetooth mesh or third-party BitChat clients and does not guarantee every participant's age.

## 11. Changes

Any change is posted on this page with its date. Significant changes are flagged in the release notes.

<!-- INTERNET:START -->
## 12. Internet mode (optional, off by default)

- You turn it on in **Connection**. The emergency erase turns it off.
- It is used only for private messages to verified contacts whose QR code carries an Internet key.
- Messages go through the **Tor** network, then through three public **Nostr** relays that Wesh does not run: relay.damus.io, relay.primal.net and relay.snort.social. Wesh uses no other relay.
- Relays do not see your direct IP address when the connection goes through Tor, or the text of private messages. For each envelope, they see the recipient's Internet key in a readable field (the `p` tag), type, timestamp, size and transport metadata. The outer envelope is signed by a one-time ephemeral sender key, so it does not expose the sender's durable protocol identity.
- To receive messages, Wesh also sends your own Internet key to the relays in a reception filter (`#p`), even if you send no text. Private-content encryption does not hide these keys from relays. These identifiers, recipients and the timing of exchanges may allow events or relationships to be linked; the ephemeral envelope key and modified timestamp do not rule out traffic correlation by a third party.
- Your QR code carries your Internet key, even while Internet mode is off: anyone who has your QR code knows it. A verified contact who turned the mode on may write to you through the relays, which then see your Internet key as the recipient.
- The Tor entry relay sees your IP address, but not which relays you reach or what you send.
- **Using Tor is visible.** Your mobile operator, the Wi-Fi network you use or a network observer can see that your phone connects to Tor, without seeing the content or the relays you reach. Some networks and some countries block Tor: Internet mode may then not work. In some settings, using Tor may draw attention.
- Relays may keep encrypted messages and readable metadata under their own rules. **Erasing data through Wesh on this phone does not erase copies kept by relays**, whose operators apply their own rules.
- Samouraï Coop does not control these relays. Internet mode does not address a copy of these exchanges to Samouraï Coop; this does not make the metadata invisible to relays.
- On the phone, Wesh keeps your Internet key and Tor's files. The emergency erase deletes them; a new key is created afterwards.

<!-- INTERNET:END -->

