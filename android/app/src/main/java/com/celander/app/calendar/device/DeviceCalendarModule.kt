package com.celander.app.calendar.device

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class DeviceCalendarModule {
    @Binds
    abstract fun source(impl: ProviderCalendarSource): DeviceCalendarSource

    @Binds
    abstract fun store(impl: ProviderEventStore): DeviceEventStore
}
