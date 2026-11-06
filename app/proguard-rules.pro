# App-specific R8 rules. Library rules (e.g. SQLCipher) come from consumer-rules.pro of :data.

# Readable crash reports (diagnostics/CrashLog): keep line numbers; class and method names are restored with the
# release's mapping.txt, which is archived with every release (docs/22-deployment-guide.md).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
