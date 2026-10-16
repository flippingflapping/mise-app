package app.mise.di

import android.content.Context
import androidx.room.Room
import app.mise.data.db.AnnotationDao
import app.mise.data.db.MiseDatabase
import app.mise.data.db.RecipeDao
import app.mise.data.db.SourceTextDao
import app.mise.data.db.ViewStateDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

/** Wall clock, injectable for tests. */
fun interface Clock {
    fun now(): Long
}

/** Lives as long as the process: for work that must outlive a screen (pushing notes, syncing). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides @Singleton
    fun clock(): Clock = Clock { System.currentTimeMillis() }

    @Provides @Singleton @ApplicationScope
    fun appScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides @Singleton
    fun httpClient(): HttpClient = HttpClient(OkHttp) {
        expectSuccess = false
        install(HttpTimeout) {
            connectTimeoutMillis = 4_000
            requestTimeoutMillis = 20_000
        }
    }

    @Provides @Singleton
    fun database(@ApplicationContext context: Context): MiseDatabase =
        Room.databaseBuilder(context, MiseDatabase::class.java, MiseDatabase.NAME)
            .addMigrations(*MiseDatabase.MIGRATIONS)
            .build()

    @Provides fun recipeDao(db: MiseDatabase): RecipeDao = db.recipes()
    @Provides fun annotationDao(db: MiseDatabase): AnnotationDao = db.annotations()
    @Provides fun viewStateDao(db: MiseDatabase): ViewStateDao = db.viewState()
    @Provides fun sourceTextDao(db: MiseDatabase): SourceTextDao = db.sourceTexts()
}

@Module
@InstallIn(SingletonComponent::class)
object SourceModule {
    @Provides
    fun sourceProvider(factory: app.mise.data.source.SourceFactory): app.mise.data.repo.SourceProvider =
        app.mise.data.repo.SourceProvider(factory::create)
}

@Module
@InstallIn(SingletonComponent::class)
object SettingsModule {
    @Provides
    fun settingsStore(repo: app.mise.data.settings.SettingsRepository): app.mise.data.settings.SettingsStore = repo
}
