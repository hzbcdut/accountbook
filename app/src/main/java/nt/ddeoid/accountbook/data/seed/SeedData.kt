package nt.ddeoid.accountbook.data.seed

import kotlinx.serialization.Serializable

@Serializable
data class SeedPlatformsFile(
    val platforms: List<SeedPlatform>,
)

@Serializable
data class SeedPlatform(
    val id: String,
    val name: String,
)

@Serializable
data class SeedTag(
    val id: String,
    val name: String,
    /** ARGB 字符串,例如 `#FFD0BCFF`。 */
    val color: String,
    val sortOrder: Int,
)

@Serializable
data class SeedTagsFile(
    val tags: List<SeedTag>,
)