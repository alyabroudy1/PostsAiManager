package com.postsaimanager.core.data.di

import android.content.Context
import androidx.room.Room
import com.postsaimanager.core.data.database.PamDatabase
import com.postsaimanager.core.data.database.PamMigrations
import com.postsaimanager.core.data.database.dao.ConversationDao
import com.postsaimanager.core.data.database.dao.DocumentDao
import com.postsaimanager.core.data.database.dao.DocumentChunkDao
import com.postsaimanager.core.data.database.dao.FieldRevisionDao
import com.postsaimanager.core.data.database.dao.MessageDao
import com.postsaimanager.core.data.database.dao.ProfileDao
import com.postsaimanager.core.data.database.dao.TimelineDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): PamDatabase {
        return Room.databaseBuilder(
            context,
            PamDatabase::class.java,
            PamDatabase.DATABASE_NAME,
        )
            // fallbackToDestructiveMigration() removed: it wiped every user document on
            // any schema change. Migrations are explicit now, and a missing one fails
            // loudly at open time rather than deleting data.
            .addMigrations(*PamMigrations.ALL)
            .build()
    }

    @Provides
    fun provideDocumentDao(database: PamDatabase): DocumentDao = database.documentDao()

    @Provides
    fun provideProfileDao(database: PamDatabase): ProfileDao = database.profileDao()

    @Provides
    fun provideTimelineDao(database: PamDatabase): TimelineDao = database.timelineDao()

    @Provides
    fun provideConversationDao(database: PamDatabase): ConversationDao = database.conversationDao()

    @Provides
    fun provideMessageDao(database: PamDatabase): MessageDao = database.messageDao()

    @Provides
    fun provideDocumentChunkDao(database: PamDatabase): DocumentChunkDao =
        database.documentChunkDao()

    @Provides
    fun provideFieldRevisionDao(database: PamDatabase): FieldRevisionDao =
        database.fieldRevisionDao()
}
