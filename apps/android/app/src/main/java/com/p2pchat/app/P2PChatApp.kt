package com.p2pchat.app

import android.app.Application
import com.p2pchat.app.di.AppContainer

/**
 * アプリ全体で 1 つだけ作られる Application。
 * 部品（DB・Repository など）は [AppContainer] が持ち、ViewModel の生成時にここから取り出す。
 */
class P2PChatApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
