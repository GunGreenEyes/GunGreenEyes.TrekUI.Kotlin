package com.gungreeneyes.trekui

import java.util.Locale

/**
 * The full pictogram catalogue and the keyword table that gives common apps a sensible default
 * pictogram (LCA-T22).
 *
 * The 28 core pictograms are matched to exact packages by [IconFactory.GLYPH_RULES]; every
 * other app is looked up here by [defaultGlyph]: its package name and label are split into
 * words and compared with [KEYWORDS] (first row with a hit wins), and games without a hit
 * get the controller. The PNGs live in `assets/glyphs/`: the core 28 from
 * `tools/legacy/make_glyphs.py`, the rest rendered by `tools/art/glyph_render.py` from
 * `glyphs_extra.py`. Pure Kotlin, unit-tested in `PictogramsTest`.
 */
internal object Pictograms {
    /** Shortest keyword that may match the start of a longer word ("uber" → "ubercab"); shorter ones must match whole words. */
    const val PREFIX_MIN = 4

    /** Every pictogram name, core set first, in picker order. */
    @JvmField
    val ALL: Array<String> = arrayOf(
        // core 28 (tools/legacy/make_glyphs.py)
        "phone", "messages", "mail", "officemail", "contacts", "browser", "camera", "photos", "video", "music",
        "radio", "calendar", "clock", "notes", "calculator", "files", "maps", "weather", "fitness", "store",
        "search", "settings", "system", "apps", "home", "secure", "alert", "computer",
        // extended set (tools/art/glyphs_extra.py)
        "chat", "people", "videocall", "share", "bell", "translate", "mic", "headphones", "tv", "clapper",
        "book", "news", "gamepad", "puzzle", "trophy", "ball", "star", "bank", "wallet", "dollar", "chart",
        "cart", "bag", "gift", "ticket", "package", "truck", "plane", "car", "train", "bike", "rocket", "fuel",
        "bed", "compass", "globe", "flag", "school", "briefcase", "document", "sheet", "slides", "pen",
        "palette", "scissors", "qr", "lens", "printer", "code", "keyboard", "robot", "cloud", "download",
        "wifi", "bluetooth", "battery", "flashlight", "key", "shield", "bulb", "thermostat", "trash", "gauge",
        "wrench", "watch", "cast", "remote", "satellite", "planet", "alarm", "timer", "heart", "pill", "moon",
        "food", "coffee", "leaf", "paw", "umbrella", "drop", "shoe",
    )

