package com.anivault.di

import com.anivault.core.AppPrefs
import com.anivault.core.DiagnosticsLog
import com.anivault.data.provider.AdapterFactory
import com.anivault.data.provider.ProviderRepository
import com.anivault.data.provider.VaultSourceStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Named
import javax.inject.Singleton

/** A second [ProviderRepository] that reads the H-Vault source list instead of the main one. */
@Module
@InstallIn(SingletonComponent::class)
object VaultSourcesModule {
    @Provides @Singleton @Named("vault")
    fun vaultProviders(store: VaultSourceStore, factory: AdapterFactory, diagnostics: DiagnosticsLog, prefs: AppPrefs): ProviderRepository =
        ProviderRepository(store, factory, diagnostics, prefs)
}
