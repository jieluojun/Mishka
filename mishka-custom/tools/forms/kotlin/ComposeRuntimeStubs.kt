/**
 * 离线逻辑测试用的注解桩：anchor 包（AnchorScan / AnchorEdit / AnchorBlock）只用到
 * compose-runtime 的 @Immutable 标注，纯逻辑编译不需要真实 Compose，给个同名注解即可。
 * 打进 app 时由真实 compose-runtime 提供，本文件只进测试编译，不进交付源码。
 */
package androidx.compose.runtime

annotation class Immutable
