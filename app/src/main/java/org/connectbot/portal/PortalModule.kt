package org.connectbot.portal

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object PortalModule {
    @Provides
    @Singleton
    fun providePortalStore(@ApplicationContext context: Context): PortalStore = PortalStore(context)

    @Provides
    @Singleton
    fun provideHubClient(store: PortalStore): HubClient = HubClient(store)

    @Provides
    @Singleton
    fun providePortalHubRepository(store: PortalStore, client: HubClient): PortalHubRepository = DefaultPortalHubRepository(store, client)
}
