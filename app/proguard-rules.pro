# The launcher instantiates its own classes from the manifest only; framework reflection is limited to
# these entry points. Keep them and their names so crash reports stay readable.
-keep class com.gungreeneyes.trekui.MainActivity { *; }
-keep class com.gungreeneyes.trekui.SettingsActivity { *; }
-keep class com.gungreeneyes.trekui.MediaListener { *; }
-keep class com.gungreeneyes.trekui.*Widget { *; }
-keep class com.gungreeneyes.trekui.Cloak$Receiver { *; }
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
