package dev.voicemacro.studio.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [Flow::class, Step::class, SlotDefinition::class, RunLog::class], version = 4, exportSchema = false)
@TypeConverters(Converters::class)
abstract class MacroDatabase : RoomDatabase() {
    abstract fun macroDao(): MacroDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE steps ADD COLUMN selectorViewId TEXT")
            }
        }
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE steps ADD COLUMN selectorContext TEXT")
            }
        }
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE steps ADD COLUMN selectorCenterX INTEGER")
                db.execSQL("ALTER TABLE steps ADD COLUMN selectorCenterY INTEGER")
            }
        }
        @Volatile
        private var INSTANCE: MacroDatabase? = null

        fun getDatabase(context: Context): MacroDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    MacroDatabase::class.java,
                    "macro_database"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