    /**
     * Keyword rows: the pictogram, then words found in package names or labels of apps it suits.
     * Order matters (first row with a hit wins), so specific rows come before broad ones.
     */
    @JvmField
    val KEYWORDS: Array<Array<String>> = arrayOf(
        arrayOf("videocall", "zoom", "meet", "duo", "facetime", "webex", "skype", "videocall"),
        arrayOf("chat", "whatsapp", "telegram", "discord", "slack", "viber", "wechat", "line", "kakao", "messenger", "chat", "teams", "element", "matrix"),
        arrayOf("people", "facebook", "linkedin", "nextdoor", "meetup", "community", "social", "groups", "katana"),
        arrayOf("share", "twitter", "bluesky", "mastodon", "threads", "barcelona", "tumblr"),
        arrayOf("photos", "instagram", "pinterest", "flickr", "vsco", "snapseed", "lightroom"),
        arrayOf("lens", "snapchat", "lens", "googlelens", "ar"),
        arrayOf("translate", "translate", "translator", "duolingo", "babbel", "deepl", "language"),
        arrayOf("headphones", "spotify", "pandora", "deezer", "tidal", "soundcloud", "audible", "shazam", "bandcamp", "audiomack"),
        arrayOf("mic", "podcast", "podcasts", "pocketcasts", "castbox", "recorder", "voicerecorder", "voice", "soundrecorder", "anchor"),
        arrayOf("clapper", "netflix", "hulu", "disney", "hbo", "max", "primevideo", "avod", "paramount", "peacock", "crunchyroll", "plex", "jellyfin", "kodi", "movies", "cinema", "imdb", "letterboxd"),
        arrayOf("tv", "twitch", "tubi", "pluto", "sling", "youtube", "tiktok", "musically", "vimeo", "vlc", "mxtech", "tv"),
        arrayOf("remote", "remote", "roku", "firetv", "smartthings", "irremote"),
        arrayOf("cast", "cast", "chromecast", "miracast", "screencast", "screenmirror"),
        arrayOf("book", "kindle", "books", "audiobooks", "reader", "goodreads", "libby", "overdrive", "wattpad", "scribd", "bible", "quran", "comics"),
        arrayOf("news", "news", "nytimes", "guardian", "bbc", "cnn", "reuters", "feedly", "flipboard", "inoreader", "medium", "substack", "magazine"),
        arrayOf("bank", "bank", "chase", "wellsfargo", "bofa", "citi", "capitalone", "hsbc", "barclays", "santander", "revolut", "monzo", "n26", "schwab", "fidelity", "vanguard", "usaa", "ally", "discover", "amex", "americanexpress", "creditkarma", "banking"),
        arrayOf("chart", "robinhood", "coinbase", "binance", "kraken", "etrade", "webull", "stocks", "stock", "trading", "invest", "investing", "crypto", "finance", "mint", "ynab", "budget"),
        arrayOf("wallet", "wallet", "paypal", "venmo", "zelle", "cashapp", "squarecash", "pay", "gpay", "samsungpay", "wise", "klarna", "afterpay", "payments"),
        arrayOf("cart", "amazon", "mshop", "walmart", "target", "costco", "instacart", "kroger", "grocery", "groceries", "aliexpress", "temu", "shein", "wish", "ebay", "mercari", "offerup", "poshmark"),
        arrayOf("bag", "shop", "shopping", "etsy", "zara", "nike", "ikea", "bestbuy", "store", "mall", "fashion"),
        arrayOf("gift", "gift", "rewards", "loyalty", "coupons", "groupon", "honey", "ibotta"),
        arrayOf("ticket", "ticket", "tickets", "ticketmaster", "stubhub", "eventbrite", "fandango", "seatgeek", "events"),
        arrayOf("truck", "fedex", "ups", "usps", "dhl", "tracking", "delivery", "parcel", "shipping", "packages"),
        arrayOf("package", "package", "parcels", "storage", "archive", "zip", "rar", "7zip", "unzip"),
        arrayOf("food", "doordash", "ubereats", "grubhub", "deliveroo", "justeat", "postmates", "seamless", "yelp", "opentable", "recipes", "recipe", "cookbook", "food", "restaurant", "mcdonalds", "dominos", "pizza", "chipotle"),
        arrayOf("coffee", "starbucks", "dunkin", "coffee", "cafe", "tea"),
        arrayOf("plane", "airline", "airlines", "flights", "flight", "delta", "united", "aa", "southwest", "ryanair", "easyjet", "lufthansa", "emirates", "skyscanner", "kayak", "hopper", "flightradar", "tripit"),
        arrayOf("bed", "airbnb", "booking", "hotels", "hotel", "expedia", "trivago", "vrbo", "hostel", "marriott", "hilton", "agoda"),
        arrayOf("car", "uber", "lyft", "bolt", "taxi", "cabify", "grab", "didi", "waze", "android.auto", "autoplay", "carplay", "tesla", "parking", "spothero", "zipcar", "turo", "obd"),
        arrayOf("train", "transit", "citymapper", "moovit", "rail", "railway", "amtrak", "trainline", "metro", "subway", "bus", "mta", "tfl"),
        arrayOf("bike", "bike", "bicycle", "cycling", "lime", "bird", "citibike", "komoot", "strava", "zwift"),
        arrayOf("fuel", "gas", "gasbuddy", "fuel", "shell", "chargepoint", "ev", "charging", "electrify"),
        arrayOf("compass", "compass", "alltrails", "hiking", "gaia", "outdoor", "orienteering"),
        arrayOf("globe", "earth", "world", "atlas", "travel", "tripadvisor", "visit", "vpn"),
        arrayOf("rocket", "rocket", "launch", "nasa", "spacex"),
        arrayOf("planet", "skyview", "stellarium", "skymap", "astronomy", "planets", "space", "starwalk"),
        arrayOf("satellite", "satellite", "gps", "gpstest", "starlink", "tracker"),
        arrayOf("school", "classroom", "school", "canvas", "blackboard", "moodle", "coursera", "udemy", "edx", "khan", "khanacademy", "quizlet", "brainly", "photomath", "learn", "study", "education", "university"),
        arrayOf("briefcase", "workday", "office", "work", "jobs", "indeed", "glassdoor", "adp", "salesforce", "hubspot", "intune", "workspace", "citrix"),
        arrayOf("document", "docs", "word", "pdf", "acrobat", "adobe.reader", "documents", "wps", "writer", "scanner", "camscanner", "notion", "evernote", "obsidian", "onenote"),
        arrayOf("sheet", "sheets", "excel", "spreadsheet", "calc", "airtable"),
        arrayOf("slides", "slides", "powerpoint", "keynote", "presentation", "prezi"),
        arrayOf("pen", "draw", "sketch", "procreate", "ibispaint", "pen", "handwriting", "write", "signature", "squid", "nebo"),
        arrayOf("palette", "paint", "art", "canva", "picsart", "design", "color", "colour", "theme", "themes", "wallpaper", "wallpapers", "zedge", "icons", "iconpack"),
        arrayOf("scissors", "editor", "capcut", "inshot", "kinemaster", "vn", "videoeditor", "photoeditor", "cutout", "trim"),
        arrayOf("qr", "qr", "barcode", "qrcode", "qrscanner", "codescanner"),
        arrayOf("printer", "print", "printer", "printing", "hp", "epson", "canon", "brother", "printservice"),
        arrayOf("code", "github", "gitlab", "termux", "terminal", "ssh", "juicessh", "developer", "devtools", "code", "ide", "acode", "jupyter", "replit", "stackoverflow"),
        arrayOf("keyboard", "keyboard", "gboard", "swiftkey", "inputmethod", "ime", "typing"),
        arrayOf("robot", "gemini", "bard", "chatgpt", "openai", "copilot", "assistant", "bixby", "alexa", "perplexity", "ai", "bot"),
        arrayOf("cloud", "drive", "dropbox", "onedrive", "icloud", "box", "mega", "pcloud", "nextcloud", "backup", "sync", "cloud"),
        arrayOf("download", "download", "downloader", "torrent", "qbittorrent", "fdroid", "aurora", "apkpure", "installer", "update", "updater"),
        arrayOf("wifi", "wifi", "wlan", "network", "speedtest", "fing", "router", "hotspot", "internet"),
        arrayOf("bluetooth", "bluetooth", "buds", "earbuds", "pairing", "galaxybuds", "wearable", "pixelbuds"),
        arrayOf("battery", "battery", "accubattery", "power", "charger", "ampere"),
        arrayOf("flashlight", "flashlight", "torch", "light"),
        arrayOf("key", "password", "passwords", "bitwarden", "lastpass", "1password", "keepass", "dashlane", "authenticator", "authy", "otp", "2fa", "keys", "keychain"),
        arrayOf("shield", "security", "antivirus", "malwarebytes", "norton", "avast", "mcafee", "kaspersky", "protonvpn", "nordvpn", "expressvpn", "surfshark", "firewall", "privacy", "adblock", "adguard", "blokada"),
        arrayOf("bulb", "hue", "lifx", "govee", "nanoleaf", "lights", "lighting", "lamp", "wiz", "tuya", "smartlife", "ideas"),
        arrayOf("thermostat", "nest", "ecobee", "thermostat", "honeywell", "climate", "hvac", "temperature", "thermometer"),
        arrayOf("home", "googlehome", "chromecast.app", "smarthome", "homeassistant", "ring", "arlo", "wyze", "alarmcom", "simplisafe"),
        arrayOf("trash", "cleaner", "clean", "ccleaner", "cleanmaster", "junk", "recycle", "recyclebin", "trash"),
        arrayOf("gauge", "booster", "boost", "performance", "speed", "cpu", "benchmark", "antutu", "geekbench", "devicecare", "monitor", "gamebooster"),
        arrayOf("wrench", "tools", "toolbox", "utility", "utilities", "maintenance", "repair", "fix", "tweaks", "tasker", "macrodroid", "shizuku", "adb"),
        arrayOf("watch", "wear", "watch", "galaxywearable", "fitbit", "garmin", "amazfit", "zepp", "huaweihealth", "mibandnotify"),
        arrayOf("shoe", "running", "run", "runkeeper", "nikerun", "pedometer", "steps", "stepcounter", "walk"),
        arrayOf("heart", "health", "heart", "medical", "doctor", "mychart", "teladoc", "pulse", "bloodpressure", "cardio", "dating", "tinder", "bumble", "hinge", "okcupid"),
        arrayOf("pill", "pharmacy", "cvs", "walgreens", "goodrx", "medisafe", "pill", "medication", "medicine", "rx"),
        arrayOf("moon", "sleep", "calm", "headspace", "meditation", "meditate", "sleepcycle", "night", "dream", "noise", "whitenoise", "relax"),
        arrayOf("drop", "water", "hydro", "hydration", "watertracker", "period", "flo", "clue", "utility"),
        arrayOf("leaf", "garden", "plant", "plants", "planta", "nature", "inaturalist", "picturethis", "eco", "green", "seek"),
        arrayOf("paw", "pet", "pets", "dog", "cat", "rover", "petco", "chewy", "vet", "wag"),
        arrayOf("umbrella", "insurance", "geico", "progressive", "statefarm", "allstate", "rain", "radar", "rainviewer", "storm"),
        arrayOf("alarm", "alarm", "alarmy", "wakeup", "sleepasandroid"),
        arrayOf("timer", "timer", "stopwatch", "pomodoro", "focus", "forest", "countdown", "habit", "habits"),
        arrayOf("trophy", "espn", "sports", "score", "scores", "fantasy", "nfl", "nba", "mlb", "fifa", "uefa", "onefootball", "thescore", "fotmob", "sofascore", "league"),
        arrayOf("ball", "golf", "tennis", "soccer", "football", "basketball", "baseball", "cricket", "ball"),
        arrayOf("star", "favorites", "favourites", "reviews", "rating", "starred", "wishlist", "star"),
        arrayOf("bell", "notifications", "notification", "reminder", "reminders", "alerts", "notify", "pushbullet"),
        arrayOf("flag", "flags", "goals", "milestones", "tasks", "todo", "todoist", "ticktick", "things", "asana", "trello", "jira", "clickup", "monday"),
        arrayOf("puzzle", "puzzle", "sudoku", "crossword", "wordle", "jigsaw", "solitaire", "chess", "mahjong", "minesweeper", "brain", "trivia"),
        arrayOf("gamepad", "games", "game", "playgames", "steam", "xbox", "playstation", "nintendo", "epicgames", "geforcenow", "moonlight", "retroarch", "emulator", "gamehub", "gamelauncher"),
        arrayOf("dollar", "money", "currency", "exchange", "tip", "expenses", "splitwise", "invoice", "billing", "tax", "turbotax", "cash"),
        arrayOf("globe", "browser", "brave", "opera", "duckduckgo", "vivaldi", "edge", "kiwi", "samsunginternet", "tor", "torbrowser", "focus.browser", "web"),
        arrayOf("cart", "store", "market", "marketplace"),
        arrayOf("music", "music", "player", "musicplayer", "poweramp", "audio", "mp3", "song", "songs", "radio", "fm", "tunein", "iheart"),
        arrayOf("video", "video", "videos", "player", "mxplayer"),
        arrayOf("camera", "camera", "cam", "opencamera", "gcam", "filmic"),
        arrayOf("photos", "gallery", "photo", "photos", "album", "pictures", "images"),
        arrayOf("mail", "mail", "email", "gmail", "protonmail", "proton.mail", "tutanota", "yahoo.mobile.client.android.mail", "spark", "bluemail", "k9", "fairemail", "thunderbird", "aquamail"),
        arrayOf("messages", "sms", "messages", "messaging", "textra", "textnow", "googlevoice"),
        arrayOf("phone", "dialer", "phone", "call", "calls", "truecaller", "callblocker", "hiya", "contacts.phone"),
        arrayOf("contacts", "contacts", "contact", "people.app", "addressbook"),
        arrayOf("calendar", "calendar", "agenda", "planner", "schedule", "timetree", "fantastical", "outlook.calendar"),
        arrayOf("clock", "clock", "deskclock", "worldclock", "time"),
        arrayOf("notes", "notes", "keep", "note", "memo", "journal", "diary", "daylio", "simplenote", "standardnotes", "joplin", "markor"),
        arrayOf("calculator", "calculator", "calc", "math", "converter", "unitconverter", "tipcalc"),
        arrayOf("files", "files", "filemanager", "explorer", "myfiles", "documentsui", "solidexplorer", "mixplorer", "totalcommander", "folder", "folders"),
        arrayOf("maps", "maps", "map", "navigation", "navigator", "here", "osmand", "organicmaps", "mapsme", "sygic", "citymaps"),
        arrayOf("weather", "weather", "accuweather", "forecast", "yr", "windy", "meteo", "climacell", "tomorrow", "carrot"),
        arrayOf("fitness", "fitness", "gym", "workout", "training", "myfitnesspal", "fitbod", "peloton", "nike.ntc", "strong", "lifting", "yoga"),
        arrayOf("store", "vending", "playstore", "appstore", "galaxystore", "samsungapps", "appgallery"),
        arrayOf("search", "search", "googlequicksearchbox", "finder", "lookup", "find"),
        arrayOf("settings", "settings", "preferences", "config", "setup"),
        arrayOf("system", "system", "systemui", "device", "deviceinfo", "cpuz", "aida64", "sysinfo", "phoneinfo"),
        arrayOf("secure", "signal", "securesms", "encrypted", "vault", "locker", "applock", "hide", "securefolder", "privatespace"),
        arrayOf("computer", "computer", "remote.desktop", "rdp", "teamviewer", "anydesk", "vnc", "pc", "windows", "chromeremotedesktop", "linux"),
        arrayOf("alert", "emergency", "sos", "alert", "warning", "redalert", "safety", "911", "disaster", "earthquake"),
        arrayOf("radio", "radio", "scanner", "broadcastify", "ham", "sdr"),
        arrayOf("apps", "launcher", "apps", "applications", "appmanager", "drawer"),
    )

