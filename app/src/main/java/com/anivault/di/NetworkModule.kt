package com.anivault.di

import com.anivault.data.remote.cloudflare.CloudflareInterceptor
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton

/**
 * NOTE: no HttpLoggingInterceptor anywhere. Request lines contain search queries (which in
 * H-Vault mode are private titles) and Cloudflare cookies, and nothing here may log those.
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    /** Shared client for catalog providers and account APIs (timeouts are applied per call). */
    @Provides
    @Singleton
    @Named("api")
    fun provideApiClient(): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()

    /** Primary H-Vault source client (resolves the site's browser check through a WebView). */
    @Provides
    @Singleton
    @Named("scraper")
    fun provideScraperClient(cfInterceptor: CloudflareInterceptor): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .addInterceptor(cfInterceptor)
            .build()

    /** Plain client for the fallback H-Vault source. */
    @Provides
    @Singleton
    @Named("watchhentai")
    fun provideWatchHentaiClient(): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .build()
}
