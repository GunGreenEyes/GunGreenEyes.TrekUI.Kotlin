# TrekUI Keyboard: every entry point (IME service, settings activity, suite provider) is declared in the
# manifest or res/xml/method.xml and kept automatically by AAPT rules.
# Keep names readable in crash reports.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