    /** Package words that say nothing about the app. */
    @JvmField
    val NOISE: List<String> = listOf(
        "com", "org", "net", "io", "de", "co", "uk", "us", "android", "app", "apps", "mobile", "client",
        "lite", "pro", "free", "beta", "dev", "debug", "release", "main", "google", "samsung", "sec", "huawei",
        "xiaomi", "miui", "oneplus", "oppo", "motorola", "sony", "lge", "htc", "zte", "www",
    )

    /**
     * Splits a package name or label into lower-case words: dots, spaces and punctuation separate
     * words (the raw text is matched separately by [hit] for dotted keywords like "android.auto").
     */
    @JvmStatic
    fun words(s: String?): List<String> {
        if (s.isNullOrEmpty()) return emptyList()
        return s.lowercase(Locale.US).split(WORD_SEPARATORS).filter { it.isNotEmpty() }
    }

    private val WORD_SEPARATORS = Regex("[^a-z0-9]+")

    /**
     * True when `keyword` names one of `words`: an exact word, or (for keywords at
     * least [PREFIX_MIN] long) the start of a word, or a dotted keyword contained in the
     * raw text. Short keywords like "ai" or "ev" must match a whole word so "mail" never matches "ai".
     */
    @JvmStatic
    fun hit(keyword: String, words: List<String>, raw: String?): Boolean {
        if (keyword.indexOf('.') >= 0) return raw != null && raw.lowercase(Locale.US).contains(keyword)
        return words.any { w -> w == keyword || (keyword.length >= PREFIX_MIN && w.startsWith(keyword)) }
    }

    /**
     * Picks a default pictogram for an app without a core rule: package words are tried against
     * every row first (package names are more reliable than localised labels), then label words,
     * then games fall back to `"gamepad"`. Package words "com", "org", "android", "app",
     * "apps", "mobile" and similar carry no meaning and are skipped.
     *
     * @param pkg      package name
     * @param label    the app's label (any case), may be null
     * @param category one of the [AppInfo] `CAT_*` constants
     * @return a name from [ALL], or null when nothing fits (the app keeps its own icon)
     */
    @JvmStatic
    fun defaultGlyph(pkg: String?, label: String?, category: Int): String? {
        val pw = words(pkg).filter { it !in NOISE }
        var g = firstRow(pw, pkg)
        if (g == null) g = firstRow(words(label), label)
        if (g == null && category == AppInfo.CAT_GAMES) g = "gamepad"
        return g
    }

    private fun firstRow(words: List<String>, raw: String?): String? {
        if (words.isEmpty()) return null
        for (row in KEYWORDS) {
            for (i in 1 until row.size) if (hit(row[i], words, raw)) return row[0]
        }
        return null
    }

    /** True when `name` is a known pictogram. */
    @JvmStatic
    fun exists(name: String?): Boolean = name != null && name in ALL
}
