package com.tprlab.teletube

import android.app.Application

class TeleTubeApplication : Application() {
    internal val repository by lazy {
        TelegramRepository(this, Library.open(this))
    }
}
