#!/bin/bash
# Hybrid build: original dex (as smali) + recompiled/new classes from mod/.
set -e
A=/home/claude/apk; B=/home/claude/build; D=$(ls -d $A/d2j/*/); JAR=/home/claude/sdk/android-35.jar
KS=${KS:-$B/signing/debug.keystore}
cd $B; rm -rf out; mkdir -p out/classes
find mod -name '*.java' > out/src.txt
javac -nowarn -Xlint:none -encoding UTF-8 -source 8 -target 8 -bootclasspath $JAR -cp $A/orig-classes.jar -d out/classes @out/src.txt 2>&1 | grep -v -E '^(Picked up|warning: \[options\]|Note:|[0-9]+ warnings?$)' || true
n=$(find mod -name '*.java' | wc -l); [ $(find out/classes -name '*.class' | wc -l) -ge $n ] || { echo "javac failed"; exit 1; }
for f in $(find mod -name '*.java'); do c=$(basename $f .java); [ -f out/classes/com/gungreeneyes/trekui/$c.class ] || { echo "javac failed ($c)"; exit 1; }; done
/usr/lib/android-sdk/build-tools/debian/dx --dex --min-sdk-version=26 --output=out/new.dex out/classes
sh $D/d2j-baksmali.sh -o out/smali_new out/new.dex >/dev/null 2>&1
cp -r $A/smali_orig out/smali
for f in $(find mod -name '*.java'); do c=$(basename $f .java); rm -f out/smali/com/gungreeneyes/trekui/$c.smali out/smali/com/gungreeneyes/trekui/$c\$*.smali; done
cp -r out/smali_new/. out/smali/
sh $D/d2j-smali.sh -o out/classes.dex out/smali >/dev/null 2>&1
java -jar $A/apktool.jar b $A/decoded -o out/base.apk >/dev/null 2>&1
cd out && cp base.apk unsigned.apk && zip -q -j unsigned.apk classes.dex && zipalign -f -p 4 unsigned.apk aligned.apk
mkdir -p $B/signing; [ -f "$KS" ] || keytool -genkeypair -keystore "$KS" -storepass android -alias androiddebugkey -keypass android -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1
apksigner sign --ks "$KS" --ks-pass pass:android --key-pass pass:android --out $B/out/TrekUI-Launcher.apk aligned.apk
apksigner verify $B/out/TrekUI-Launcher.apk && ls -la $B/out/TrekUI-Launcher.apk && echo BUILD_OK
