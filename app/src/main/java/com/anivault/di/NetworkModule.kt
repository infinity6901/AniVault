package com.anivault.di

import com.anivault.data.remote.cloudflare.CloudflareInterceptor
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    // ── Cloudflare-bypassing client — hanime.tv (primary H-Vault source) ───
    @Provides
    @Singleton
    @Named("scraper")
    fun provideScraperClient(cfInterceptor: CloudflareInterceptor): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .addInterceptor(cfInterceptor)
            .addInterceptor(
                HttpLoggingInterceptor().apply {
                    level = HttpLoggingInterceptor.Level.HEADERS
                }
            )
            .build()

    // ── Plain scraper client — watchhentai.net (fallback H-Vault source) ───
    //
    // watchhentai.net does not currently sit behind Cloudflare Bot Management,
    // so a plain client with a realistic User-Agent is sufficient.
    // If they add CF protection in future, swap this for the CF-intercepting
    // client above (and inject a second CloudflareInterceptor instance).
    @Provides
    @Singleton
    @Named("watchhentai")
    fun provideWatchHentaiClient(): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .addInterceptor(
                HttpLoggingInterceptor().apply {
                    level = HttpLoggingInterceptor.Level.BASIC
                }
            )
            .build()
}
