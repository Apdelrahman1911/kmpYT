package dev.kmpyt.android

import android.content.Context
import dev.kmpyt.YouTubeNativePlaybackLimits
import dev.kmpyt.YouTubePlaybackAdapter
import dev.kmpyt.internal.FileSabrPlaybackAdapter
import io.ktor.client.HttpClient

/** Android's cache root is already app-private; all media HTTP remains inside kmpYT. */
fun createAndroidYouTubeSabrPlaybackAdapter(context: Context, httpClient: HttpClient,
    limits: YouTubeNativePlaybackLimits = YouTubeNativePlaybackLimits()): YouTubePlaybackAdapter =
    FileSabrPlaybackAdapter(httpClient, context.applicationContext.cacheDir.canonicalPath, limits)
