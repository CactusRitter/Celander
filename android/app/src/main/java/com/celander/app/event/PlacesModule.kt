package com.celander.app.event

import com.celander.app.core.di.IoDispatcher
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import okhttp3.OkHttpClient

@Module
@InstallIn(SingletonComponent::class)
object PlacesModule {
    @Provides
    @Singleton
    fun placeSearch(client: OkHttpClient, @IoDispatcher io: CoroutineDispatcher): PlaceSearch = PhotonPlaceSearch(client, io)
}
