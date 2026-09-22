package app.glance.wallet.core.data.security

import android.content.Context
import androidx.room.Room
import app.glance.wallet.core.data.db.GlanceDatabase
import app.glance.wallet.core.data.db.GlanceDatabaseMigrations
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

class SqlCipherDatabaseFactory(
    private val context: Context,
    private val keyProvider: DatabaseKeyProvider,
) {
    fun open(databaseName: String = DATABASE_NAME): GlanceDatabase {
        loadSqlCipherNativeLibrary()
        return Room.databaseBuilder(context, GlanceDatabase::class.java, databaseName)
            .openHelperFactory(SupportOpenHelperFactory(keyProvider.getOrCreate()))
            .addMigrations(*GlanceDatabaseMigrations.ALL)
            .build()
    }

    companion object {
        const val DATABASE_NAME = "glance-wallet.db"

        @Volatile
        private var nativeLibraryLoaded = false

        private fun loadSqlCipherNativeLibrary() {
            if (!nativeLibraryLoaded) {
                synchronized(this) {
                    if (!nativeLibraryLoaded) {
                        System.loadLibrary("sqlcipher")
                        nativeLibraryLoaded = true
                    }
                }
            }
        }
    }
}
