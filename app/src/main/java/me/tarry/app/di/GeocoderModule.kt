package me.tarry.app.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import me.tarry.app.segmentation.AndroidPlaceGeocoder
import me.tarry.app.segmentation.PlaceGeocoder

@Module
@InstallIn(SingletonComponent::class)
abstract class GeocoderModule {
    @Binds
    abstract fun bindPlaceGeocoder(impl: AndroidPlaceGeocoder): PlaceGeocoder
}
