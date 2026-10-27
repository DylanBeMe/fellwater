# Media3 and Compose ship their own consumer rules; nothing here uses reflection,
# so no application classes need explicit keeping - only readable stack traces.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# NewPipeExtractor (YouTube): Rhino runs YouTube's player JS and loads its own
# classes reflectively, and the extractor looks up its date patterns by name.
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter
-keep class org.schabi.newpipe.extractor.timeago.patterns.** { *; }
-keep class * extends com.google.protobuf.GeneratedMessageLite { <fields>; }
-dontwarn org.mozilla.javascript.**
-dontwarn javax.script.**
-dontwarn java.beans.**
