package com.newsblur.di

import com.newsblur.repository.FolderListRepository
import com.newsblur.repository.FolderListRepositoryImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class FolderListModule {
    @Binds
    abstract fun bindFolderListRepository(impl: FolderListRepositoryImpl): FolderListRepository
}
