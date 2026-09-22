package app.glance.wallet.core.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

@Database(
    entities = [
        WatchedKeyEntity::class,
        DerivedAddressEntity::class,
        AddressHistoryEntity::class,
        BlockTimestampCacheEntity::class,
        UtxoEntity::class,
        LabelEntity::class,
        ServerConfigEntity::class,
        FiatPriceCacheEntity::class,
        DecoyProfileEntity::class,
        WalletGroupEntity::class,
    ],
    version = 12,
    exportSchema = true,
)
@TypeConverters(GlanceDatabase.Converters::class)
abstract class GlanceDatabase : RoomDatabase() {
    abstract fun watchedKeyDao(): WatchedKeyDao
    abstract fun walletGroupDao(): WalletGroupDao
    abstract fun derivedAddressDao(): DerivedAddressDao
    abstract fun addressHistoryDao(): AddressHistoryDao
    abstract fun utxoDao(): UtxoDao
    abstract fun blockTimestampCacheDao(): BlockTimestampCacheDao
    abstract fun labelDao(): LabelDao
    abstract fun serverConfigDao(): ServerConfigDao
    abstract fun fiatPriceCacheDao(): FiatPriceCacheDao
    abstract fun decoyProfileDao(): DecoyProfileDao
    abstract fun walletScreenDao(): WalletScreenDao

    class Converters {
        @TypeConverter fun scriptTypeToString(value: ScriptType): String = value.name
        @TypeConverter fun stringToScriptType(value: String): ScriptType = ScriptType.valueOf(value)
        @TypeConverter fun watchTargetTypeToString(value: WatchTargetType): String = value.name
        @TypeConverter fun stringToWatchTargetType(value: String): WatchTargetType = WatchTargetType.valueOf(value)
        @TypeConverter fun chainToString(value: AddressChain): String = value.name
        @TypeConverter fun stringToChain(value: String): AddressChain = AddressChain.valueOf(value)
        @TypeConverter fun labelReferenceTypeToString(value: LabelReferenceType): String = value.name
        @TypeConverter fun stringToLabelReferenceType(value: String): LabelReferenceType = LabelReferenceType.valueOf(value)
    }

    companion object {
        internal fun inMemory(context: Context): GlanceDatabase =
            Room.inMemoryDatabaseBuilder(context, GlanceDatabase::class.java)
                .allowMainThreadQueries()
                .build()
    }
}
