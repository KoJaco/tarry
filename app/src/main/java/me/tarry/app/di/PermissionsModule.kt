package me.tarry.app.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import me.tarry.app.ui.permissions.AndroidPermissionChecker
import me.tarry.app.ui.permissions.PermissionChecker

@Module
@InstallIn(SingletonComponent::class)
abstract class PermissionsModule {
    @Binds
    abstract fun bindPermissionChecker(impl: AndroidPermissionChecker): PermissionChecker
}
