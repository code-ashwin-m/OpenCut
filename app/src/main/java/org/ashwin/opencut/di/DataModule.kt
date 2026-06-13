package org.ashwin.opencut.di

import com.google.gson.Gson
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.ashwin.opencut.data.repository.FileProjectRepositoryImpl
import org.ashwin.opencut.domain.repository.ProjectRepository
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    fun provideGson(): Gson {
        return Gson()
    }

    @Provides
    @Singleton
    fun provideProjectRepository(impl: FileProjectRepositoryImpl): ProjectRepository {
        return impl
    }
}
