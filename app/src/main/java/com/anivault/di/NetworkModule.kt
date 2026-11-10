package com.anivault.di

import com.anivault.data.provider.RedirectGuardInterceptor
import com.anivault.data.provider.SafeDns
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
 * All clients enforce [SafeDns] and [RedirectGuardInterceptor] against SSRF/plain-HTTP redirects.
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
            .dns(SafeDns)
            .followRedirects(true)
            .followSslRedirects(false)
            .addNetworkInterceptor(RedirectGuardInterceptor)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()

    /** Primary H-Vault source client (resolves the site's browser check through a WebView when needed). */
    @Provides
    @Singleton
    @Named("scraper")
    fun provideScraperClient(cfInterceptor: CloudflareInterceptor): OkHttpClient =
        OkHttpClient.Builder()
            .dns(SafeDns)
            .followRedirects(true)
            .followSslRedirects(false)
            .addNetworkInterceptor(RedirectGuardInterceptor)
            .connectTimeout(25, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .addInterceptor(cfInterceptor)
            .build()

    /** Plain client for JSON APIs and adaptive scrapers (no WebView CF interceptor). */
    @Provides
    @Singleton
    @Named("watchhentai")
    fun provideWatchHentaiClient(): OkHttpClient =
        OkHttpClient.Builder()
            .dns(SafeDns)
            .followRedirects(true)
            .followSslRedirects(false)
            .addNetworkInterceptor(RedirectGuardInterceptor)
            .connectTimeout(18, TimeUnit.SECONDS)
            .readTimeout(35, TimeUnit.SECONDS)
            .build()
}
