package com.gungreeneyes.trekui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The full pictogram catalogue and the keyword table that gives common apps a sensible default
 * pictogram (LCA-T22).
 *
 * <p>The 28 core pictograms are matched to exact packages by {@link IconFactory#GLYPH_RULES}; every
 * other app is looked up here by {@link #defaultGlyph}: its package name and label are split into
 * words and compared with {@link #KEYWORDS} (first row with a hit wins), and games without a hit
 * get the controller. The PNGs live in {@code assets/glyphs/}: the core 28 from
 * {@code tools/legacy/make_glyphs.py}, the rest rendered by {@code tools/art/glyph_render.py} from
 * {@code glyphs_extra.py}. Pure Java, unit-tested in {@code PictogramsTest}.
 */
final class Pictograms {
    private Pictograms() {}

    /** Shortest keyword that may match the start of a longer word ("uber" → "ubercab"); shorter ones must match whole words. */
    static final int PREFIX_MIN = 4;

    /** Every pictogram name, core set first, in picker order. */
    static final String[] ALL = {
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
    };

    /**
     * Keyword rows: the pictogram, then words found in package names or labels of apps it suits.
     * Order matters (first row with a hit wins), so specific rows come before broad ones.
     */
    static final String[][] KEYWORDS = {
        {"videocall", "zoom", "meet", "duo", "facetime", "webex", "skype", "videocall"},
        {"chat", "whatsapp", "telegram", "discord", "slack", "viber", "wechat", "line", "kakao", "messenger", "chat", "teams", "element", "matrix"},
        {"people", "facebook", "linkedin", "nextdoor", "meetup", "community", "social", "groups", "katana"},
        {"share", "twitter", "bluesky", "mastodon", "threads", "barcelona", "tumblr"},
        {"photos", "instagram", "pinterest", "flickr", "vsco", "snapseed", "lightroom"},
        {"lens", "snapchat", "lens", "googlelens", "ar"},
        {"translate", "translate", "translator", "duolingo", "babbel", "deepl", "language"},
        {"headphones", "spotify", "pandora", "deezer", "tidal", "soundcloud", "audible", "shazam", "bandcamp", "audiomack"},
        {"mic", "podcast", "podcasts", "pocketcasts", "castbox", "recorder", "voicerecorder", "voice", "soundrecorder", "anchor"},
        {"clapper", "netflix", "hulu", "disney", "hbo", "max", "primevideo", "avod", "paramount", "peacock", "crunchyroll", "plex", "jellyfin", "kodi", "movies", "cinema", "imdb", "letterboxd"},
        {"tv", "twitch", "tubi", "pluto", "sling", "youtube", "tiktok", "musically", "vimeo", "vlc", "mxtech", "tv"},
        {"remote", "remote", "roku", "firetv", "smartthings", "irremote"},
        {"cast", "cast", "chromecast", "miracast", "screencast", "screenmirror"},
        {"book", "kindle", "books", "audiobooks", "reader", "goodreads", "libby", "overdrive", "wattpad", "scribd", "bible", "quran", "comics"},
        {"news", "news", "nytimes", "guardian", "bbc", "cnn", "reuters", "feedly", "flipboard", "inoreader", "medium", "substack", "magazine"},
        {"bank", "bank", "chase", "wellsfargo", "bofa", "citi", "capitalone", "hsbc", "barclays", "santander", "revolut", "monzo", "n26", "schwab", "fidelity", "vanguard", "usaa", "ally", "discover", "amex", "americanexpress", "creditkarma", "banking"},
        {"chart", "robinhood", "coinbase", "binance", "kraken", "etrade", "webull", "stocks", "stock", "trading", "invest", "investing", "crypto", "finance", "mint", "ynab", "budget"},
        {"wallet", "wallet", "paypal", "venmo", "zelle", "cashapp", "squarecash", "pay", "gpay", "samsungpay", "wise", "klarna", "afterpay", "payments"},
        {"cart", "amazon", "mshop", "walmart", "target", "costco", "instacart", "kroger", "grocery", "groceries", "aliexpress", "temu", "shein", "wish", "ebay", "mercari", "offerup", "poshmark"},
        {"bag", "shop", "shopping", "etsy", "zara", "nike", "ikea", "bestbuy", "store", "mall", "fashion"},
        {"gift", "gift", "rewards", "loyalty", "coupons", "groupon", "honey", "ibotta"},
        {"ticket", "ticket", "tickets", "ticketmaster", "stubhub", "eventbrite", "fandango", "seatgeek", "events"},
        {"truck", "fedex", "ups", "usps", "dhl", "tracking", "delivery", "parcel", "shipping", "packages"},
        {"package", "package", "parcels", "storage", "archive", "zip", "rar", "7zip", "unzip"},
        {"food", "doordash", "ubereats", "grubhub", "deliveroo", "justeat", "postmates", "seamless", "yelp", "opentable", "recipes", "recipe", "cookbook", "food", "restaurant", "mcdonalds", "dominos", "pizza", "chipotle"},
        {"coffee", "starbucks", "dunkin", "coffee", "cafe", "tea"},
        {"plane", "airline", "airlines", "flights", "flight", "delta", "united", "aa", "southwest", "ryanair", "easyjet", "lufthansa", "emirates", "skyscanner", "kayak", "hopper", "flightradar", "tripit"},
        {"bed", "airbnb", "booking", "hotels", "hotel", "expedia", "trivago", "vrbo", "hostel", "marriott", "hilton", "agoda"},
        {"car", "uber", "lyft", "bolt", "taxi", "cabify", "grab", "didi", "waze", "android.auto", "autoplay", "carplay", "tesla", "parking", "spothero", "zipcar", "turo", "obd"},
        {"train", "transit", "citymapper", "moovit", "rail", "railway", "amtrak", "trainline", "metro", "subway", "bus", "mta", "tfl"},
        {"bike", "bike", "bicycle", "cycling", "lime", "bird", "citibike", "komoot", "strava", "zwift"},
        {"fuel", "gas", "gasbuddy", "fuel", "shell", "chargepoint", "ev", "charging", "electrify"},
        {"compass", "compass", "alltrails", "hiking", "gaia", "outdoor", "orienteering"},
        {"globe", "earth", "world", "atlas", "travel", "tripadvisor", "visit", "vpn"},
        {"rocket", "rocket", "launch", "nasa", "spacex"},
        {"planet", "skyview", "stellarium", "skymap", "astronomy", "planets", "space", "starwalk"},
        {"satellite", "satellite", "gps", "gpstest", "starlink", "tracker"},
        {"school", "classroom", "school", "canvas", "blackboard", "moodle", "coursera", "udemy", "edx", "khan", "khanacademy", "quizlet", "brainly", "photomath", "learn", "study", "education", "university"},
        {"briefcase", "workday", "office", "work", "jobs", "indeed", "glassdoor", "adp", "salesforce", "hubspot", "intune", "workspace", "citrix"},
        {"document", "docs", "word", "pdf", "acrobat", "adobe.reader", "documents", "wps", "writer", "scanner", "camscanner", "notion", "evernote", "obsidian", "onenote"},
        {"sheet", "sheets", "excel", "spreadsheet", "calc", "airtable"},
        {"slides", "slides", "powerpoint", "keynote", "presentation", "prezi"},
        {"pen", "draw", "sketch", "procreate", "ibispaint", "pen", "handwriting", "write", "signature", "squid", "nebo"},
        {"palette", "paint", "art", "canva", "picsart", "design", "color", "colour", "theme", "themes", "wallpaper", "wallpapers", "zedge", "icons", "iconpack"},
        {"scissors", "editor", "capcut", "inshot", "kinemaster", "vn", "videoeditor", "photoeditor", "cutout", "trim"},
        {"qr", "qr", "barcode", "qrcode", "qrscanner", "codescanner"},
        {"printer", "print", "printer", "printing", "hp", "epson", "canon", "brother", "printservice"},
        {"code", "github", "gitlab", "termux", "terminal", "ssh", "juicessh", "developer", "devtools", "code", "ide", "acode", "jupyter", "replit", "stackoverflow"},
        {"keyboard", "keyboard", "gboard", "swiftkey", "inputmethod", "ime", "typing"},
        {"robot", "gemini", "bard", "chatgpt", "openai", "copilot", "assistant", "bixby", "alexa", "perplexity", "ai", "bot"},
        {"cloud", "drive", "dropbox", "onedrive", "icloud", "box", "mega", "pcloud", "nextcloud", "backup", "sync", "cloud"},
        {"download", "download", "downloader", "torrent", "qbittorrent", "fdroid", "aurora", "apkpure", "installer", "update", "updater"},
        {"wifi", "wifi", "wlan", "network", "speedtest", "fing", "router", "hotspot", "internet"},
        {"bluetooth", "bluetooth", "buds", "earbuds", "pairing", "galaxybuds", "wearable", "pixelbuds"},
        {"battery", "battery", "accubattery", "power", "charger", "ampere"},
        {"flashlight", "flashlight", "torch", "light"},
        {"key", "password", "passwords", "bitwarden", "lastpass", "1password", "keepass", "dashlane", "authenticator", "authy", "otp", "2fa", "keys", "keychain"},
        {"shield", "security", "antivirus", "malwarebytes", "norton", "avast", "mcafee", "kaspersky", "protonvpn", "nordvpn", "expressvpn", "surfshark", "firewall", "privacy", "adblock", "adguard", "blokada"},
        {"bulb", "hue", "lifx", "govee", "nanoleaf", "lights", "lighting", "lamp", "wiz", "tuya", "smartlife", "ideas"},
        {"thermostat", "nest", "ecobee", "thermostat", "honeywell", "climate", "hvac", "temperature", "thermometer"},
        {"home", "googlehome", "chromecast.app", "smarthome", "homeassistant", "ring", "arlo", "wyze", "alarmcom", "simplisafe"},
        {"trash", "cleaner", "clean", "ccleaner", "cleanmaster", "junk", "recycle", "recyclebin", "trash"},
        {"gauge", "booster", "boost", "performance", "speed", "cpu", "benchmark", "antutu", "geekbench", "devicecare", "monitor", "gamebooster"},
        {"wrench", "tools", "toolbox", "utility", "utilities", "maintenance", "repair", "fix", "tweaks", "tasker", "macrodroid", "shizuku", "adb"},
        {"watch", "wear", "watch", "galaxywearable", "fitbit", "garmin", "amazfit", "zepp", "huaweihealth", "mibandnotify"},
        {"shoe", "running", "run", "runkeeper", "nikerun", "pedometer", "steps", "stepcounter", "walk"},
        {"heart", "health", "heart", "medical", "doctor", "mychart", "teladoc", "pulse", "bloodpressure", "cardio", "dating", "tinder", "bumble", "hinge", "okcupid"},
        {"pill", "pharmacy", "cvs", "walgreens", "goodrx", "medisafe", "pill", "medication", "medicine", "rx"},
        {"moon", "sleep", "calm", "headspace", "meditation", "meditate", "sleepcycle", "night", "dream", "noise", "whitenoise", "relax"},
        {"drop", "water", "hydro", "hydration", "watertracker", "period", "flo", "clue", "utility"},
        {"leaf", "garden", "plant", "plants", "planta", "nature", "inaturalist", "picturethis", "eco", "green", "seek"},
        {"paw", "pet", "pets", "dog", "cat", "rover", "petco", "chewy", "vet", "wag"},
        {"umbrella", "insurance", "geico", "progressive", "statefarm", "allstate", "rain", "radar", "rainviewer", "storm"},
        {"alarm", "alarm", "alarmy", "wakeup", "sleepasandroid"},
        {"timer", "timer", "stopwatch", "pomodoro", "focus", "forest", "countdown", "habit", "habits"},
        {"trophy", "espn", "sports", "score", "scores", "fantasy", "nfl", "nba", "mlb", "fifa", "uefa", "onefootball", "thescore", "fotmob", "sofascore", "league"},
        {"ball", "golf", "tennis", "soccer", "football", "basketball", "baseball", "cricket", "ball"},
        {"star", "favorites", "favourites", "reviews", "rating", "starred", "wishlist", "star"},
        {"bell", "notifications", "notification", "reminder", "reminders", "alerts", "notify", "pushbullet"},
        {"flag", "flags", "goals", "milestones", "tasks", "todo", "todoist", "ticktick", "things", "asana", "trello", "jira", "clickup", "monday"},
        {"puzzle", "puzzle", "sudoku", "crossword", "wordle", "jigsaw", "solitaire", "chess", "mahjong", "minesweeper", "brain", "trivia"},
        {"gamepad", "games", "game", "playgames", "steam", "xbox", "playstation", "nintendo", "epicgames", "geforcenow", "moonlight", "retroarch", "emulator", "gamehub", "gamelauncher"},
        {"dollar", "money", "currency", "exchange", "tip", "expenses", "splitwise", "invoice", "billing", "tax", "turbotax", "cash"},
        {"globe", "browser", "brave", "opera", "duckduckgo", "vivaldi", "edge", "kiwi", "samsunginternet", "tor", "torbrowser", "focus.browser", "web"},
        {"cart", "store", "market", "marketplace"},
        {"music", "music", "player", "musicplayer", "poweramp", "audio", "mp3", "song", "songs", "radio", "fm", "tunein", "iheart"},
        {"video", "video", "videos", "player", "mxplayer"},
        {"camera", "camera", "cam", "opencamera", "gcam", "filmic"},
        {"photos", "gallery", "photo", "photos", "album", "pictures", "images"},
        {"mail", "mail", "email", "gmail", "protonmail", "proton.mail", "tutanota", "yahoo.mobile.client.android.mail", "spark", "bluemail", "k9", "fairemail", "thunderbird", "aquamail"},
        {"messages", "sms", "messages", "messaging", "textra", "textnow", "googlevoice"},
        {"phone", "dialer", "phone", "call", "calls", "truecaller", "callblocker", "hiya", "contacts.phone"},
        {"contacts", "contacts", "contact", "people.app", "addressbook"},
        {"calendar", "calendar", "agenda", "planner", "schedule", "timetree", "fantastical", "outlook.calendar"},
        {"clock", "clock", "deskclock", "worldclock", "time"},
        {"notes", "notes", "keep", "note", "memo", "journal", "diary", "daylio", "simplenote", "standardnotes", "joplin", "markor"},
        {"calculator", "calculator", "calc", "math", "converter", "unitconverter", "tipcalc"},
        {"files", "files", "filemanager", "explorer", "myfiles", "documentsui", "solidexplorer", "mixplorer", "totalcommander", "folder", "folders"},
        {"maps", "maps", "map", "navigation", "navigator", "here", "osmand", "organicmaps", "mapsme", "sygic", "citymaps"},
        {"weather", "weather", "accuweather", "forecast", "yr", "windy", "meteo", "climacell", "tomorrow", "carrot"},
        {"fitness", "fitness", "gym", "workout", "training", "myfitnesspal", "fitbod", "peloton", "nike.ntc", "strong", "lifting", "yoga"},
        {"store", "vending", "playstore", "appstore", "galaxystore", "samsungapps", "appgallery"},
        {"search", "search", "googlequicksearchbox", "finder", "lookup", "find"},
        {"settings", "settings", "preferences", "config", "setup"},
        {"system", "system", "systemui", "device", "deviceinfo", "cpuz", "aida64", "sysinfo", "phoneinfo"},
        {"secure", "signal", "securesms", "encrypted", "vault", "locker", "applock", "hide", "securefolder", "privatespace"},
        {"computer", "computer", "remote.desktop", "rdp", "teamviewer", "anydesk", "vnc", "pc", "windows", "chromeremotedesktop", "linux"},
        {"alert", "emergency", "sos", "alert", "warning", "redalert", "safety", "911", "disaster", "earthquake"},
        {"radio", "radio", "scanner", "broadcastify", "ham", "sdr"},
        {"apps", "launcher", "apps", "applications", "appmanager", "drawer"},
    };

    /**
     * Splits a package name or label into lower-case words: dots, spaces and punctuation separate
     * words, and the whole package/label (lower-cased, punctuation removed) is added too so
     * multi-word keywords like "android.auto" match.
     */
    static List<String> words(String s) {
        if (s == null || s.isEmpty()) return Collections.emptyList();
        String lower = s.toLowerCase(Locale.US);
        List<String> out = new ArrayList<>();
        for (String w : lower.split("[^a-z0-9]+")) if (!w.isEmpty()) out.add(w);
        return out;
    }

    /**
     * True when {@code keyword} names one of {@code words}: an exact word, or (for keywords at
     * least {@link #PREFIX_MIN} long) the start of a word, or a dotted keyword contained in the
     * raw text. Short keywords like "ai" or "ev" must match a whole word so "mail" never matches "ai".
     */
    static boolean hit(String keyword, List<String> words, String raw) {
        if (keyword.indexOf('.') >= 0) return raw != null && raw.toLowerCase(Locale.US).contains(keyword);
        for (String w : words) {
            if (w.equals(keyword)) return true;
            if (keyword.length() >= PREFIX_MIN && w.startsWith(keyword)) return true;
        }
        return false;
    }

    /**
     * Picks a default pictogram for an app without a core rule: package words are tried against
     * every row first (package names are more reliable than localised labels), then label words,
     * then games fall back to {@code "gamepad"}. Package words "com", "org", "android", "app",
     * "apps", "mobile" and similar carry no meaning and are skipped.
     *
     * @param pkg      package name
     * @param label    the app's label (any case), may be null
     * @param category one of the {@link AppInfo} {@code CAT_*} constants
     * @return a name from {@link #ALL}, or null when nothing fits (the app keeps its own icon)
     */
    static String defaultGlyph(String pkg, String label, int category) {
        List<String> pw = new ArrayList<>(words(pkg));
        pw.removeAll(NOISE);
        String g = firstRow(pw, pkg);
        if (g == null) g = firstRow(words(label), label);
        if (g == null && category == AppInfo.CAT_GAMES) g = "gamepad";
        return g;
    }

    /** Package words that say nothing about the app. */
    static final List<String> NOISE = java.util.Arrays.asList(
            "com", "org", "net", "io", "de", "co", "uk", "us", "android", "app", "apps", "mobile", "client",
            "lite", "pro", "free", "beta", "dev", "debug", "release", "main", "google", "samsung", "sec", "huawei",
            "xiaomi", "miui", "oneplus", "oppo", "motorola", "sony", "lge", "htc", "zte", "www");

    private static String firstRow(List<String> words, String raw) {
        if (words.isEmpty()) return null;
        for (String[] row : KEYWORDS) {
            for (int i = 1; i < row.length; i++) if (hit(row[i], words, raw)) return row[0];
        }
        return null;
    }

    /** True when {@code name} is a known pictogram. */
    static boolean exists(String name) {
        if (name == null) return false;
        for (String n : ALL) if (n.equals(name)) return true;
        return false;
    }
}
