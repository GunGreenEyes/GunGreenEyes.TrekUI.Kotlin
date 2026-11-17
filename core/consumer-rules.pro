# Applied to every app that depends on :core. The suite provider is instantiated by the framework from
# each app's manifest (kept automatically); keep its contract constants readable in crash reports.
-keep class com.gungreeneyes.trekui.core.SuiteProvider { *; }
-keep class com.gungreeneyes.trekui.licensing.PurchaseActivity { *; }
