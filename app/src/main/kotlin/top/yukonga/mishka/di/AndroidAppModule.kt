package top.yukonga.mishka.di

import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module
import top.yukonga.mishka.platform.ProfileFileManager
import top.yukonga.mishka.service.AndroidProfileFileManager

/**
 * app 壳（Android）层绑定：ProfileFileManager 的实现属服务层，故在此绑定而非 data 层。
 */
val androidAppModule = module {
    single<ProfileFileManager> { AndroidProfileFileManager(androidContext()) }
}
