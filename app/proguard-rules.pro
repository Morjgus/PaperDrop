# Retrofit + OkHttp ship their own consumer ProGuard rules (bundled in the
# AAR since Retrofit 2.6 / OkHttp 4.x), so no manual rules are needed for them
# here.

# Gson deserializes these response models via reflection using field names
# declared in @SerializedName. R8 full mode may otherwise strip unused fields
# or rename them, breaking deserialization at runtime even though nothing
# calls them directly from code.
-keepclassmembers class de.paperdrop.data.api.TaskStatusResponse {
    <fields>;
}
-keepclassmembers class de.paperdrop.data.api.TagsResponse {
    <fields>;
}
-keepclassmembers class de.paperdrop.data.api.PaperlessLabel {
    <fields>;
}
