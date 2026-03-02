package de.paperdrop.data.preferences

data class AppSettings(
    val paperlessUrl: String        = "",
    val apiToken: String            = "",
    val watchFolderUri: String      = "",
    val afterUpload: AfterUploadAction = AfterUploadAction.KEEP,
    val moveTargetUri: String       = "",
    val isWatchingEnabled: Boolean  = false,
    val selectedLabelIds: Set<Int>  = emptySet()
)

enum class AfterUploadAction { KEEP, DELETE, MOVE }
