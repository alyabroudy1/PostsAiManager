package com.postsaimanager.core.designsystem.icon

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Settings

/**
 * Central icon registry. All icons used in the app are referenced here
 * for consistency and easy replacement.
 *
 * Uses AutoMirrored variants where available for correct RTL behavior.
 */
object PamIcons {
    // Navigation
    val Home = Icons.Filled.Home
    val HomeOutlined = Icons.Outlined.Home
    val Documents = Icons.Filled.Description
    val DocumentsOutlined = Icons.Outlined.Description
    val Profiles = Icons.Filled.People
    val ProfilesOutlined = Icons.Outlined.People
    val Settings = Icons.Filled.Settings
    val SettingsOutlined = Icons.Outlined.Settings

    // Actions
    val Add = Icons.Filled.Add
    val Back = Icons.AutoMirrored.Filled.ArrowBack
    val Close = Icons.Filled.Close
    val Delete = Icons.Filled.Delete
    val Edit = Icons.Filled.Edit
    val Search = Icons.Filled.Search
    val Filter = Icons.Filled.FilterList
    val More = Icons.Filled.MoreVert
    val Send = Icons.AutoMirrored.Filled.Send

    // Features
    val Camera = Icons.Filled.CameraAlt
    val Upload = Icons.Filled.Upload
    val Gallery = Icons.Filled.Image
    val Pdf = Icons.Filled.PictureAsPdf
    val AiChat = Icons.AutoMirrored.Filled.Chat
    val AiModel = Icons.Filled.SmartToy
    val Tag = Icons.Filled.Tag
    val Favorite = Icons.Filled.Favorite
    val FavoriteOutlined = Icons.Filled.FavoriteBorder

    // States
    val Error = Icons.Filled.Error
}
