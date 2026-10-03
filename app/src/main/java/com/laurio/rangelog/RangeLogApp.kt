package com.laurio.rangelog

import android.app.Application
import com.laurio.rangelog.data.AppDatabase
import com.laurio.rangelog.data.Repository

class RangeLogApp : Application() {
    val repository: Repository by lazy { Repository(this, AppDatabase.create(this)) }
}
