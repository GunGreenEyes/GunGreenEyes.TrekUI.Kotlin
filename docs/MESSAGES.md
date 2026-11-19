# TrekUI Messages

`:messages` is a complete default SMS/MMS app (`com.gungreeneyes.trekui.messages`, develop flavor
`com.gungreeneyes.trekui.messages.dev`) drawn in the TrekUI interfaces. It depends on `:core` for the look
(console frame, cells, dialogs, fonts, sounds), licensing and the suite contract, and works with or without
the launcher installed.

## Features

- **Default SMS app:** asks for the role with `RoleManager.ROLE_SMS` (Android 10+) or
  `Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT` (8.0-9). A banner and a SET DEFAULT sidebar key show while
  it is not the default. The launcher's CONFIG › SUITE › MESSAGES sends
  `SuiteContract.ACTION_REQUEST_SMS_ROLE` to `RequestRoleActivity` (invisible, guarded by the signature-level
  suite permission), which asks for the role on Messages' own behalf.
- **Conversations:** list newest first (contact names, preview, time, unread marker, saved drafts), thread
  view with bubbles (sent / delivered / not sent state, SIM label on dual-SIM phones), new message to one or
  more numbers or a picked contact, CALL, BLOCK and DELETE, copy / delete / send again per message.
- **SMS:** sent with `SmsManager` (split with `divideMessage`, multipart), sent and delivery `PendingIntent`s
  per part. The row is written to the outbox first and moves to SENT / FAILED; delivery reports set
  `STATUS_COMPLETE / PENDING / FAILED` (CONFIG › DELIVERY REPORTS turns the request off). Incoming SMS arrive
  through `SMS_DELIVER`, are joined, written to the inbox and notified.
- **MMS:** pictures (system photo picker, up to 5) and/or text, or any message to several people, go out as
  one MMS via `SmsManager.sendMultimediaMessage`. Incoming MMS: the `WAP_PUSH_DELIVER` notification is parsed
  and the message downloaded at once with `downloadMultimediaMessage`, then stored in the inbox with its parts.
  PDUs are built and parsed by the app's own small codec (`mms/PduCodec`, `mms/MmsBuilder`: m-send-req with a
  generated SMIL layout in multipart/related, m-notification-ind, m-retrieve-conf, m-send-conf). Images are
  re-encoded as JPEG to fit a 300 KB message.
- **Dual SIM:** a SIM key in the compose bar cycles through the active SIMs; the choice is remembered per
  conversation. Default: the system's SMS SIM.
- **Search:** names and numbers of conversations plus SMS and MMS text, grouped per conversation with a match
  count.
- **Drafts:** unsent text is saved per recipient set when you leave a conversation and shown in the list.
- **Blocking:** exact numbers go to the system block list (`BlockedNumberContract`, shared with the dialer);
  prefix rules such as `+1900*` are kept by the app. Blocked senders' SMS and MMS are dropped silently.
- **Notifications:** one per conversation, messaging style, with inline REPLY (`RemoteInput`) and MARK READ.
  Each conversation gets its own channel in the "Conversations" group the first time it notifies, so a person
  can have their own sound or importance in system settings. Send failures use a separate channel.
- **Respond via message:** quick replies from the incoming-call screen are sent without opening the app.
- **Look:** follows the launcher's interface and sub-theme when the launcher is installed (`SuiteClient.launcherTheme`
  → `TrekPrefs.setThemeOverride`, refreshed on every screen resume and cached for cold starts); without it,
  CONFIG › INTERFACE offers the app's own interface and sub-theme picker. Portrait and landscape, phone to
  tablet (`ScreenMetrics`), edge-to-edge with keyboard insets.
- **Licensing:** the same 12-hour session trial and the same `trekui_unlock` product as every TrekUI app.
  One purchase unlocks every TrekUI app; an unlocked sibling unlocks Messages through the suite provider.

## Default SMS components

Android only offers the SMS role to an app that declares all of these (see the manifest):

