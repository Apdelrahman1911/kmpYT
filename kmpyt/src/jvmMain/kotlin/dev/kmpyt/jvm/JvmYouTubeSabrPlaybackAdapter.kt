package dev.kmpyt.jvm

import dev.kmpyt.YouTubeNativePlaybackLimits
import dev.kmpyt.YouTubePlaybackAdapter
import dev.kmpyt.internal.FileSabrPlaybackAdapter
import io.ktor.client.HttpClient

/** The host retains its HTTP client and supplies an existing app-private cache root. */
fun createJvmYouTubeSabrPlaybackAdapter(httpClient: HttpClient, cacheDirectory: String,
    limits: YouTubeNativePlaybackLimits = YouTubeNativePlaybackLimits()): YouTubePlaybackAdapter =
    FileSabrPlaybackAdapter(httpClient, cacheDirectory, limits)
