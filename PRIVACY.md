# Privacy Policy: TrekUI Launcher

*Effective: 18 November 2026. Applies to TrekUI Launcher for Android (`com.gungreeneyes.trekui`) and its test build TrekUI Dev (`com.gungreeneyes.trekui.dev`).*

TrekUI Launcher is a home-screen app. It was built to keep your data on your phone.

- **We do not collect, store or sell personal data.** There is no developer server that receives data from the app, no user account, no analytics, no advertising, no crash-reporting SDK and no tracking identifier. The one developer address the app reads from is a static folder of the app's own sounds (below).
- **Your settings stay on your device.** Your layout, settings and sounds live in the app's private storage on your device.
- **Every network request is listed below, and all but one are ones you turned on.** The exception is a check, at most once a day, for updated versions of the app's own sounds. All of them use encrypted HTTPS, except commands to Govee lights on your own Wi-Fi and, if you choose an `http://…local` address, your own Home Assistant on your home network.
- **Sounds.** The app downloads its own sound files from mindattic.com and, only if you opt in (DOWNLOAD SOUND LIBRARY), recordings from the public sources listed in the app. No personal data is sent: each download is a plain HTTPS GET, so the server sees your IP address as with any web request.

## What the app reads on your device, and why

The app reads the following on your phone to show it to you. **None of it is transmitted anywhere.**

| Data | Why | Permission |
|---|---|---|
| Installed apps | App drawer, search, home tiles | none (Android's launcher API) |
| Contacts | console dialer: contact matching and the CONTACTS tab | Contacts, optional, asked for on first use |
| Call history | console dialer RECENTS tab, in builds that include it | Call logs, optional, asked for on first use |
| Calendar events (next 7 days) | Schedule panel | Calendar, optional, asked for when you tap Grant |
| Approximate location | Weather for where you are, if you choose "device location" | Approximate location, optional |
| Media sessions and notifications | Track title and artwork in the media panel; the notification list in the console shade (with REPLY, which sends your text only to the app that posted the notification); unread counts on home tiles | Notification access, optional, you turn it on in Android settings. Nothing is stored or logged; counts are kept in memory only. |
| Battery, CPU, memory, storage, network rate | Power and Systems panels | none |

Placing calls: if you allow "make phone calls", the console dialer places calls directly. Otherwise it hands the number to your phone app. Emergency numbers always go through your phone app.

## Network connections

| When | Where to | What is sent | Why |
|---|---|---|---|
| Weather panel is on: at most every 30 minutes while the home screen is visible, or when you tap REFRESH NOW | **Open-Meteo**: `api.open-meteo.com` | Latitude and longitude: your approximate location or the city you chose. Nothing that identifies you. | Current weather and forecast |
| You search for a city in weather settings | **Open-Meteo Geocoding**: `geocoding-api.open-meteo.com` | The city name you typed, and your language | Find the city's coordinates |
| You set up and use Govee lights | Your Govee lights on your **local Wi-Fi** (UDP multicast, LAN API) and, if you entered a Govee API key, **Govee's cloud API** (`openapi.api.govee.com`) | On/off commands and device IDs; your Govee API key, sent to Govee only | Turn your lights on and off |
| You set up and use Home Assistant | **Your own Home Assistant** at the address you enter (HTTPS, or plain HTTP only to a `.local` / `.home.arpa` name on your home network) | Your long-lived access token; entity lists, states and commands | Control your lights, switches, climate, scenes and scripts |
| You link and use a Nest thermostat | **Google** (`www.googleapis.com` OAuth, `smartdevicemanagement.googleapis.com`) | The OAuth authorisation code and tokens from your own Google Device Access project; thermostat readings and commands | Read and set your thermostat |
| Automatically, at most once a day while the home screen is used, and when you open settings | **mindattic.com** (`mindattic.com/gungreeneyes/trekui/sfx/`), a static file folder run by the developer | A plain HTTPS GET for `manifest.json` and any changed sound file: no identifier, no account, no analytics. Like any web server, it sees your IP address in its access log. | Fix or add the app's own (synthesized) sounds without an app update |
| You tap DOWNLOAD SOUND LIBRARY | The public web pages listed in the app's sound catalogue and GitHub | Ordinary HTTPS downloads; no personal data | Fetch sound clips, which are then processed and kept on your device |
| You buy or restore the unlock | **Google Play** (Play Billing) | Handled by Google Play under [Google's privacy policy](https://policies.google.com/privacy). The app receives only whether you own the unlock. | One-time $0.99 unlock |
| You tap a web search or a link | Your browser | Whatever you search for, handled by your browser | Web search hand-off |

The Govee API key, the Nest tokens and the Home Assistant token are stored only in the app's private storage on your device (the Home Assistant address and token are also excluded from Android backup and layout export). They are sent only to Govee or Google respectively, when you use those devices.

Open-Meteo, Govee and Google have their own privacy policies: [Open-Meteo](https://open-meteo.com/en/terms), [Govee](https://www.govee.com/policies/privacy-policy) and [Google](https://policies.google.com/privacy).

## Storage, backup and deletion

- **On your device.** Your settings, layout and licence state are stored in the app's private storage.
- **Android backup.** If you have Android backup turned on, Android may include the settings in your device backup to your Google account, encrypted by Android. Downloaded and imported sound files are excluded from backup and device transfer.
- **Deleting your data.** Clear the app's storage (**Settings › Apps › TrekUI › Storage › Clear data**) or uninstall the app. Because there is no account or server, there is nothing else to delete and nothing for us to hand over.
- **Exported files.** If you export your layout or app art, the file goes where you save it, and you control it.

## Children

TrekUI Launcher is not directed at children. It is intended for adults (18+). We do not knowingly collect any information from anyone, including children.

## Changes

If this policy changes, the new version will be published at this address with a new effective date. The history is in the project's [Git repository](https://github.com/GunGreenEyes/TrekUI/commits/main/PRIVACY.md).

## Contact

For questions about privacy, open an issue at <https://github.com/GunGreenEyes/TrekUI/issues>, or use the developer contact email shown on the Google Play listing.

*TrekUI Launcher is an independent app with no third-party sponsors.*