| Component | Class | Guard |
|---|---|---|
| `SMS_DELIVER` receiver | `SmsDeliverReceiver` | `BROADCAST_SMS` |
| `WAP_PUSH_DELIVER` receiver (`application/vnd.wap.mms-message`) | `MmsPushReceiver` | `BROADCAST_WAP_PUSH` |
| `RESPOND_VIA_MESSAGE` service (sms/smsto/mms/mmsto) | `RespondViaMessageService` | `SEND_RESPOND_VIA_MESSAGE` |
| `SENDTO` activity (sms/smsto/mms/mmsto) | `ThreadActivity` | none (also `SEND` text/image share target) |

MMS PDUs reach the platform MMS service through `MmsFileProvider` (not exported, per-call URI grants).

## Code map (`messages/src/main/kotlin/com/gungreeneyes/trekui/messages/`)

| Class | Role |
|---|---|
| `data/PhoneNumbers`, `ThreadGrouper`, `MessageItem`, `DraftStore`, `BlockList` | Pure logic, JVM unit-tested |
| `mms/PduCodec`, `MmsBuilder`, `MmsPdu`, `MmsPart` | MMS PDU encode/decode, unit-tested by round trip |
| `MessageStore`, `MmsStore` | Telephony provider reads/writes (conversations, threads, search, read state, inserts) |
| `SmsSender`, `MmsSender`, `SmsStatusReceiver`, `MmsResultReceiver` | Sending, downloading and their results |
| `Notifier`, `NotificationActionReceiver` | Notifications, channels, inline reply, mark read |
| `Blocking`, `Sims`, `SmsRole`, `Contacts`, `Images`, `MessagesSettings` | Block list, dual SIM, role, names, images, settings |
| `BaseScreen`, `ConversationListActivity`, `ThreadActivity`, `BlockedActivity`, `ConversationRowView`, `BubbleView` | Canvas-drawn UI |
| `MessagesTheme` | Follows the launcher's interface/sub-theme |
| `com.gungreeneyes.trekui.MessagesLook` | `internal object` facade over `:core`'s look classes (Ui, Skin, Palette, ConsoleFrame, ConsoleDialog) |

## Known limits / follow-ups

- **Group MMS threading:** sending to several people works (one MMS to all), but a received group MMS is
  filed under its sender only; replies to it go to the sender. Follow-up: thread incoming MMS on sender + other
  To/Cc recipients minus the user's own number (from `SubscriptionInfo`), and show per-sender names in bubbles.
- **Failed MMS downloads** are notified but not retried; follow-up: keep the content location in an "undownloaded"
  MMS row (m-notification-ind, `m_type` 130) with a DOWNLOAD key in the thread.
- **Carrier limits:** the 300 KB budget is fixed; follow-up: read `CarrierConfigManager.KEY_MMS_MAX_MESSAGE_SIZE_INT`.
- No video/audio/vCard attachments (received ones are stored but only text and images are shown).

## Google Play: SMS permissions declaration

Play restricts SMS permissions to the default SMS handler. In Play Console › App content › Sensitive
permissions, declare **Default SMS handler** as the core functionality and use this text:

> TrekUI Messages is a full SMS and MMS messaging app that users choose as their default SMS handler. It
> needs SEND_SMS, RECEIVE_SMS, READ_SMS, RECEIVE_MMS and RECEIVE_WAP_PUSH to send, receive, store and display
> the user's text and picture messages, which is its core functionality. The app asks to become the default
> SMS app through Android's role request on first use; it declares every component Android requires of a
> default SMS app (SMS_DELIVER and WAP_PUSH_DELIVER receivers, a RESPOND_VIA_MESSAGE service and a SENDTO
> activity). Messages stay on the device in the system SMS/MMS store; the app sends no message content, contacts or
> phone numbers to any server (its only network use is Google Play Billing for the optional unlock). READ_CONTACTS only shows contact names next
> to numbers; READ_PHONE_STATE only lists the SIM cards for choosing which SIM sends.

The video Play asks for should show: installing, the role prompt, sending and receiving an SMS and a picture
message, and the inline reply.
