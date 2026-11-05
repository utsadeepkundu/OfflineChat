# R8 rules for OfflineChat.
#
# The app uses no reflection, no serialization library and no network stack, so almost nothing needs keeping.
# Room, Hilt and Compose ship their own consumer rules.

# Keep readable stack traces in crash reports without shipping source file names.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Enum.valueOf is used by Room's type handling.
-keepclassmembers enum * { public static **[] values(); public static ** valueOf(java.lang.String); }
