package com.payamesh.licenseadmin.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [LicenseSeatEntity::class, LicenseCheckLogEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class LicenseAdminDatabase : RoomDatabase() {
    abstract fun licenseSeatDao(): LicenseSeatDao

    companion object {
        @Volatile
        private var instance: LicenseAdminDatabase? = null

        fun getInstance(context: Context): LicenseAdminDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    LicenseAdminDatabase::class.java,
                    "license_admin.db",
                ).build().also { instance = it }
            }
        }
    }
}
